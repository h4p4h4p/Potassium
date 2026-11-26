package com.potassium.util;

import net.minecraft.util.MathHelper;

import java.util.Random;

/**
 * One tick of aim, generated the way a hand generates one.
 *
 * <p>A rotation check does not only care where an aim points; it reads the sequence. A view
 * that jumps from still to a target inside one packet, repeats the same step tick after tick,
 * draws a perfectly straight line onto a box, or never moves its pitch at all is not a shape a
 * mouse produces. Vulcan's rotation checks and MX's heuristic suite look for exactly those
 * shapes: a large acceleration out of a still view, repeated deltas, identical delta angles,
 * an entropy value that does not change between windows.</p>
 *
 * <p>The aim is therefore a flick. A turn ramps up over the first ticks, every step varies
 * around the ramp, full speed occasionally drops into a short correction, and once the point
 * is reached the aim holds on it with a one-count tremor instead of freezing. Every step is a
 * whole number of mouse counts measured from the rotation already sent, so each delta the
 * server sees is one the current sensitivity can produce, and the tremor never moves the aim
 * more than a count off the point.</p>
 */
public final class RotationSmoother {

    /** How far the tremor may pull the aim off the point, in mouse counts. */
    private static final int TREMOR_COUNTS = 1;

    private final Random random = new Random();

    /** OpenAdjust's Organic model, rebuilt whenever the Legit settings change. */
    private OrganicRotation organic;
    private double organicSpeed;
    private double organicDrift;
    private double organicJitter;

    private float yaw;
    private float pitch;
    private boolean started;

    /** Ramp position of the current flick, per axis: 0 starts, 2 is full speed. */
    private int yawRamp;
    private int pitchRamp;
    private int yawSign;
    private int pitchSign;

    /** Ticks each axis has spent holding on the point, used to pace its tremor. */
    private int yawSettledTicks;
    private int pitchSettledTicks;
    private int yawTremorWait = 4;
    private int pitchTremorWait = 5;
    private int tremorYaw;
    private int tremorPitch;

    /**
     * Supplies the Legit settings. The model keeps its own drift phase while they are unchanged,
     * so moving a slider is the only thing that starts a new hand motion.
     */
    public void setOrganic(double speed, double drift, double jitter) {
        if (organic != null && organicSpeed == speed && organicDrift == drift
                && organicJitter == jitter) {
            return;
        }
        organicSpeed = speed;
        organicDrift = drift;
        organicJitter = jitter;
        organic = new OrganicRotation(speed, drift, jitter);
    }

    /** Starts at a rotation without turning toward it. */
    public void reset(float yaw, float pitch) {
        this.yaw = MathHelper.wrapAngleTo180_float(yaw);
        this.pitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        this.started = true;
        this.yawRamp = 0;
        this.pitchRamp = 0;
        this.yawSign = 0;
        this.pitchSign = 0;
        this.yawSettledTicks = 0;
        this.pitchSettledTicks = 0;
        this.tremorYaw = 0;
        this.tremorPitch = 0;
        this.yawTremorWait = 3 + random.nextInt(4);
        this.pitchTremorWait = 3 + random.nextInt(4);
    }

    /**
     * One tick's turn toward the target. Set once per tick by the module that aims. Every step
     * is rounded onto the mouse grid and limited to the axis's degrees per tick, with a flick
     * ramp, so a turn is drawn out over several ticks instead of arriving in one.
     */
    public void tick(float targetYaw, float targetPitch, double yawStep, double pitchStep,
                     String smoothing) {
        if (!started) {
            reset(targetYaw, targetPitch);
            return;
        }

        double step = RotationUtil.mouseStep();
        targetYaw = MathHelper.wrapAngleTo180_float(targetYaw);
        targetPitch = MathHelper.clamp_float(targetPitch, -90.0F, 90.0F);

        // Legit is OpenAdjust's Organic model: its own speed limit, sine drift and random
        // jitter, so the ramp and settled tremor below are not used on top of it.
        if (RotationUtil.SMOOTHING_LEGIT.equals(smoothing)) {
            if (organic == null) setOrganic(60.0D, 1.2D, 0.12D);
            float[] next = organic.apply(yaw, pitch, targetYaw, targetPitch, 1.0F);
            yaw = MathHelper.wrapAngleTo180_float(next[0]);
            pitch = MathHelper.clamp_float(next[1], -90.0F, 90.0F);
            return;
        }

        // A real mouse is never perfectly still. Each axis that has reached its point moves one
        // count every few ticks and comes back, so an aim that is tracking a target on one axis
        // still moves its other axis and the deltas never settle into a constant angle. Pace
        // the axes separately: a chase on yaw must not freeze the pitch.
        float holdYaw = MathHelper.wrapAngleTo180_float(targetYaw - yaw);
        float holdPitch = targetPitch - pitch;
        if (atPoint(holdYaw, step)) {
            if (++yawSettledTicks >= yawTremorWait) {
                yawSettledTicks = 0;
                yawTremorWait = 3 + random.nextInt(4);
                tremorYaw = random.nextInt(2 * TREMOR_COUNTS + 1) - TREMOR_COUNTS;
            }
        } else {
            yawSettledTicks = 0;
            tremorYaw = 0;
        }
        if (atPoint(holdPitch, step)) {
            if (++pitchSettledTicks >= pitchTremorWait) {
                pitchSettledTicks = 0;
                pitchTremorWait = 3 + random.nextInt(4);
                tremorPitch = random.nextInt(2 * TREMOR_COUNTS + 1) - TREMOR_COUNTS;
                if (tremorPitch == 0) {
                    tremorPitch = random.nextBoolean() ? TREMOR_COUNTS : -TREMOR_COUNTS;
                }
            }
        } else {
            pitchSettledTicks = 0;
            tremorPitch = 0;
        }

        if (step > 0.0D) {
            targetYaw = MathHelper.wrapAngleTo180_float(targetYaw + tremorYaw * (float) step);
            targetPitch = MathHelper.clamp_float(targetPitch + tremorPitch * (float) step,
                    -90.0F, 90.0F);
        }

        yaw = MathHelper.wrapAngleTo180_float(yaw + (float) turn(
                MathHelper.wrapAngleTo180_float(targetYaw - yaw), yawStep, step, true, smoothing));
        pitch = MathHelper.clamp_float(pitch + (float) turn(
                targetPitch - pitch, pitchStep, step, false, smoothing), -90.0F, 90.0F);
    }

    /**
     * A tick of the flick, in degrees. The first ticks of a turn use a fraction of the allowed
     * step, full speed varies inside the ramp, and every value is a whole number of mouse
     * counts. A remainder under one count is left for the tremor rather than flipped back and
     * forth, so a settled aim does not produce a one-count oscillation every tick.
     */
    private double turn(float delta, double budget, double step, boolean yawAxis,
                        String smoothing) {
        double magnitude = Math.abs(delta);
        if (budget <= 0.0D || magnitude < 1.0E-4D) return 0.0D;

        int sign = delta > 0.0F ? 1 : -1;
        int ramp = yawAxis ? yawRamp : pitchRamp;
        int lastSign = yawAxis ? yawSign : pitchSign;
        if (sign != lastSign) {
            ramp = 0;
            if (yawAxis) yawSign = sign;
            else pitchSign = sign;
        }

        double limit = budget;
        double rampFactor = ramp == 0 ? 0.45D : ramp == 1 ? 0.75D : 1.0D;
        double varied = 0.72D + random.nextDouble() * 0.28D;
        if (ramp >= 2 && random.nextInt(16) == 0) varied *= 0.4D;
        if (yawAxis) yawRamp = Math.min(2, ramp + 1);
        else pitchRamp = Math.min(2, ramp + 1);

        if (step < 1.0E-4D) {
            // No sensitivity to round with; the ramp alone keeps the turn from being one step.
            return Math.copySign(Math.min(magnitude, limit * rampFactor * varied), delta);
        }

        double remaining = magnitude / step;
        double allowed = Math.max(1.0D, limit * rampFactor * varied / step);
        if (RotationUtil.SMOOTHING_EXPONENTIAL.equals(smoothing)) {
            double budgetCounts = Math.max(1.0D, limit / step);
            allowed *= 1.0D - Math.exp(-remaining / budgetCounts);
        }

        long counts = Math.round(Math.min(remaining, Math.max(1.0D, allowed)));
        if (counts < 1L) return 0.0D;
        // A hand does not step exactly one count for twenty ticks in a row. A small step is
        // sometimes skipped, which keeps a slow chase from repeating the same delta. The aim
        // catches up on the next tick because the remaining angle keeps growing.
        if (counts <= 2L && random.nextInt(4) == 0) return 0.0D;
        return Math.copySign(counts * step, delta);
    }

    /** True when an axis is within the tremor band of its point, in mouse counts. */
    private static boolean atPoint(float delta, double step) {
        return step > 0.0D
                ? Math.abs(delta) <= step * (TREMOR_COUNTS + 1)
                : Math.abs(delta) <= 0.75F;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }
}
