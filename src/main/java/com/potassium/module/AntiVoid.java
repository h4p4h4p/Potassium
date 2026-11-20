package com.potassium.module;

import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Catches a long fall before it becomes a void death.
 *
 * <p>Flag deliberately sends a movement the server cannot accept. Vanilla's moved-too-quickly
 * handler answers with S08PacketPlayerPosLook, putting the player back at its last accepted
 * position, which is the ledge the fall started from.</p>
 *
 * <p>Blink holds outgoing movement while there is no landing inside the configured distance.
 * The server keeps its last position at the edge, so cancelling the held movement and restoring
 * the last ground position puts the client back where the server still believes it is.</p>
 */
public class AntiVoid extends Module {

    public static final String MODE_FLAG = "Flag";
    public static final String MODE_BLINK = "Blink";

    private static final String HANDLER_NAME = "potassium_antivoid";

    // Vanilla rejects a movement whose squared distance exceeds 100, then sends a setback.
    private static final double FLAG_DISTANCE = 101.0D;
    private static final int BLINK_HOLD_TICKS = 10;
    private static final double POSITION_EPSILON_SQ = 1.0E-8D;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_FLAG, MODE_BLINK}, 0);
    private final SliderSetting maxFallDistanceSetting = new SliderSetting("Max Fall Distance",
            3.0D, 1.0D, 30.0D, 0.5D);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Object queueLock = new Object();
    private final Deque<Packet> queuedMovement = new ArrayDeque<>();

    private BlinkHandler handler;
    private Channel boundChannel;
    private WorldClient lastWorld;

    private boolean blinkActive;
    private int blinkTicks;
    private int blinkCooldownTicks;
    private volatile boolean serverCorrection;
    private boolean flagSent;
    private String lastMode;

    private boolean safeSet;
    private double safeX;
    private double safeY;
    private double safeZ;

    public AntiVoid() {
        super("AntiVoid", Category.MOVEMENT);
        settings.add(modeSetting);
        settings.add(maxFallDistanceSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private String mode() {
        String mode = modeSetting.getValue();
        return mode == null ? MODE_FLAG : mode;
    }

    @Override
    public String getTag() {
        return mode();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            resetAll();
            lastWorld = null;
            return;
        }

        if (mc.theWorld != lastWorld) {
            resetAll();
            lastWorld = mc.theWorld;
            if (player.onGround) saveSafePosition(player);
            return;
        }

        if (player.isDead || player.getHealth() <= 0.0F) {
            resetAll();
            return;
        }

        // Water, lava, ladders, vehicles and creative flight all provide their own safety.
        if (player.capabilities.isFlying || player.isRiding()
                || player.isInWater() || player.isInLava() || player.isOnLadder()) {
            if (blinkActive) discardBlink();
            flagSent = false;
            if (player.onGround) saveSafePosition(player);
            return;
        }

        if (player.onGround) {
            if (blinkActive) discardBlink();
            saveSafePosition(player);
            flagSent = false;
            blinkCooldownTicks = 0;
            return;
        }

        String activeMode = mode();
        if (!activeMode.equals(lastMode)) {
            // Do not let a held blink survive a switch to Flag, or a pending flag suppress Blink.
            if (blinkActive) discardBlink();
            flagSent = false;
            blinkCooldownTicks = 0;
            lastMode = activeMode;
        }

        if (MODE_BLINK.equals(activeMode)) {
            tickBlink(player);
        } else {
            tickFlag(player);
        }
    }

    private void tickFlag(EntityPlayerSP player) {
        if (flagSent) return;
        if (player.fallDistance < maxFallDistanceSetting.getValue()) return;
        if (mc.getNetHandler() == null) return;

        flagSent = true;
        player.fallDistance = 0.0F;

        // One impossible move makes the server correct the position for us. The client is not
        // moved here; the S08 that comes back is the teleport to the last accepted position.
        mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(
                player.posX, player.posY + FLAG_DISTANCE, player.posZ, false));
    }

    private void tickBlink(EntityPlayerSP player) {
        if (blinkActive) {
            // Retry the pipeline attachment in case the first attempt ran before the channel was ready.
            armChannel();
            if (serverCorrection) {
                discardBlink();
                return;
            }

            blinkTicks++;
            if (blinkTicks >= BLINK_HOLD_TICKS) {
                rescueBlink(player);
            }
            return;
        }

        if (blinkCooldownTicks > 0) {
            blinkCooldownTicks--;
            return;
        }
        if (!safeSet || player.fallDistance <= 0.0F) return;
        if (!shouldStartBlink(player)) return;

        armChannel();
        beginBlink();
    }

    /**
     * Starts before the player has actually fallen the full distance when there is no landing
     * inside that distance. That keeps the held movement short enough for the server to still
     * consider the last ground position current.
     */
    private boolean shouldStartBlink(EntityPlayerSP player) {
        double maxDistance = maxFallDistanceSetting.getValue();
        if (player.fallDistance >= maxDistance) return true;

        Vec3 start = new Vec3(player.posX, player.posY + 0.1D, player.posZ);
        Vec3 end = new Vec3(player.posX, player.posY - maxDistance - 0.5D, player.posZ);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(start, end, true, true, false);
        return hit == null;
    }

    private void beginBlink() {
        synchronized (queueLock) {
            if (blinkActive) return;
            queuedMovement.clear();
            serverCorrection = false;
            blinkTicks = 0;
            blinkActive = true;
        }
    }

    /**
     * The client position no longer matches the position held at the start of the blink, so drop
     * the held movement and return to the last position where the player was standing on ground.
     */
    private void rescueBlink(EntityPlayerSP player) {
        if (!safeSet) {
            discardBlink();
            return;
        }

        double dx = player.posX - safeX;
        double dy = player.posY - safeY;
        double dz = player.posZ - safeZ;
        discardBlink();

        if (dx * dx + dy * dy + dz * dz <= POSITION_EPSILON_SQ) return;

        player.setPositionAndUpdate(safeX, safeY, safeZ);
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        player.fallDistance = 0.0F;
        blinkCooldownTicks = BLINK_HOLD_TICKS;

        // The held packets never reached the server, so this is the first movement it sees after
        // the blink. Sending it immediately avoids waiting a tick at the old position.
        if (mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(
                    safeX, safeY, safeZ, true));
        }
    }

    private void saveSafePosition(EntityPlayerSP player) {
        safeX = player.posX;
        safeY = player.posY;
        safeZ = player.posZ;
        safeSet = true;
    }

    private void discardBlink() {
        synchronized (queueLock) {
            blinkActive = false;
            queuedMovement.clear();
        }
        blinkTicks = 0;
        serverCorrection = false;
    }

    private void resetAll() {
        discardBlink();
        flagSent = false;
        safeSet = false;
        blinkCooldownTicks = 0;
        detachHandler();
    }

    @Override
    public void onEnable() {
        lastWorld = mc.theWorld;
        lastMode = mode();
        flagSent = false;
        safeSet = false;
        blinkCooldownTicks = 0;
        discardBlink();
        if (mc.thePlayer != null && mc.thePlayer.onGround) {
            saveSafePosition(mc.thePlayer);
        }
    }

    @Override
    public void onDisable() {
        EntityPlayerSP player = mc.thePlayer;
        if (blinkActive && player != null && !player.onGround && safeSet) {
            rescueBlink(player);
        } else {
            discardBlink();
        }
        flagSent = false;
        safeSet = false;
        blinkCooldownTicks = 0;
        detachHandler();
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;

        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;

        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (boundChannel != channel) {
            removeFrom(boundChannel);
            boundChannel = channel;
            handler = null;
        }
        if (handler == null) {
            handler = new BlinkHandler();
        }

        if (channel.pipeline().get(HANDLER_NAME) == null) {
            final BlinkHandler created = handler;
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.isActive() && channel.pipeline().get(HANDLER_NAME) == null) {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, created);
                    }
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void detachHandler() {
        handler = null;
        Channel channel = boundChannel;
        boundChannel = null;
        removeFrom(channel);
    }

    private void removeFrom(Channel channel) {
        if (channel == null || !channel.isActive()) return;
        try {
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER_NAME) != null) {
                        channel.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private class BlinkHandler extends ChannelDuplexHandler {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (msg instanceof C03PacketPlayer) {
                synchronized (queueLock) {
                    if (blinkActive) {
                        queuedMovement.add((Packet) msg);
                        promise.setSuccess();
                        return;
                    }
                }
            }
            ctx.write(msg, promise);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof S08PacketPlayerPosLook) {
                synchronized (queueLock) {
                    if (blinkActive) serverCorrection = true;
                }
            }
            ctx.fireChannelRead(msg);
        }
    }
}
