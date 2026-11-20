package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;

/**
 * Keeps the world-space movement direction unchanged when a module turns the view.
 *
 * Vanilla builds the movement vector from the input and the current yaw inside
 * EntityPlayerSP#moveFlying, so a rotation applied earlier in the same tick silently
 * redirects the player mid-move. While sprinting that reads to a server as a sudden change
 * of travel direction and is a common source of Speed flags. Rotating the input by the yaw
 * delta cancels the redirect, so the player keeps walking exactly where the keys point.
 */
public final class MovementFix {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Below this the yaw change cannot move the player far enough to matter. */
    private static final float MIN_YAW_DELTA = 0.01F;

    private MovementFix() {
    }

    /**
     * @param previousYaw the player's yaw before the module wrote a new one
     */
    public static void apply(float previousYaw) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || player.movementInput == null) return;

        float delta = MathHelper.wrapAngleTo180_float(previousYaw - player.rotationYaw);
        if (Math.abs(delta) < MIN_YAW_DELTA) return;

        float strafe = player.movementInput.moveStrafe;
        float forward = player.movementInput.moveForward;
        if (strafe == 0.0F && forward == 0.0F) return;

        float sin = MathHelper.sin(delta * (float) Math.PI / 180.0F);
        float cos = MathHelper.cos(delta * (float) Math.PI / 180.0F);

        float rotatedStrafe = strafe * cos + forward * sin;
        float rotatedForward = forward * cos - strafe * sin;

        player.movementInput.moveStrafe =
                Math.abs(rotatedStrafe) < 1.0E-4F ? 0.0F : rotatedStrafe;
        player.movementInput.moveForward =
                Math.abs(rotatedForward) < 1.0E-4F ? 0.0F : rotatedForward;
    }
}
