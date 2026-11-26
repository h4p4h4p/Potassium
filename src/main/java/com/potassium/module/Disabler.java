package com.potassium.module;

import com.potassium.Potassium;
import com.potassium.settings.BooleanSetting;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Hides the client's sprint state from the server.
 *
 * <p>The server only learns that the player is sprinting from the START_SPRINTING and
 * STOP_SPRINTING entity actions; movement packets never carry it. Dropping those actions
 * leaves the player sprinting locally (FOV, input speed) while the server keeps treating
 * them as walking.</p>
 *
 * <p>That is exactly what a movement prediction check measures, so the suppression is
 * skipped while the Speed module's Vulcan mode is running: Vulcan replays each tick from
 * the previous motion and the sprint boost the server has been told about. With the sprint
 * actions hidden the server predicts walking speed for a client that moves at sprint
 * speed, and every sprinting tick fails Speed (Prediction).</p>
 */
public class Disabler extends Module {

    private static final String HANDLER_NAME = "potassium_disabler";

    private final BooleanSetting noSprintPacketSetting =
            new BooleanSetting("No Sprint Packet", true);
    private final Minecraft mc = Minecraft.getMinecraft();

    private DisablerHandler handler;
    private Channel boundChannel;

    public Disabler() {
        super("Disabler", Category.EXPLOITS);
        settings.add(noSprintPacketSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!hidesSprint()) return;
        if (mc.thePlayer == null || event.player != mc.thePlayer) return;
        armChannel();
    }

    /**
     * True while the sprint actions should be dropped. Vulcan's Speed check is a pure
     * prediction, so it is skipped for as long as the Speed module's Vulcan mode drives
     * the player; hiding sprint there only makes the movement and the server's state
     * disagree.
     */
    private boolean hidesSprint() {
        if (!isEnabled() || !noSprintPacketSetting.getValue()) return false;
        if (Potassium.INSTANCE == null) return false;
        Module speed = Potassium.INSTANCE.moduleManager.getModule("Speed");
        return !(speed instanceof Speed && ((Speed) speed).isVulcanMode());
    }

    private static boolean isSprintAction(C0BPacketEntityAction packet) {
        C0BPacketEntityAction.Action action = packet.getAction();
        return action == C0BPacketEntityAction.Action.START_SPRINTING
                || action == C0BPacketEntityAction.Action.STOP_SPRINTING;
    }

    private void armChannel() {
        if (mc.getNetHandler() == null) return;

        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;

        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (boundChannel != channel) {
            removeHandler(boundChannel);
            boundChannel = channel;
            handler = null;
        }
        if (handler == null) {
            handler = new DisablerHandler();
        }
        if (channel.pipeline().get(HANDLER_NAME) == null) {
            final DisablerHandler created = handler;
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
        removeHandler(channel);
    }

    private void removeHandler(Channel channel) {
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
        if (hidesSprint()) {
            armChannel();
        }
    }

    @Override
    public void onDisable() {
        detachHandler();
    }

    private class DisablerHandler extends ChannelDuplexHandler {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (msg instanceof C0BPacketEntityAction
                    && hidesSprint()
                    && isSprintAction((C0BPacketEntityAction) msg)) {
                return;
            }
            ctx.write(msg, promise);
        }
    }
}
