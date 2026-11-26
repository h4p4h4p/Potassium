package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Decides which module owns this tick's silent rotation.
 *
 * <p>{@link SilentLook} carries exactly one rotation per tick, but several modules can ask
 * for it: KillAura aims at a target, Scaffold aims at the block it is about to place, and
 * both ease their aim back to the camera when they have nothing to do. Before this class the
 * caller whose tick handler ran last won, which depended on module registration order and
 * changed when a module was toggled. A module could also send a click for a rotation that
 * never reached the server, which is the ghost-block shape: the client places, the server
 * never sees the look that justified it, and the two sides disagree about the world.
 *
 * <p>Claims are ranked. A combat aim outranks a placement aim, a placement aim outranks a
 * held pose such as Scaffold's Face Backward, and easing back to the camera yields to all of
 * them. Equal ranks keep the first claim of the tick, so the answer never depends on handler
 * order. A module that loses the claim is told so and can hold its click back instead of
 * acting on a rotation the server never received.
 *
 * <p>The tick is read from the player's own tick counter, so nothing here has to subscribe to
 * an event or worry about where in the tick a module asks.
 */
public final class CombatArbiter {

    /** Easing an aim back to the camera; every real aim outranks it. */
    public static final int PRIORITY_RETURN = 10;
    /** A pose held for its own sake, such as Scaffold's Face Backward. */
    public static final int PRIORITY_POSE = 40;
    /** An aim a block placement depends on. */
    public static final int PRIORITY_MOVEMENT = 60;
    /** An aim a hit depends on. */
    public static final int PRIORITY_COMBAT = 100;

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static int rotationTick = Integer.MIN_VALUE;
    private static String rotationOwner;
    private static int rotationPriority = Integer.MIN_VALUE;

    private CombatArbiter() {
    }

    /**
     * Claims this tick's silent rotation for a module.
     *
     * @return true when this module's aim is the one the tick will carry
     */
    public static boolean claimRotation(String source, int priority) {
        if (source == null) return false;
        int tick = currentTick();
        if (tick != rotationTick) {
            rotationTick = tick;
            rotationOwner = null;
            rotationPriority = Integer.MIN_VALUE;
        }
        if (rotationOwner == null) {
            rotationOwner = source;
            rotationPriority = priority;
            return true;
        }
        // The owner asking again keeps the tick without re-ranking itself: a module may ease
        // its aim at return priority in the same tick it already aimed at a target.
        if (source.equals(rotationOwner)) return true;
        if (priority > rotationPriority) {
            rotationOwner = source;
            rotationPriority = priority;
            return true;
        }
        return false;
    }

    /** Whether this module owns the tick's rotation right now. */
    public static boolean ownsRotation(String source) {
        return source != null && source.equals(rotationOwner) && rotationTick == currentTick();
    }

    /** The module that owns the tick's rotation, or null before the first claim. */
    public static String rotationOwner() {
        return rotationTick == currentTick() ? rotationOwner : null;
    }

    private static int currentTick() {
        EntityPlayerSP player = mc.thePlayer;
        return player == null ? -1 : player.ticksExisted;
    }
}
