package com.potassium.util;

import net.minecraft.entity.player.EntityPlayer;

public class PlayerUtil {

    public static boolean isMoving(EntityPlayer player) {
        return player.moveForward != 0 || player.moveStrafing != 0;
    }

    public static void strafe(EntityPlayer player, double speed) {
        strafe(player, speed, 1.0D);
    }

    /** Blends the current horizontal direction toward the input direction by strength. */
    public static void strafe(EntityPlayer player, double speed, double strength) {
        boolean forward = player.moveForward > 0;
        boolean backward = player.moveForward < 0;
        boolean right = player.moveStrafing > 0;
        boolean left = player.moveStrafing < 0;

        float yaw = player.rotationYaw;

        if (forward) {
            if (left) yaw += 45;
            else if (right) yaw -= 45;
        } else if (backward) {
            yaw += 180;
            if (left) yaw -= 45;
            else if (right) yaw += 45;
        } else {
            if (left) yaw += 90;
            else if (right) yaw -= 90;
        }

        if (forward || backward || left || right) {
            double rad = Math.toRadians(yaw);
            double usedSpeed = speed * strength;
            player.motionX = player.motionX * (1.0D - strength) - Math.sin(rad) * usedSpeed;
            player.motionZ = player.motionZ * (1.0D - strength) + Math.cos(rad) * usedSpeed;
        }
    }
}