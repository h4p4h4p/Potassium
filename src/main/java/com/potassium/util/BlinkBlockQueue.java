package com.potassium.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;

import java.util.ArrayDeque;

/**
 * Holds outbound movement packets while a Blink block pulse is active.
 *
 * <p>Sword blocking is a server-side item-use flag set by C08 and cleared by C07. The client
 * does not enter vanilla's use state in Blink mode, so the camera and sprint speed never slow
 * down. With the movement packets held, the server also never receives a C03 showing the
 * player moving while that use flag is up, which is what a NoSlow check looks for.</p>
 *
 * <p>Only C03 is held. C07/C08 are sent at the pulse boundaries so the server sees one
 * deliberate block start and one release, then the queued movement is flushed after the
 * release. Blocking and releasing are decided on the client tick; the queue itself is only
 * touched from the channel's event loop.</p>
 */
public final class BlinkBlockQueue {

    private static final String HANDLER_NAME = "potassium_blink_block";

    private static Channel boundChannel;
    private static Handler handler;
    private static volatile boolean holding;
    private static volatile boolean draining;

    private BlinkBlockQueue() {
    }

    /**
     * Starts holding C03. Returns false before a server connection has a usable channel, so a
     * pulse is not started without the movement half of the effect.
     */
    public static boolean beginHold() {
        if (!ensureHandler()) return false;
        holding = true;
        return true;
    }

    /** Stops accepting new packets into the hold; call {@link #flush()} after the C07. */
    public static void stopHolding() {
        holding = false;
        draining = true;
    }

    /** Sends everything held, in order, on the event loop. */
    public static void flush() {
        final Channel channel = boundChannel;
        final Handler h = handler;
        if (channel == null || h == null) {
            draining = false;
            return;
        }
        channel.eventLoop().execute(() -> {
            ChannelHandlerContext context = h.context;
            if (context == null) {
                h.pending.clear();
                draining = false;
                return;
            }
            Packet packet;
            while ((packet = h.pending.poll()) != null) {
                context.write(packet);
            }
            context.flush();
            draining = false;
        });
    }

    private static boolean ensureHandler() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() == null) return false;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return false;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return false;

        if (channel == boundChannel && handler != null) return true;

        final Channel old = boundChannel;
        if (old != null && old.isActive()) {
            old.eventLoop().execute(() -> {
                try {
                    if (old.pipeline().get(HANDLER_NAME) != null) old.pipeline().remove(HANDLER_NAME);
                } catch (Exception ignored) {
                }
            });
        }

        boundChannel = channel;
        if (handler == null) handler = new Handler();
        final Handler h = handler;
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(HANDLER_NAME) == null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME, h);
                }
            } catch (Exception ignored) {
            }
        });
        return true;
    }

    private static final class Handler extends ChannelDuplexHandler {

        private final ArrayDeque<Packet> pending = new ArrayDeque<>();
        private ChannelHandlerContext context;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            context = ctx;
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            pending.clear();
            context = null;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            pending.clear();
            super.channelInactive(ctx);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            context = ctx;
            if ((holding || draining) && msg instanceof C03PacketPlayer) {
                pending.add((Packet) msg);
                promise.setSuccess();
                return;
            }
            ctx.write(msg, promise);
        }
    }
}
