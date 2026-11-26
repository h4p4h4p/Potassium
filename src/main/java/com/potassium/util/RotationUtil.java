package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class RotationUtil {

    public static float[] getRotations(Vec3 target) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return new float[]{0, 0};

        Vec3 eyes = new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
        return getRotations(target, eyes);
    }

    /** Rotations from a supplied eye position, for a camera that moves between ticks. */
    public static float[] getRotations(Vec3 target, Vec3 eyes) {
        if (target == null || eyes == null) return new float[]{0, 0};

        double diffX = target.xCoord - eyes.xCoord;
        double diffY = target.yCoord - eyes.yCoord;
        double diffZ = target.zCoord - eyes.zCoord;

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(diffY, diffXZ));

        yaw = MathHelper.wrapAngleTo180_float(yaw);
        pitch = MathHelper.clamp_float(pitch, -90.0f, 90.0f);

        return new float[]{yaw, pitch};
    }

    public static float[] getRotations(double x, double y, double z) {
        return getRotations(new Vec3(x, y, z));
    }

    public static final String SMOOTHING_LINEAR = "Linear";
    public static final String SMOOTHING_EXPONENTIAL = "Exponential";

    /**
     * Degrees a speed setting allows in one tick, the unit the game itself moves in. 180 is a
     * whole half turn, which puts the aim on the target in the tick it was chosen; below that
     * the turn is deliberately drawn out over several ticks. LiquidBounce's rotation speeds
     * live on the same 0..180 scale.
     */
    public static double speed(double degrees) {
        if (Double.isNaN(degrees) || Double.isInfinite(degrees)) return 0.0D;
        return Math.max(0.0D, Math.min(180.0D, degrees));
    }

    /**
     * One tick's turn under a smoothing curve, never longer than the axis limit. Linear takes
     * the whole step; Exponential moves a fraction of it that grows with the remaining angle,
     * so the turn decays toward the target.
     */
    public static double curveStep(float delta, double maxStep, String smoothing) {
        double magnitude = Math.abs(delta);
        if (magnitude < 1.0E-4D || maxStep <= 0.0D) return 0.0D;

        double fraction = 1.0D;
        if (SMOOTHING_EXPONENTIAL.equals(smoothing)) {
            fraction = 1.0D - Math.exp(-magnitude / maxStep);
        }
        return Math.copySign(Math.min(magnitude, maxStep * Math.min(1.0D, fraction)), delta);
    }

    /** One tick's movement toward a target, no further than the allowed step. */
    public static float limitStep(float delta, double maxStep) {
        if (maxStep <= 0.0D) return 0.0F;
        if (Math.abs(delta) <= maxStep) return delta;
        return (float) (delta > 0.0D ? maxStep : -maxStep);
    }

    /**
     * Degrees the view turns per mouse count at the current sensitivity. Vanilla turns the
     * mouse delta by {@code f^3 * 8} and then scales that by 0.15 in setAngles, so every
     * look a real mouse can produce is a whole multiple of this value.
     */
    public static double mouseStep() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings == null) return 0.0D;
        float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        return (double) f * f * f * 1.2D;
    }

    /**
     * Snaps a rotation to the steps a mouse produces at the current sensitivity. Server
     * rotation checks compare every look delta against that step, so an aim that moves in
     * fractional degrees reads as synthetic. Raven applies the same snap to every rotation
     * it sends, which is why its aim passes without rotation flags.
     */
    public static float[] snapToMouse(float yaw, float pitch, float lastYaw, float lastPitch) {
        double step = mouseStep();
        if (step < 1.0E-4D) return new float[]{yaw, pitch};

        double yawDelta = Math.round((yaw - lastYaw) / step) * step;
        double pitchDelta = Math.round((pitch - lastPitch) / step) * step;
        return new float[]{(float) (lastYaw + yawDelta),
                MathHelper.clamp_float((float) (lastPitch + pitchDelta), -90.0F, 90.0F)};
    }

    public static float getYawChange(float targetYaw) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return 0;
        float yaw = MathHelper.wrapAngleTo180_float(targetYaw - mc.thePlayer.rotationYaw);
        return yaw;
    }

    public static float getPitchChange(float targetPitch) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return 0;
        return targetPitch - mc.thePlayer.rotationPitch;
    }
}
