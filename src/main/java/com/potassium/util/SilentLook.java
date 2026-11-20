package com.potassium.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The rotation the server is told to believe, shared by every module that aims somewhere
 * other than the camera.
 *
 * Modules used to hand the server a look packet of their own before an action and another
 * one to put the camera back afterwards. That is two extra position updates inside a single
 * tick, which packet rate checks read as a timer violation, and the immediate flip back made
 * the attack look like it was thrown facing away from the target.
 *
 * Instead the aim is written into the player's rotation for the one moment vanilla builds
 * its movement packet: Forge fires PlayerTickEvent END at the end of EntityPlayer#onUpdate,
 * and EntityPlayerSP#onUpdate calls onUpdateWalkingPlayer right afterwards. The camera is put
 * back at the end of the client tick, before anything is rendered, and the packet that went
 * out carries the aim. Modules then act at the end of the tick, so their own packet follows
 * the rotation the server has just been given rather than the previous one.
 *
 * Nothing is injected while the client is already sending movement packets, because the aim
 * rides along with them. The only packet this adds is the single look packet for a tick in
 * which vanilla sent none, which is what a player standing still while aiming produces.
 */
public final class SilentLook {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String HANDLER_NAME = "potassium_silent_look";

    /** How close a sent rotation has to be to the aim to count as having carried it. */
    private static final float EPSILON = 0.5F;

    private static SilentLook instance;

    private static volatile float aimYaw;
    private static volatile float aimPitch;
    private static volatile boolean requested;
    private static volatile boolean applied;
    private static volatile float savedYaw;
    private static volatile float savedPitch;

    /** Set by the outbound handler once a look packet carrying this aim has gone out. */
    private static volatile boolean aimSentThisTick;

    private static Channel boundChannel;
    private static LookHandler handler;

    private SilentLook() {
    }

    /**
     * Asks for this tick's server rotation. Renew it every tick that a module is aiming;
     * a tick without a request simply leaves the camera rotation in place.
     */
    public static void request(float yaw, float pitch) {
        register();
        armChannel();
        aimYaw = yaw;
        aimPitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        requested = true;
    }

    /** The rotation the player is really looking along, ignoring any silent aim. */
    public static float cameraYaw() {
        if (applied) return savedYaw;
        EntityPlayerSP player = mc.thePlayer;
        return player == null ? 0.0F : player.rotationYaw;
    }

    public static float cameraPitch() {
        if (applied) return savedPitch;
        EntityPlayerSP player = mc.thePlayer;
        return player == null ? 0.0F : player.rotationPitch;
    }

    private static void register() {
        if (instance != null) return;
        instance = new SilentLook();
        MinecraftForge.EVENT_BUS.register(instance);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickStart(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        requested = false;
        aimSentThisTick = false;
        // The player cannot be restored if it was replaced mid-tick, so never stay applied.
        if (applied) restore();
    }

    /** Runs after the player's own update and before vanilla builds the movement packet. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerTickEnd(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !requested || applied) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || event.player != player) return;
        // A riding player sends its look from a code path this cannot reach.
        if (player.isRiding() || mc.currentScreen != null) return;

        savedYaw = player.rotationYaw;
        savedPitch = player.rotationPitch;
        player.rotationYaw = aimYaw;
        player.rotationPitch = aimPitch;
        applied = true;
    }

    /**
     * The tick's movement packet is queued by now. If it did not carry the aim (nothing was
     * sent at all because the player stood still), send the one look packet for this tick.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !requested || aimSentThisTick) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.getNetHandler() == null || player.isRiding()) return;

        mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C06PacketPlayerPosLook(
                player.posX, player.posY, player.posZ, aimYaw, aimPitch, player.onGround));
        aimSentThisTick = true;
    }

    /** Puts the camera back before the frame is rendered. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTickEndRestore(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (applied) restore();
    }

    private static void restore() {
        applied = false;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;
        player.rotationYaw = savedYaw;
        player.rotationPitch = savedPitch;
    }

    private static void armChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (boundChannel != channel) {
            boundChannel = channel;
            handler = null;
        }
        if (handler != null) return;

        final LookHandler created = new LookHandler();
        handler = created;
        try {
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.isActive() && channel.pipeline().get(HANDLER_NAME) == null) {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, created);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** Watches outgoing looks so no second packet is sent for one that already had the aim. */
    private static final class LookHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (requested && (msg instanceof C03PacketPlayer.C05PacketPlayerLook
                    || msg instanceof C03PacketPlayer.C06PacketPlayerPosLook)) {
                C03PacketPlayer packet = (C03PacketPlayer) msg;
                if (Math.abs(MathHelper.wrapAngleTo180_float(packet.getYaw() - aimYaw)) < EPSILON
                        && Math.abs(packet.getPitch() - aimPitch) < EPSILON) {
                    aimSentThisTick = true;
                }
            }
            super.write(ctx, msg, promise);
        }
    }
}
