package com.potassium.util;

/**
 * Keeps an attack and an item/block use out of the same client tick. Servers with a
 * MultiActions check flag the pair even when the clickers fire independently.
 */
public final class ClickState {

    private static int attackTick = -1;
    private static int useTick = -1;

    private ClickState() {
    }

    public static boolean canAttack(int tick) {
        return useTick != tick;
    }

    public static boolean canUse(int tick) {
        return attackTick != tick;
    }

    public static void markAttack(int tick) {
        attackTick = tick;
    }

    public static void markUse(int tick) {
        useTick = tick;
    }
}
