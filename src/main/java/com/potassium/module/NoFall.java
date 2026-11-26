package com.potassium.module;

import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Prevents fall damage by telling the server the player is on the ground.
 *
 * <p>The server builds fall distance from the movement packets and clears it as soon as one
 * of them carries the on-ground flag, so a fall that reports ground before it ends never
 * reaches the damage check. The client's own motion is untouched: the player still falls,
 * lands, and takes no damage, because only the reported flag is false.</p>
 *
 * <p>Packet rewrites the on-ground flag of the movement packet vanilla is already sending,
 * so it adds no traffic; the rewrite starts only once the fall is long enough to matter.
 * Spoof leaves vanilla's packets alone and queues a separate ground-flagged position packet
 * every tick of the fall, which is slower but survives another module rewriting movement
 * packets after this one.</p>
 */
public class NoFall extends Module {

    public static final String MODE_PACKET = "Packet";
    public static final String MODE_SPOOF = "Spoof";

    private static final String HANDLER_NAME = "potassium_nofall";

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_PACKET, MODE_SPOOF}, 0);
    private final SliderSetting fallDistanceSetting = new SliderSetting("Fall Distance",
            2.5D, 1.0D, 10.0D, 0.5D);

    private final Minecraft mc = Minecraft.getMinecraft();

    private Channel boundChannel;
    private GroundHandler handler;

    public NoFall() {
        super("NoFall", Category.PLAYER);
        settings.add(modeSetting);
        settings.add(fallDistanceSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private String mode() {
        String mode = modeSetting.getValue();
        return mode == null ? MODE_PACKET : mode;
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
            detachHandler();
            return;
        }

        if (MODE_SPOOF.equals(mode())) {
            // No pipeline handler in this mode; every spoofed tick goes out on its own.
            if (shouldSpoof(player)) sendGroundPacket(player);
            return;
        }

        armChannel();
    }

    /**
     * True while the server could already be charging fall damage. Creative flight, liquids,
     * ladders and vehicles all reset or prevent the fall themselves, so the flag is not
     * spoofed there and a normal landing keeps its usual packets.
     */
    private boolean shouldSpoof(EntityPlayerSP player) {
        if (player.isDead || player.getHealth() <= 0.0F) return false;
        if (player.onGround || player.capabilities.isFlying || player.isRiding()) return false;
        if (player.isInWater() || player.isInLava() || player.isOnLadder()) return false;
        return player.fallDistance >= fallDistanceSetting.getValue();
    }

    private void sendGroundPacket(EntityPlayerSP player) {
        if (mc.getNetHandler() == null) return;
        mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(
                player.posX, player.posY, player.posZ, true));
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
        if (channel.pipeline().get(HANDLER_NAME) != null) return;

        final GroundHandler created = new GroundHandler();
        handler = created;
        try {
            channel.eventLoop().execute(() -> {
                try {
                    if (isEnabled() && MODE_PACKET.equals(mode()) && channel.isActive()
                            && channel.pipeline().get(HANDLER_NAME) == null) {
                        channel.pipeline().addBefore("packet_handler", HANDLER_NAME, created);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
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

    @Override
    public void onEnable() {
        handler = null;
    }

    @Override
    public void onDisable() {
        detachHandler();
    }

    /**
     * Flips the on-ground flag on the movement packets that leave while the player is falling
     * far enough to be damaged. The position and rotation the packet carries are kept, so the
     * server sees the same movement it would have seen, just reported as grounded.
     */
    private class GroundHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (msg instanceof C03PacketPlayer) {
                EntityPlayerSP player = mc.thePlayer;
                C03PacketPlayer packet = (C03PacketPlayer) msg;
                if (player != null && !packet.isOnGround() && shouldSpoof(player)) {
                    msg = withOnGround(packet);
                }
            }
            super.write(ctx, msg, promise);
        }

        private C03PacketPlayer withOnGround(C03PacketPlayer packet) {
            if (packet instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
                return new C03PacketPlayer.C06PacketPlayerPosLook(
                        packet.getPositionX(), packet.getPositionY(), packet.getPositionZ(),
                        packet.getYaw(), packet.getPitch(), true);
            }
            if (packet instanceof C03PacketPlayer.C04PacketPlayerPosition) {
                return new C03PacketPlayer.C04PacketPlayerPosition(
                        packet.getPositionX(), packet.getPositionY(), packet.getPositionZ(), true);
            }
            if (packet instanceof C03PacketPlayer.C05PacketPlayerLook) {
                return new C03PacketPlayer.C05PacketPlayerLook(
                        packet.getYaw(), packet.getPitch(), true);
            }
            return new C03PacketPlayer(true);
        }
    }
}
