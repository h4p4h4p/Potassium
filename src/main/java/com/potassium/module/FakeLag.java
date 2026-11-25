package com.potassium.module;

import com.potassium.gui.Theme;
import com.potassium.settings.BooleanSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.RenderUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * Holds every position packet back for a fixed number of milliseconds.
 *
 * <p>Each moving C03 is queued with the time it is due and released on the Netty event loop,
 * so the server walks through the positions at the normal twenty a second, just late. Look-only
 * packets are not delayed: they carry no position, and holding them would only lag aim.</p>
 *
 * <p>The module can draw the position the server currently has for the player. That position
 * only changes when a delayed packet actually leaves, so the marker is eased toward it every
 * frame instead of teleporting each time a burst lands.</p>
 *
 * <p>Disable on Hurt watches the absorption pool as well as health, because a golden apple's
 * extra hearts swallow a hit without health ever moving.</p>
 */
public class FakeLag extends Module {

    private static final String HANDLER_NAME = "potassium_fakelag";

    /** How quickly the marker glides to a freshly received server position, in seconds. */
    private static final double GHOST_RESPONSE_SECONDS = 0.04D;
    /** A jump this large is a teleport, not walking; the marker snaps rather than slides. */
    private static final double GHOST_SNAP_DISTANCE = 6.0D;
    private static final double NEAR_CAMERA_MARGIN = 0.05D;

    private final SliderSetting delaySetting = new SliderSetting("Delay", 200, 50, 2000, 10);
    private final BooleanSetting serverPositionSetting = new BooleanSetting("Server Position", true);
    private final BooleanSetting disableOnHurtSetting = new BooleanSetting("Disable on Hurt", false);
    private final BooleanSetting disableOnAttackSetting = new BooleanSetting("Disable on Attack", false);

    private final Minecraft mc = Minecraft.getMinecraft();

    private Channel boundChannel;
    private LagHandler handler;
    /** Read by the Netty handler, written by the client tick. */
    private volatile long delayMillis = 200L;
    private volatile boolean ghostSnap = true;

    private volatile double[] serverPos = new double[3];

    private final Object ghostLock = new Object();
    private double ghostX;
    private double ghostY;
    private double ghostZ;
    private boolean ghostSet;
    private long lastGhostFrameNanos;

    private WorldClient lastWorld;
    private float lastHealth = -1.0F;
    private float lastAbsorption = -1.0F;

    public FakeLag() {
        super("FakeLag", Category.MOVEMENT);
        settings.add(delaySetting);
        settings.add(serverPositionSetting);
        settings.add(disableOnHurtSetting);
        settings.add(disableOnAttackSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;

        if (mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.isDead) {
            lastHealth = -1.0F;
            lastAbsorption = -1.0F;
            return;
        }

        delayMillis = (long) delaySetting.getValue();
        armChannel();

        if (mc.theWorld != lastWorld) {
            lastWorld = mc.theWorld;
            setServerPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, true);
            lastHealth = mc.thePlayer.getHealth();
            lastAbsorption = mc.thePlayer.getAbsorptionAmount();
            return;
        }

        // A golden apple's extra hearts take a hit before health moves, so a drop in either
        // pool means damage was taken. Both are tracked even when the option is off, so
        // enabling it mid-fight cannot compare against a stale value.
        float health = mc.thePlayer.getHealth();
        float absorption = mc.thePlayer.getAbsorptionAmount();
        boolean hurt = (lastHealth >= 0.0F && health < lastHealth - 0.01F)
                || (lastAbsorption >= 0.0F && absorption < lastAbsorption - 0.01F);
        if (disableOnHurtSetting.getValue() && hurt) {
            setEnabled(false);
            return;
        }
        lastHealth = health;
        lastAbsorption = absorption;
    }

    /**
     * An attack is the one action a fake lag must never cover: the server would judge the hit
     * from a position it has not caught up to yet. Disabling flushes the queue, and because the
     * flush task is queued on the event loop before the attack packet's write, the server gets
     * the real position first.
     */
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (!isEnabled() || !disableOnAttackSetting.getValue()) return;
        if (event.entityPlayer != mc.thePlayer) return;
        setEnabled(false);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!isEnabled() || !serverPositionSetting.getValue()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        double[] target = serverPos;
        long now = System.nanoTime();
        double deltaSeconds = lastGhostFrameNanos == 0L ? 1.0D / 60.0D
                : Math.min(0.05D, (now - lastGhostFrameNanos) / 1.0e9D);
        lastGhostFrameNanos = now;

        double dx = target[0] - ghostX;
        double dy = target[1] - ghostY;
        double dz = target[2] - ghostZ;
        synchronized (ghostLock) {
            if (!ghostSet || ghostSnap
                    || dx * dx + dy * dy + dz * dz > GHOST_SNAP_DISTANCE * GHOST_SNAP_DISTANCE) {
                ghostX = target[0];
                ghostY = target[1];
                ghostZ = target[2];
                ghostSet = true;
                ghostSnap = false;
            } else {
                double share = 1.0D - Math.exp(-deltaSeconds / GHOST_RESPONSE_SECONDS);
                ghostX += dx * share;
                ghostY += dy * share;
                ghostZ += dz * share;
            }
        }

        drawGhostBox();
    }

    private void drawGhostBox() {
        double gx;
        double gy;
        double gz;
        synchronized (ghostLock) {
            if (!ghostSet) return;
            gx = ghostX;
            gy = ghostY;
            gz = ghostZ;
        }

        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;

        // The player's own hitbox, moved to where the server believes the player stands.
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox()
                .offset(gx - mc.thePlayer.posX, gy - mc.thePlayer.posY, gz - mc.thePlayer.posZ);
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

    private void setServerPosition(double x, double y, double z, boolean snap) {
        if (snap) {
            serverPos = new double[]{x, y, z};
            ghostSnap = true;
            return;
        }
        double[] current = serverPos;
        if (Math.abs(current[0] - x) < 1.0E-4D
                && Math.abs(current[1] - y) < 1.0E-4D
                && Math.abs(current[2] - z) < 1.0E-4D) {
            return;
        }
        serverPos = new double[]{x, y, z};
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (handler != null && boundChannel == channel) {
            if (channel.pipeline().get(HANDLER_NAME) == null) addHandler(channel);
            return;
        }

        final LagHandler oldHandler = handler;
        final Channel oldChannel = boundChannel;
        handler = new LagHandler();
        boundChannel = channel;

        if (oldChannel != null && oldChannel.isActive() && oldHandler != null) {
            oldChannel.eventLoop().execute(() -> {
                try {
                    oldHandler.flushNow();
                } catch (Exception ignored) {
                }
                try {
                    if (oldChannel.pipeline().get(HANDLER_NAME) != null) {
                        oldChannel.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Exception ignored) {
                }
            });
        }

        addHandler(channel);
    }

    private void addHandler(Channel channel) {
        final LagHandler added = handler;
        if (added == null) return;
        channel.eventLoop().execute(() -> {
            try {
                if (channel.isActive() && channel.pipeline().get(HANDLER_NAME) == null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME, added);
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public String getTag() {
        return Math.round(delaySetting.getValue()) + "ms";
    }

    @Override
    public void onEnable() {
        delayMillis = (long) delaySetting.getValue();
        lastWorld = mc.theWorld;
        lastHealth = mc.thePlayer == null ? -1.0F : mc.thePlayer.getHealth();
        lastAbsorption = mc.thePlayer == null ? -1.0F : mc.thePlayer.getAbsorptionAmount();
        ghostSnap = true;
        ghostSet = false;
        lastGhostFrameNanos = 0L;
        if (mc.thePlayer != null) {
            setServerPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, true);
        }
        armChannel();
    }

    @Override
    public void onDisable() {
        final LagHandler released = handler;
        final Channel channel = boundChannel;
        handler = null;
        boundChannel = null;

        if (channel != null && channel.isActive() && released != null) {
            channel.eventLoop().execute(() -> {
                try {
                    released.flushNow();
                } catch (Exception ignored) {
                }
                try {
                    if (channel.pipeline().get(HANDLER_NAME) != null) {
                        channel.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Exception ignored) {
                }
            });
        } else if (released != null) {
            released.clear();
        }

        ghostSet = false;
        lastHealth = -1.0F;
        lastAbsorption = -1.0F;
    }

    private static final class Held {

        private final C03PacketPlayer packet;
        private final long dueNanos;

        private Held(C03PacketPlayer packet, long dueNanos) {
            this.packet = packet;
            this.dueNanos = dueNanos;
        }
    }

    /**
     * Every queue operation runs on the Netty event loop: writes arrive there, the release is
     * scheduled there, and a disable posts its flush there too. Nothing is touched from two
     * threads, so packet order cannot be broken.
     */
    private final class LagHandler extends ChannelDuplexHandler {

        private final ArrayDeque<Held> held = new ArrayDeque<>();
        private ChannelHandlerContext context;
        private boolean drainScheduled;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            this.context = ctx;

            if (msg instanceof S08PacketPlayerPosLook) {
                // The server has moved us. Queued positions predate that correction and would
                // drag us back once they were sent, so they are dropped and the marker follows
                // the teleport.
                held.clear();
                S08PacketPlayerPosLook teleport = (S08PacketPlayerPosLook) msg;
                setServerPosition(teleport.getX(), teleport.getY(), teleport.getZ(), true);
            } else if (msg instanceof S07PacketRespawn) {
                held.clear();
            }

            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            this.context = ctx;

            if (!isEnabled() || !(msg instanceof C03PacketPlayer)) {
                ctx.write(msg, promise);
                return;
            }

            C03PacketPlayer packet = (C03PacketPlayer) msg;
            long delay = delayMillis;
            if (!packet.isMoving() || delay <= 0L) {
                // Look-only packets go straight out; a position that is not held still tells
                // the module where the server is.
                if (packet.isMoving()) setServerPosition(packet.getPositionX(),
                        packet.getPositionY(), packet.getPositionZ(), false);
                ctx.write(msg, promise);
                return;
            }

            held.add(new Held(packet, System.nanoTime() + delay * 1_000_000L));
            promise.setSuccess();
            schedule(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            held.clear();
            drainScheduled = false;
            ctx.fireChannelInactive();
        }

        private void schedule(ChannelHandlerContext ctx) {
            if (drainScheduled || held.isEmpty()) return;
            drainScheduled = true;
            long wait = Math.max(0L, held.peek().dueNanos - System.nanoTime());
            ctx.executor().schedule(() -> drain(ctx), wait, TimeUnit.NANOSECONDS);
        }

        private void drain(ChannelHandlerContext ctx) {
            drainScheduled = false;
            if (!ctx.channel().isActive()) {
                held.clear();
                return;
            }

            long now = System.nanoTime();
            Held next;
            while ((next = held.peek()) != null && now - next.dueNanos >= 0L) {
                held.poll();
                setServerPosition(next.packet.getPositionX(),
                        next.packet.getPositionY(), next.packet.getPositionZ(), false);
                ctx.writeAndFlush(next.packet);
            }
            schedule(ctx);
        }

        /** Sends everything still queued, in order, and forgets it. Runs on the event loop. */
        void flushNow() {
            drainScheduled = false;
            ChannelHandlerContext ctx = context;
            if (ctx == null) {
                held.clear();
                return;
            }
            Held next;
            while ((next = held.poll()) != null) {
                setServerPosition(next.packet.getPositionX(),
                        next.packet.getPositionY(), next.packet.getPositionZ(), false);
                ctx.writeAndFlush(next.packet);
            }
        }

        void clear() {
            held.clear();
            drainScheduled = false;
        }
    }
}
