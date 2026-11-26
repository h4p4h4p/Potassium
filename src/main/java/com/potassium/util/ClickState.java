package com.potassium.util;

/**
 * Keeps one action per client tick: an attack and an item/block use never share a tick, and
 * two modules never both attack in the same tick. Servers with MultiActions or attack-rate
 * checks flag either pair even when the clicks come from independent modules.
 */
public final class ClickState {

    private static int attackTick = -1;
    private static int useTick = -1;

    private ClickState() {
    }

    public static boolean canAttack(int tick) {
        return useTick != tick && attackTick != tick;
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
