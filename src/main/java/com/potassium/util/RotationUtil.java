package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class RotationUtil {

    public static float[] getRotations(Vec3 target) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return new float[]{0, 0};

        double diffX = target.xCoord - mc.thePlayer.posX;
        double diffY = target.yCoord - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double diffZ = target.zCoord - mc.thePlayer.posZ;

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

    /**
     * Interpolates a rotation toward a target at a time-based speed. {@code interpolation}
     * is a percentage where 0 applies the target immediately and higher values turn more
     * slowly. The speed is expressed in degrees per second so the result stays smooth at
     * any frame rate, and the step is capped by the actual time since the previous update.
     */
    public static float[] interpolate(float currentYaw, float currentPitch,
                                      float targetYaw, float targetPitch,
                                      double interpolation, double deltaSeconds) {
        float yawDelta = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDelta = targetPitch - currentPitch;

        double clamped = Math.max(0.0D, Math.min(95.0D, interpolation));
        if (clamped <= 0.0D) {
            return new float[]{currentYaw + yawDelta,
                    MathHelper.clamp_float(currentPitch + pitchDelta, -90.0F, 90.0F)};
        }

        double smoothing = clamped / 95.0D;
        double degreesPerSecond = 1080.0D - smoothing * 1020.0D;
        double frameTime = Math.max(0.001D, Math.min(0.1D, deltaSeconds));
        float maxStep = (float) (degreesPerSecond * frameTime);

        float yaw = currentYaw + MathHelper.clamp_float(yawDelta, -maxStep, maxStep);
        float pitch = MathHelper.clamp_float(
                currentPitch + MathHelper.clamp_float(pitchDelta, -maxStep, maxStep), -90.0F, 90.0F);
        return new float[]{yaw, pitch};
    }

    /**
     * One frame of a smooth aim toward a target. The speed settings scale the remaining
     * angle and Interpolation then caps the step, so both parts of the curve apply: 30 on a
     * speed is a full turn toward the target, and lower values approach it more slowly.
     *
     * AimAssist and KillAura both step through this, so the same settings turn the view at
     * the same rate in either module instead of each applying its own blend.
     */
    public static float[] step(float baseYaw, float basePitch,
                               float targetYaw, float targetPitch,
                               double yawSpeed, double pitchSpeed,
                               double interpolation, double deltaSeconds) {
        float yawFactor = (float) (yawSpeed / 30.0D);
        float pitchFactor = (float) (pitchSpeed / 30.0D);

        float desiredYaw = baseYaw
                + MathHelper.wrapAngleTo180_float(targetYaw - baseYaw) * yawFactor;
        float desiredPitch = MathHelper.clamp_float(
                basePitch + (targetPitch - basePitch) * pitchFactor, -90.0F, 90.0F);

        return interpolate(baseYaw, basePitch, desiredYaw, desiredPitch, interpolation, deltaSeconds);
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