package com.potassium.module;

import com.potassium.gui.Theme;
import com.potassium.mixin.S14PacketEntityAccessor;
import com.potassium.settings.BooleanSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.RenderUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.ThreadQuickExitException;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Holds back an opponent's incoming position updates to create a short, closer "ghost".
 *
 * <p>Only the target's S14/S15/S16/S17 movement packets and S18 teleports are queued. Health,
 * velocity, chat, sound and the rest of the packet stream continue normally. The true server
 * position is reconstructed from those same packets, so the module can tell whether the ghost
 * is actually helping. When the real position is closer than the ghost, the queue is flushed
 * immediately instead of keeping a farther position.</p>
 *
 * <p>Show Real Position draws that reconstructed position as a box the size of the target's
 * hitbox, the way FakeLag draws the server position of the local player.</p>
 *
 * <p>Server hit validation is still authoritative. This is a lag/ghost aid, not a spoofed reach
 * packet, and strict anti-cheats may flag or reject hits after a long hold.</p>
 */
public class Backtrack extends Module {

    private static final String HANDLER_NAME = "potassium_backtrack";
    private static final double DISTANCE_EPSILON = 0.01D;
    private static final double REAL_POSITION_EPSILON = 0.01D;
    private static final double NEAR_CAMERA_MARGIN = 0.05D;
    private static final Field S14_ENTITY_ID = resolveS14EntityId();

    private final SliderSetting delaySetting = new SliderSetting("Delay", 250, 50, 1000, 50);
    private final SliderSetting rangeSetting = new SliderSetting("Range", 4.0, 0.0, 6.0, 0.1);
    private final BooleanSetting onlyWhenNeedSetting = new BooleanSetting("Only When Need", true);
    private final BooleanSetting realPositionSetting = new BooleanSetting("Show Real Position", true);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Object lock = new Object();
    private final Deque<Packet> queuedPackets = new ArrayDeque<>();

    private BacktrackHandler handler;
    private Channel boundChannel;
    private WorldClient lastWorld;
    private EntityPlayer target;

    // Guarded by lock: the Netty handler and the client tick both touch this state.
    private int targetId = -1;
    private boolean holding;
    private boolean draining;
    private long holdStartedAt;
    private double trueX;
    private double trueY;
    private double trueZ;

    public Backtrack() {
        super("Backtrack", Category.COMBAT);
        settings.add(delaySetting);
        settings.add(rangeSetting);
        settings.add(onlyWhenNeedSetting);
        settings.add(realPositionSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public String getTag() {
        return Math.round(delaySetting.getValue()) + "ms";
    }

    /**
     * Draws the position the server has for the target, which is the point the held-back
     * movement packets will eventually move them to. The target's own hitbox is offset from
     * their delayed client position, so the marker matches their real size and pose.
     */
    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled() || !realPositionSetting.getValue()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        EntityPlayer tracked = target;
        if (tracked == null || !isValidTarget(tracked)) return;

        double realX;
        double realY;
        double realZ;
        synchronized (lock) {
            if (targetId != tracked.getEntityId()) return;
            realX = trueX;
            realY = trueY;
            realZ = trueZ;
        }

        // Nothing to show while the client already holds the server's position.
        double ox = realX - tracked.posX;
        double oy = realY - tracked.posY;
        double oz = realZ - tracked.posZ;
        if (ox * ox + oy * oy + oz * oz
                < REAL_POSITION_EPSILON * REAL_POSITION_EPSILON) return;

        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;

        AxisAlignedBB box = tracked.getEntityBoundingBox().offset(ox, oy, oz);
        if (RenderUtil.pointNearBox(camX, camY, camZ, box, NEAR_CAMERA_MARGIN)) return;

        AxisAlignedBB relative = box.offset(-camX, -camY, -camZ);
        int color = Theme.ACCENT;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

        Tessellator tessellator = Tessellator.getInstance();
        RenderUtil.drawFilledBox(tessellator,
                relative.minX, relative.minY, relative.minZ,
                relative.maxX, relative.maxY, relative.maxZ,
                r, g, b, 45);
        RenderUtil.drawOutlinedBox(tessellator, relative, r, g, b, 210);

        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        armChannel();

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || player.isDead || player.getHealth() <= 0.0F) {
            dropQueue();
            clearTargetState();
            lastWorld = mc.theWorld;
            return;
        }

        if (mc.theWorld != lastWorld) {
            lastWorld = mc.theWorld;
            dropQueue();
            clearTargetState();
            return;
        }

        // Do not leave a target frozen behind a GUI. Flushing restores the real position first.
        if (mc.currentScreen != null) {
            flush();
            clearTargetState();
            return;
        }

        int lockedId;
        double trackedX;
        double trackedY;
        double trackedZ;
        boolean currentlyHolding;
        synchronized (lock) {
            lockedId = targetId;
            trackedX = trueX;
            trackedY = trueY;
            trackedZ = trueZ;
            currentlyHolding = holding;
        }

        EntityPlayer tracked = target;
        if (tracked != null && tracked.getEntityId() != lockedId) {
            tracked = null;
            target = null;
        }

        if (!isValidTarget(tracked)) {
            flush();
            clearTargetState();

            tracked = findTarget();
            if (tracked == null) return;

            target = tracked;
            selectTarget(tracked);

            synchronized (lock) {
                lockedId = targetId;
                trackedX = trueX;
                trackedY = trueY;
                trackedZ = trueZ;
                currentlyHolding = holding;
            }
        }

        double range = rangeSetting.getValue();
        double rangeSquared = range * range;
        // Compare feet positions on both sides. Mixing an entity-box distance with a
        // feet distance makes a stationary target look farther than its ghost.
        double trueDistanceSquared = player.getDistanceSq(trackedX, trackedY, trackedZ);
        double displayedDistanceSquared = player.getDistanceSq(tracked.posX, tracked.posY, tracked.posZ);

        // The real target has moved beyond the activation range. Stop holding and reacquire later.
        if (trueDistanceSquared > rangeSquared) {
            flush();
            clearTargetState();
            return;
        }

        // The real position is closer than the ghost. Catching up gives a better hit chance.
        if (trueDistanceSquared < displayedDistanceSquared - DISTANCE_EPSILON) {
            flush();
            return;
        }

        boolean wantHold = !onlyWhenNeedSetting.getValue()
                || trueDistanceSquared > displayedDistanceSquared + DISTANCE_EPSILON;

        if (!wantHold) {
            if (currentlyHolding) flush();
            return;
        }

        if (!currentlyHolding) {
            startHolding();
            return;
        }

        boolean queueEmpty;
        long startedAt;
        synchronized (lock) {
            queueEmpty = queuedPackets.isEmpty();
            startedAt = holdStartedAt;
        }

        long delay = (long) delaySetting.getValue();
        if (!queueEmpty && startedAt > 0L && System.currentTimeMillis() - startedAt >= delay) {
            flush();
        }
    }

    private boolean isValidTarget(EntityPlayer player) {
        if (mc.thePlayer == null || mc.theWorld == null) return false;
        return player != null
                && player != mc.thePlayer
                && !player.isDead
                && player.deathTime <= 0
                && player.worldObj == mc.theWorld
                && !player.isInvisible()
                && !AntiBot.isBot(player)
                && mc.theWorld.getEntityByID(player.getEntityId()) == player;
    }

    private EntityPlayer findTarget() {
        if (mc.thePlayer == null || mc.theWorld == null) return null;

        double bestDistanceSquared = rangeSetting.getValue() * rangeSetting.getValue();
        EntityPlayer best = null;

        for (EntityPlayer candidate : mc.theWorld.playerEntities) {
            if (!isValidTarget(candidate)) continue;

            double distanceSquared = mc.thePlayer.getDistanceSq(candidate.posX, candidate.posY, candidate.posZ);
            if (distanceSquared <= bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = candidate;
            }
        }

        return best;
    }

    private void selectTarget(EntityPlayer player) {
        synchronized (lock) {
            targetId = player.getEntityId();
            trueX = player.serverPosX / 32.0D;
            trueY = player.serverPosY / 32.0D;
            trueZ = player.serverPosZ / 32.0D;
            holding = false;
            draining = false;
            holdStartedAt = 0L;
            queuedPackets.clear();
        }
    }

    private void startHolding() {
        synchronized (lock) {
            if (!holding) {
                holding = true;
                holdStartedAt = 0L;
            }
        }
    }

    /**
     * Releases every queued packet on the client thread, in arrival order. Processing a packet
     * from the Netty thread would race the client world, so the queue is drained here instead.
     */
    private void flush() {
        List<Packet> pending;
        synchronized (lock) {
            if (queuedPackets.isEmpty()) {
                holding = false;
                draining = false;
                holdStartedAt = 0L;
                return;
            }

            draining = true;
            pending = new ArrayList<>(queuedPackets);
            queuedPackets.clear();
            holding = false;
            holdStartedAt = 0L;
        }

        NetHandlerPlayClient netHandler = mc.getNetHandler();
        if (netHandler == null || mc.theWorld == null || mc.thePlayer == null) {
            synchronized (lock) {
                queuedPackets.clear();
                draining = false;
            }
            return;
        }

        while (true) {
            for (Packet packet : pending) {
                try {
                    packet.processPacket(netHandler);
                } catch (ThreadQuickExitException ignored) {
                    // A delayed packet can legitimately race an entity/world teardown.
                }
            }

            // While packets were processed, the Netty thread may have queued newer target
            // packets. Drain those too so they cannot overtake the packets just applied.
            synchronized (lock) {
                if (queuedPackets.isEmpty()) {
                    draining = false;
                    holding = false;
                    holdStartedAt = 0L;
                    return;
                }

                pending = new ArrayList<>(queuedPackets);
                queuedPackets.clear();
            }
        }
    }

    private void dropQueue() {
        synchronized (lock) {
            queuedPackets.clear();
            holding = false;
            draining = false;
            holdStartedAt = 0L;
        }
    }

    private void clearTargetState() {
        target = null;
        synchronized (lock) {
            targetId = -1;
            queuedPackets.clear();
            holding = false;
            draining = false;
            holdStartedAt = 0L;
            trueX = 0.0D;
            trueY = 0.0D;
            trueZ = 0.0D;
        }
    }

    /**
     * Called from the Netty thread when the connection itself invalidates the target. The client
     * tick will clear its target reference when it sees the ID change.
     */
    private void invalidateFromNetty() {
        synchronized (lock) {
            queuedPackets.clear();
            holding = false;
            draining = false;
            holdStartedAt = 0L;
            targetId = -1;
        }
    }

    @Override
    public void onEnable() {
        target = null;
        lastWorld = mc.theWorld;
        synchronized (lock) {
            targetId = -1;
            queuedPackets.clear();
            holding = false;
            draining = false;
            holdStartedAt = 0L;
            trueX = 0.0D;
            trueY = 0.0D;
            trueZ = 0.0D;
        }
        armChannel();
    }

    @Override
    public void onDisable() {
        // Flush before dropping the queue so the client ends up at the server's real position.
        flush();
        clearTargetState();
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;

        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;

        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (handler == null) {
            handler = new BacktrackHandler();
            boundChannel = channel;
        } else if (boundChannel != channel) {
            final Channel oldChannel = boundChannel;
            boundChannel = channel;

            // The old queue belongs to the old connection, so it must not be applied to this one.
            target = null;
            synchronized (lock) {
                targetId = -1;
                queuedPackets.clear();
                holding = false;
                draining = false;
                holdStartedAt = 0L;
            }

            channel.eventLoop().execute(() -> {
                try {
                    if (oldChannel != null && oldChannel.isActive()) {
                        oldChannel.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Exception ignored) {
                }
            });

        }

        // Retry until the packet handler exists on this channel. A failed async add must not
        // leave the module permanently unarmed.
        if (channel.pipeline().get(HANDLER_NAME) == null) {
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER_NAME) == null) {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
                    }
                } catch (Exception ignored) {
                }
            });
        }
    }

    private int s14EntityId(S14PacketEntity packet) {
        // Direct field read when the accessor mixin is applied, reflection when it is not.
        try {
            return ((S14PacketEntityAccessor) packet).potassium$entityId();
        } catch (Throwable ignored) {
        }
        if (S14_ENTITY_ID != null) {
            try {
                Object value = S14_ENTITY_ID.get(packet);
                if (value instanceof Integer) return (Integer) value;
            } catch (Throwable ignored) {
            }
        }

        try {
            if (mc.theWorld == null) return -1;
            Entity entity = packet.getEntity(mc.theWorld);
            return entity == null ? -1 : entity.getEntityId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static Field resolveS14EntityId() {
        // try the runtime-remapped name first, then the MCP/SRG names for development jars
        try {
            String[] remapped = ObfuscationReflectionHelper.remapFieldNames(
                    S14PacketEntity.class.getName(), "entityId", "field_149074_a");
            return ReflectionHelper.findField(S14PacketEntity.class, remapped);
        } catch (Throwable ignored) {
        }

        try {
            // "a" is the notch name used by the reobfuscated release jar.
            return ReflectionHelper.findField(S14PacketEntity.class, "entityId", "field_149074_a", "a");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean handleMovement(S14PacketEntity packet) {
        int id = s14EntityId(packet);
        if (id < 0) return false;

        synchronized (lock) {
            if (id != targetId) return false;

            trueX += packet.func_149062_c() / 32.0D;
            trueY += packet.func_149061_d() / 32.0D;
            trueZ += packet.func_149064_e() / 32.0D;

            if (!holding && !draining) return false;
            if (queuedPackets.isEmpty()) holdStartedAt = System.currentTimeMillis();
            queuedPackets.add(packet);
            return true;
        }
    }

    private boolean handleTeleport(S18PacketEntityTeleport packet) {
        synchronized (lock) {
            if (packet.getEntityId() != targetId) return false;

            trueX = packet.getX() / 32.0D;
            trueY = packet.getY() / 32.0D;
            trueZ = packet.getZ() / 32.0D;

            if (!holding && !draining) return false;
            if (queuedPackets.isEmpty()) holdStartedAt = System.currentTimeMillis();
            queuedPackets.add(packet);
            return true;
        }
    }

    private void handleDestroy(S13PacketDestroyEntities packet) {
        synchronized (lock) {
            if (targetId < 0) return;

            for (int id : packet.getEntityIDs()) {
                if (id == targetId) {
                    queuedPackets.clear();
                    holding = false;
                    draining = false;
                    holdStartedAt = 0L;
                    targetId = -1;
                    return;
                }
            }
        }
    }

    private final class BacktrackHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            boolean held = false;

            if (isEnabled() && msg instanceof Packet) {
                Packet packet = (Packet) msg;

                if (packet instanceof S14PacketEntity) {
                    held = handleMovement((S14PacketEntity) packet);
                } else if (packet instanceof S18PacketEntityTeleport) {
                    held = handleTeleport((S18PacketEntityTeleport) packet);
                } else if (packet instanceof S13PacketDestroyEntities) {
                    handleDestroy((S13PacketDestroyEntities) packet);
                } else if (packet instanceof S08PacketPlayerPosLook
                        || packet instanceof S07PacketRespawn) {
                    invalidateFromNetty();
                }
            }

            if (!held) {
                ctx.fireChannelRead(msg);
            }
        }
    }
}
