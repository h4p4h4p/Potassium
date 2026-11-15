package com.kalca.ragejava.module;

import com.kalca.ragejava.settings.ModeSetting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.potion.Potion;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Lands critical hits without the player having to set them up by hand.
 *
 * Jump attacks with a real jump so the following swings land while falling.
 * Packet queues a short fake fall just before the attack reaches the server.
 * Air reports onGround=false in every movement packet, so the server treats the player
 * as airborne for as long as the module is enabled.
 *
 * Jump and Packet run on the client thread from AttackEntityEvent, which Forge fires at the
 * start of attackTargetEntityWithCurrentItem - before vanilla queues the C02 attack packet.
 * Air has to touch outgoing movement, so it filters the Netty pipeline.
 */
public class Criticals extends Module {

    public static final String MODE_JUMP = "Jump";
    public static final String MODE_PACKET = "Packet";
    public static final String MODE_AIR = "Air";

    private static final String HANDLER_NAME = "ragejava_criticals";

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_JUMP, MODE_PACKET, MODE_AIR}, 1);

    private final Minecraft mc = Minecraft.getMinecraft();

    private Channel boundChannel;
    private AirHandler handler;
    private boolean airSeeded;

    public Criticals() {
        super("Criticals", Category.COMBAT);
        settings.add(modeSetting);
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
    public void onAttack(AttackEntityEvent event) {
        if (!isEnabled()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return;
        // Singleplayer posts this for the integrated server's player too; only touch ours.
        if (event.entityPlayer != player) return;
        if (event.target == player || !(event.target instanceof EntityLivingBase)) return;
        if (!canLandCrit(player)) return;

        if (MODE_JUMP.equals(mode())) {
            player.jump();
        } else if (MODE_PACKET.equals(mode())) {
            sendFallPackets(player);
            // The server sees the fake fall, but the client still thinks it is grounded.
            player.onCriticalHit(event.target);
        }
    }

    /** Vanilla's own conditions for a critical hit, minus the falling state we create. */
    private boolean canLandCrit(EntityPlayerSP player) {
        return player.onGround
                && !player.isInWater()
                && !player.isInLava()
                && !player.isOnLadder()
                && !player.isRiding()
                && !player.isPotionActive(Potion.blindness);
    }

    /**
     * The 1.8.9 Packet sequence: rise a sixteenth of a block, drop back to the feet, then a
     * negligible rise. The server records the fall, so the attack queued right after these
     * packets is a critical hit while the player never leaves the ground.
     */
    private void sendFallPackets(EntityPlayerSP player) {
        if (mc.getNetHandler() == null) return;
        double x = player.posX;
        double y = player.posY;
        double z = player.posZ;

        mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 0.0625D, z, true));
        mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
        mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(x, y + 1.1E-5D, z, false));
        mc.getNetHandler().addToSendQueue(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        EntityPlayerSP player = mc.thePlayer;
        if (!isEnabled() || !MODE_AIR.equals(mode())
                || player == null || mc.theWorld == null) {
            airSeeded = false;
            if (handler != null || boundChannel != null) removeHandler();
            return;
        }

        // The onGround spoof already keeps fall distance from resetting; this one-time fake
        // fall gives the server some to start with when the mode is switched on while standing.
        if (!airSeeded) {
            if (player.onGround) sendFallPackets(player);
            airSeeded = true;
        }
        armChannel();
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

        final AirHandler created = new AirHandler();
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

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        airSeeded = false;
        removeHandler();
    }

    /**
     * Reports the player as airborne in every movement packet while they are really on the
     * ground. The server builds fall distance from that and accepts the next attack as a crit.
     */
    private class AirHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (msg instanceof C03PacketPlayer) {
                EntityPlayerSP player = mc.thePlayer;
                if (player != null && player.onGround && !player.isRiding()) {
                    // onGround is protected, so rebuild the packet with the flag flipped while
                    // keeping the position and rotation it was carrying.
                    msg = withOnGround((C03PacketPlayer) msg);
                }
            }
            super.write(ctx, msg, promise);
        }

        private C03PacketPlayer withOnGround(C03PacketPlayer packet) {
            if (packet instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
                return new C03PacketPlayer.C06PacketPlayerPosLook(
                        packet.getPositionX(), packet.getPositionY(), packet.getPositionZ(),
                        packet.getYaw(), packet.getPitch(), false);
            }
            if (packet instanceof C03PacketPlayer.C04PacketPlayerPosition) {
                return new C03PacketPlayer.C04PacketPlayerPosition(
                        packet.getPositionX(), packet.getPositionY(), packet.getPositionZ(), false);
            }
            if (packet instanceof C03PacketPlayer.C05PacketPlayerLook) {
                return new C03PacketPlayer.C05PacketPlayerLook(
                        packet.getYaw(), packet.getPitch(), false);
            }
            return new C03PacketPlayer(false);
        }
    }
}
