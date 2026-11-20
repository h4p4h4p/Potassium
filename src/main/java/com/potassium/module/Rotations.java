package com.potassium.module;

import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.SilentLook;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Shows where the server thinks the player is aiming, without touching the camera.
 *
 * Modules such as Scaffold and KillAura in Silent mode only write their aim into outgoing
 * C03 look packets, so the camera stays where the player is looking while the server is told
 * something else. This module reads those packets out of the Netty pipeline and, for the
 * duration of one player model render, swaps the character's head and body angles onto the
 * rotation the server was given. The player's own rotation is restored before the frame ends,
 * so the camera, movement and everything sent to the server stay untouched.
 *
 * The rotation is dropped after Reset Ticks without a new silent packet, matching the way a
 * module stops aiming: the model then goes back to following the real camera.
 */
public class Rotations extends Module {

    private static final String HANDLER_NAME = "potassium_rotations";

    /** Any rotation further than this from the client's own is treated as a module aim. */
    private static final float ROTATION_EPSILON = 0.5F;

    public static final String PART_HEAD = "Head";
    public static final String PART_BODY = "Body";
    public static final String PART_BOTH = "Both";

    private final ModeSetting bodyPartSetting =
            new ModeSetting("Body Part", new String[]{PART_HEAD, PART_BODY, PART_BOTH}, 2);
    private final SliderSetting resetTicksSetting =
            new SliderSetting("Reset Ticks", 5, 1, 20, 1);

    private final Minecraft mc = Minecraft.getMinecraft();

    private Channel boundChannel;
    private SilentRotationHandler handler;

    private volatile float serverYaw;
    private volatile float serverPitch;
    private volatile boolean pending;

    private boolean active;
    private int ticksSinceAim;

    private float savedYawHead;
    private float savedPrevYawHead;
    private float savedRenderYawOffset;
    private float savedPrevRenderYawOffset;
    private float savedPitch;
    private float savedPrevPitch;
    private boolean overriding;

    public Rotations() {
        super("Rotations", Category.RENDER);
        settings.add(bodyPartSetting);
        settings.add(resetTicksSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            if (handler != null || boundChannel != null) removeHandler();
            reset();
            return;
        }

        armChannel();

        if (pending) {
            pending = false;
            active = true;
            ticksSinceAim = 0;
            return;
        }
        if (!active) return;

        // A module that stopped aiming stops sending looks, and the server rotation goes back
        // to the real one; keep the model from staying on a stale aim.
        if (++ticksSinceAim > (int) Math.round(resetTicksSetting.getValue())) reset();
    }

    /** Renders the player model on the server aim, then puts the real angles back. */
    @SubscribeEvent
    public void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (!isEnabled() || !active) return;
        EntityPlayer player = event.entityPlayer;
        if (player != mc.thePlayer) return;

        savedYawHead = player.rotationYawHead;
        savedPrevYawHead = player.prevRotationYawHead;
        savedRenderYawOffset = player.renderYawOffset;
        savedPrevRenderYawOffset = player.prevRenderYawOffset;
        savedPitch = player.rotationPitch;
        savedPrevPitch = player.prevRotationPitch;
        overriding = true;

        float yaw = serverYaw;
        float pitch = serverPitch;

        if (!PART_BODY.equals(bodyPartSetting.getValue())) {
            player.rotationYawHead = yaw;
            player.prevRotationYawHead = yaw;
            player.rotationPitch = pitch;
            player.prevRotationPitch = pitch;
        }
        if (!PART_HEAD.equals(bodyPartSetting.getValue())) {
            player.renderYawOffset = yaw;
            player.prevRenderYawOffset = yaw;
        }
    }

    @SubscribeEvent
    public void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (!overriding) return;
        EntityPlayer player = event.entityPlayer;
        if (player != mc.thePlayer) return;

        overriding = false;
        player.rotationYawHead = savedYawHead;
        player.prevRotationYawHead = savedPrevYawHead;
        player.renderYawOffset = savedRenderYawOffset;
        player.prevRenderYawOffset = savedPrevRenderYawOffset;
        player.rotationPitch = savedPitch;
        player.prevRotationPitch = savedPrevPitch;
    }

    /** Looks for look packets whose rotation the client is not actually using. */
    private class SilentRotationHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (msg instanceof C03PacketPlayer.C05PacketPlayerLook
                    || msg instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
                record((C03PacketPlayer) msg);
            }
            super.write(ctx, msg, promise);
        }

        private void record(C03PacketPlayer packet) {
            EntityPlayerSP player = mc.thePlayer;
            if (player == null) return;

            float yaw = packet.getYaw();
            float pitch = packet.getPitch();
            // A silent aim is on the player's rotation at the moment the packet is built, so
            // it has to be compared against the camera, not against the live rotation.
            boolean yawDiffers = Math.abs(MathHelper.wrapAngleTo180_float(
                    yaw - SilentLook.cameraYaw())) > ROTATION_EPSILON;
            boolean pitchDiffers = Math.abs(pitch - SilentLook.cameraPitch()) > ROTATION_EPSILON;
            if (!yawDiffers && !pitchDiffers) return;

            serverYaw = yaw;
            serverPitch = pitch;
            pending = true;
        }
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
        if (handler != null) return;

        final SilentRotationHandler created = new SilentRotationHandler();
        handler = created;
        try {
            channel.eventLoop().execute(() -> {
                try {
                    if (isEnabled() && channel.isActive()
                            && channel.pipeline().get(HANDLER_NAME) == null) {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, created);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void removeHandler() {
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

    private void reset() {
        pending = false;
        active = false;
        ticksSinceAim = 0;
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
        removeHandler();
    }
}
