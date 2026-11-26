package com.potassium.util;

import net.minecraft.util.MathHelper;

import java.util.Random;

/**
 * OpenAdjust's Organic rotation model, imported as Potassium's Legit smoothing.
 *
 * <p>Each tick turns toward the target no faster than the model's angle speed, weighted by how
 * much of the turn each axis needs. Two sine waves per axis, at random frequencies and phases,
 * add a slow drift, and a random jitter rides on top; the result is rounded onto the mouse grid
 * measured from the rotation already held, so every step is one the current sensitivity can
 * produce. Drift is not removed when the aim is close: a target inside the drift radius is
 * followed directly, which is what keeps a settled aim alive instead of frozen.</p>
 */
public final class OrganicRotation {

    private final Random random = new Random();

    private final double speed;
    private final double drift;
    private final double jitter;

    private final double yawFrequency1;
    private final double yawFrequency2;
    private final double pitchFrequency1;
    private final double pitchFrequency2;
    private final double yawPhase1;
    private final double yawPhase2;
    private final double pitchPhase1;
    private final double pitchPhase2;

    private double time;

    public OrganicRotation(double speed, double drift, double jitter) {
        this.speed = speed;
        this.drift = drift;
        this.jitter = jitter;

        yawFrequency1 = random.nextDouble() * 0.3D + 0.1D;
        yawFrequency2 = random.nextDouble() * 0.5D + 0.5D;
        pitchFrequency1 = random.nextDouble() * 0.3D + 0.1D;
        pitchFrequency2 = random.nextDouble() * 0.5D + 0.5D;
        yawPhase1 = random.nextDouble() * Math.PI * 2.0D;
        yawPhase2 = random.nextDouble() * Math.PI * 2.0D;
        pitchPhase1 = random.nextDouble() * Math.PI * 2.0D;
        pitchPhase2 = random.nextDouble() * Math.PI * 2.0D;
    }

    /**
     * One tick of the model, following OpenAdjust's {@code apply(from, to, factor)} with a
     * factor of one tick.
     */
    public float[] apply(float fromYaw, float fromPitch, float toYaw, float toPitch, float factor) {
        float dx = MathHelper.wrapAngleTo180_float(toYaw - fromYaw) * factor;
        float dy = (toPitch - fromPitch) * factor;
        double distance = Math.sqrt(dx * dx + dy * dy);
        // Already inside the drift radius: follow the target exactly and let the hand's own
        // motion come from the drift below rather than from a distance-limited turn.
        if (drift > distance) {
            return new float[]{fromYaw + dx,
                    MathHelper.clamp_float(fromPitch + dy, -90.0F, 90.0F)};
        }
        if (distance > 0.0D) {
            double yawLimit = Math.abs(dx) / distance * speed * factor;
            double pitchLimit = Math.abs(dy) / distance * speed * factor;
            dx = (float) clamp(dx, -yawLimit, yawLimit);
            dy = (float) clamp(dy, -pitchLimit, pitchLimit);
        }

        time += factor;

        double yawWave = Math.sin(time * yawFrequency1 + yawPhase1);
        double yawWeight = 0.45D + random.nextDouble() * 0.1D;
        double pitchWave = Math.sin(time * pitchFrequency1 + pitchPhase1);
        double yawSecondary = Math.sin(time * yawFrequency2 + yawPhase2);
        yawWave += yawWeight * yawSecondary;

        double pitchWeight = 0.45D + random.nextDouble() * 0.1D;
        double pitchSecondary = Math.sin(time * pitchFrequency2 + pitchPhase2);
        pitchWave += pitchWeight * pitchSecondary;

        float yawDrift = (float) (yawWave * drift * factor);
        float pitchDrift = (float) (pitchWave * drift * factor);
        float yawJitter = (float) ((random.nextDouble() * 2.0D - 1.0D) * jitter * factor);
        float pitchJitter = (float) ((random.nextDouble() * 2.0D - 1.0D) * jitter * factor);

        float yaw = fromYaw + dx + yawDrift + yawJitter;
        float pitch = fromPitch + dy + pitchDrift + pitchJitter;
        return RotationUtil.snapToMouse(yaw, pitch, fromYaw, fromPitch);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
