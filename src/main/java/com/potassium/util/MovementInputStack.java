package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MovementInput;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The one place that writes {@code player.movementInput}.
 *
 * Modules used to wrap the input themselves, each delegating to whatever happened to be
 * installed when it ran. Two wrappers therefore nested in enable order, and an uninstall in
 * the wrong order could restore a stale delegate. Here every wrapper is a layer with a
 * fixed priority, and the chain is rebuilt from the vanilla input whenever a layer is added
 * or removed, so the composition is the same no matter which module acted first.
 *
 * Lower priorities sit closer to vanilla. The player update samples the keys once through
 * the outermost wrapper, and every layer copies the delegate's fields after it runs.
 */
public final class MovementInputStack {

    /** Silent key rotation, closest to vanilla so later scaling still applies to it. */
    public static final int PRIORITY_MOVEMENT_FIX = 100;
    /** NoSlowdown's item-use pre-scale, above the rotation. */
    public static final int PRIORITY_NO_SLOW = 200;
    /** Scaffold's sneak speed, outermost: the final walk speed is the configured one. */
    public static final int PRIORITY_SNEAK_SPEED = 300;
    /** Freecam's frozen input, above everything: no key may reach the body while it flies. */
    public static final int PRIORITY_FREECAM = 400;

    /** Builds one layer around the input beneath it. Called again on every rebuild. */
    public interface Wrapper {
        MovementInput wrap(MovementInput inner);
    }

    private static final class Layer {
        private final String source;
        private final int priority;
        private final Wrapper wrapper;

        private Layer(String source, int priority, Wrapper wrapper) {
            this.source = source;
            this.priority = priority;
            this.wrapper = wrapper;
        }
    }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final List<Layer> layers = new ArrayList<>();

    /** The vanilla input the chain is built on. */
    private static MovementInput base;
    /** The player the base belongs to, so a new world never reuses an old input. */
    private static EntityPlayerSP basePlayer;
    /** The chain currently installed, used to recognise a layer left over from a skipped tick. */
    private static MovementInput installedChain;
    private static boolean initialized;

    private MovementInputStack() {
    }

    /** Makes sure a layer left behind by an interrupted tick never survives into the next one. */
    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(new TickHandler());
    }

    private static final class TickHandler {
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.START || layers.isEmpty()) return;
            clear();
        }
    }

    public static void install(String source, int priority, Wrapper wrapper) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || wrapper == null || player.movementInput == null) return;

        MovementInput current = player.movementInput;
        if (base == null || basePlayer != player) {
            base = current;
            basePlayer = player;
            layers.clear();
            installedChain = null;
        } else if (current == installedChain) {
            // A layer survived a tick that never unwound it. The saved base is still the truth.
            layers.clear();
        } else if (layers.isEmpty()) {
            base = current;
            installedChain = null;
        }

        remove(source);
        layers.add(new Layer(source, priority, wrapper));
        rebuild(player);
    }

    public static void uninstall(String source) {
        if (!remove(source)) return;

        EntityPlayerSP player = mc.thePlayer;
        if (layers.isEmpty()) {
            if (base != null && player != null && player.movementInput == installedChain) {
                player.movementInput = base;
            }
            installedChain = null;
            return;
        }
        if (player != null && player == basePlayer) rebuild(player);
    }

    /** Drops every layer and puts the vanilla input back. */
    public static void clear() {
        layers.clear();
        EntityPlayerSP player = mc.thePlayer;
        if (base != null && player != null && player.movementInput == installedChain) {
            player.movementInput = base;
        }
        installedChain = null;
    }

    private static boolean remove(String source) {
        boolean removed = false;
        for (Iterator<Layer> it = layers.iterator(); it.hasNext(); ) {
            if (it.next().source.equals(source)) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    private static void rebuild(EntityPlayerSP player) {
        if (base == null) return;

        List<Layer> ordered = new ArrayList<>(layers);
        ordered.sort((a, b) -> {
            int byPriority = Integer.compare(a.priority, b.priority);
            return byPriority != 0 ? byPriority : a.source.compareTo(b.source);
        });

        MovementInput result = base;
        for (Layer layer : ordered) {
            result = layer.wrapper.wrap(result);
        }

        if (result == base) {
            player.movementInput = base;
            installedChain = null;
            return;
        }

        // Seed the one-tick flags EntityPlayerSP reads before it samples the keys again.
        result.moveForward = base.moveForward;
        result.moveStrafe = base.moveStrafe;
        result.jump = base.jump;
        result.sneak = base.sneak;

        player.movementInput = result;
        installedChain = result;
    }
}
