package com.potassium.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One Netty handler for the whole client, and named listeners on top of it.
 *
 * <p>Modules used to each install their own handler in front of {@code packet_handler}. Every
 * one of them repeated the same connection tracking, the same retry when the pipeline was not
 * ready yet, and the same "is the channel still the one I armed" check, and a module could
 * only see a packet by being in exactly the right place in that stack.</p>
 *
 * <p>Subscriptions are ordered by priority, highest first, and equal priorities keep the order
 * they subscribed in. A listener may replace the packet it was given, or cancel it and deliver
 * it later through {@link #deliver(Packet)}; both hooks run on the channel's event loop, so a
 * held packet can be replayed without racing a live write.</p>
 */
public final class PacketBus {

    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    /** One packet passing through the bus. Valid only for the duration of the callback. */
    public static final class Event {

        private Packet packet;
        private final Direction direction;
        private boolean cancelled;

        private Event(Packet packet, Direction direction) {
            this.packet = packet;
            this.direction = direction;
        }

        public Packet packet() {
            return packet;
        }

        public Direction direction() {
            return direction;
        }

        /** Uses another packet in place of this one. */
        public void replace(Packet replacement) {
            if (replacement != null) packet = replacement;
        }

        /** Consumes the packet: the bus stops it here and the listener owns it from now on. */
        public void cancel() {
            cancelled = true;
        }
    }

    /** Called on the channel's event loop; never touch client state from directly. */
    public interface Listener {
        void onPacket(Event event);
    }

    private static final String HANDLER_NAME = "potassium_packetbus";
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Copied on write: the client thread subscribes while the event loop reads. */
    private static final List<Subscription> SUBSCRIPTIONS = new CopyOnWriteArrayList<>();
    private static final BusHandler HANDLER = new BusHandler();

    private static volatile Channel boundChannel;
    private static boolean watching;

    private PacketBus() {
    }

    /** Starts watching for connections. Call once, from init. */
    public static void init() {
        if (watching) return;
        watching = true;
        MinecraftForge.EVENT_BUS.register(new Watcher());
    }

    /**
     * Adds a listener, replacing any earlier one with the same id.
     *
     * @param priority higher runs first; equal priorities keep subscription order
     */
    public static void subscribe(String id, Direction direction, int priority, Listener listener) {
        if (id == null || direction == null || listener == null) return;
        unsubscribe(id);
        SUBSCRIPTIONS.add(new Subscription(id, direction, priority, listener));
        SUBSCRIPTIONS.sort(Comparator.comparingInt((Subscription s) -> s.priority).reversed());
        installCurrent();
    }

    public static void unsubscribe(String id) {
        if (id == null) return;
        for (int i = SUBSCRIPTIONS.size() - 1; i >= 0; i--) {
            if (id.equals(SUBSCRIPTIONS.get(i).id)) SUBSCRIPTIONS.remove(i);
        }
    }

    /** True while the bus handler is on the live connection. */
    public static boolean isInstalled() {
        Channel channel = boundChannel;
        return channel != null && channel.isActive()
                && channel.pipeline().get(HANDLER_NAME) == HANDLER;
    }

    /**
     * Writes a packet through the normal pipeline. Used when a module has to inject something
     * vanilla did not send; outbound listeners still see it.
     */
    public static void send(final Packet packet) {
        if (packet == null) return;
        final Channel channel = boundChannel;
        if (channel == null || !channel.isActive()) {
            if (mc.getNetHandler() != null) mc.getNetHandler().addToSendQueue(packet);
            return;
        }
        runOnLoop(channel, () -> channel.writeAndFlush(packet));
    }

    /**
     * Hands a packet the bus consumed back to the pipeline, at the bus's position, so it is
     * processed exactly as if it had just arrived. Must be called after the listener decided
     * to release it; it is moved onto the event loop when needed.
     */
    public static void deliver(final Packet packet) {
        if (packet == null) return;
        final Channel channel = boundChannel;
        if (channel == null || !channel.isActive()) return;
        runOnLoop(channel, () -> {
            ChannelHandlerContext ctx = HANDLER.context;
            if (ctx != null) ctx.fireChannelRead(packet);
        });
    }

    private static void runOnLoop(Channel channel, Runnable task) {
        try {
            if (channel.eventLoop().inEventLoop()) task.run();
            else channel.eventLoop().execute(task);
        } catch (Exception ignored) {
        }
    }

    private static void installCurrent() {
        if (mc.getNetHandler() == null) return;
        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        install(manager.channel());
    }

    private static void install(final Channel channel) {
        if (channel == null || !channel.isActive()) return;
        if (channel == boundChannel && isInstalled()) return;
        boundChannel = channel;
        runOnLoop(channel, () -> addNow(channel));
    }

    private static void addNow(Channel channel) {
        try {
            if (!channel.isActive() || channel.pipeline().get("packet_handler") == null) return;
            if (channel.pipeline().get(HANDLER_NAME) == null) {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME, HANDLER);
            }
        } catch (Exception ignored) {
        }
    }

    private static Event dispatch(Packet packet, Direction direction) {
        if (SUBSCRIPTIONS.isEmpty()) return new Event(packet, direction);
        Event event = new Event(packet, direction);
        for (int i = 0; i < SUBSCRIPTIONS.size(); i++) {
            Subscription subscription = SUBSCRIPTIONS.get(i);
            if (subscription.direction != direction) continue;
            try {
                subscription.listener.onPacket(event);
            } catch (Throwable ignored) {
                // A broken listener must not drop the connection.
            }
        }
        return event.cancelled ? null : event;
    }

    private static final class Subscription {

        private final String id;
        private final Direction direction;
        private final int priority;
        private final Listener listener;

        private Subscription(String id, Direction direction, int priority, Listener listener) {
            this.id = id;
            this.direction = direction;
            this.priority = priority;
            this.listener = listener;
        }
    }

    private static final class BusHandler extends ChannelDuplexHandler {

        /** The context the pipeline handed us, used to deliver held packets from here. */
        private volatile ChannelHandlerContext context;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            context = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            context = ctx;
            if (msg instanceof Packet) {
                Event event = dispatch((Packet) msg, Direction.INBOUND);
                if (event == null) return;
                ctx.fireChannelRead(event.packet);
                return;
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            context = ctx;
            if (msg instanceof Packet) {
                Event event = dispatch((Packet) msg, Direction.OUTBOUND);
                if (event == null) {
                    promise.setSuccess();
                    return;
                }
                super.write(ctx, event.packet, promise);
                return;
            }
            super.write(ctx, msg, promise);
        }
    }

    private static final class Watcher {

        @SubscribeEvent
        public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
            // Runs on the channel thread before the join packet, so the handler is in place
            // for the first packets of the connection.
            if (event.manager != null) install(event.manager.channel());
        }

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            installCurrent();
        }
    }
}
