package com.potassium.module.scaffold;

import net.minecraft.util.MathHelper;

import java.util.concurrent.ThreadLocalRandom;

/**
 * One tick of a turn, OpenAdjust's {@code RotationModel}: given the rotation the aim is on and
 * the rotation the placement was solved at, answer where the aim sits after this tick.
 */
public interface RotationModel {

    float[] apply(float fromYaw, float fromPitch, float toYaw, float toPitch);

    /** The whole turn in one tick, the model Hand and most snap settings end on. */
    RotationModel INSTANT = (fromYaw, fromPitch, toYaw, toPitch) ->
            new float[]{toYaw, toPitch};

    /**
     * OpenAdjust's {@code ScaffoldLinearRotationModel}: the turn is split between the axes and
     * the yaw is capped at {@code speed} degrees a tick, scaled by how much of the whole turn
     * is horizontal. The pitch is never capped, so the aim drops onto the block at once while
     * the body swings around over the following ticks.
     */
    final class Linear implements RotationModel {

        private final double speed;

        public Linear(double speed) {
            this.speed = speed + ThreadLocalRandom.current().nextDouble(0.01D, 0.05D);
        }

        @Override
        public float[] apply(float fromYaw, float fromPitch, float toYaw, float toPitch) {
            float yawDiff = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
            float pitchDiff = toPitch - fromPitch;
            double length = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
            if (length == 0.0D) return new float[]{toYaw, toPitch};

            double maxYaw = Math.abs(yawDiff / length) * speed;
            double yaw = Math.max(-maxYaw, Math.min(maxYaw, yawDiff));
            return new float[]{fromYaw + (float) yaw, fromPitch + pitchDiff};
        }
    }

    /**
     * OpenAdjust's {@code ScaffoldWeightedRotationModel}: a fixed share of the remaining turn,
     * taken from a weight table so a two or three tick flick lands on the target exactly on the
     * last tick instead of overshooting it.
     */
    final class Weighted implements RotationModel {

        private final float factor;

        public Weighted(float[] weights, int remainingTicks) {
            int remaining = Math.max(1, Math.min(weights.length, remainingTicks));
            float sum = 0.0F;
            for (int i = weights.length - remaining; i < weights.length; i++) sum += weights[i];
            this.factor = weights[weights.length - remaining] / sum;
        }

        @Override
        public float[] apply(float fromYaw, float fromPitch, float toYaw, float toPitch) {
            float yawDelta = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
            float pitchDelta = toPitch - fromPitch;
            return new float[]{fromYaw + yawDelta * factor, fromPitch + pitchDelta * factor};
        }
    }

    /**
     * The linear mode with a separate limit per axis, which is what Potassium's scaffold used
     * before the OpenAdjust port: the horizontal and vertical speeds are the axis limits, drawn
     * from their ranges every tick.
     */
    final class Axis implements RotationModel {

        private final double yawLimit;
        private final double pitchLimit;
        private final boolean exponential;

        public Axis(double yawLimit, double pitchLimit, boolean exponential) {
            this.yawLimit = Math.max(0.0D, Math.min(180.0D, yawLimit));
            this.pitchLimit = Math.max(0.0D, Math.min(180.0D, pitchLimit));
            this.exponential = exponential;
        }

        @Override
        public float[] apply(float fromYaw, float fromPitch, float toYaw, float toPitch) {
            float yawDelta = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
            float pitchDelta = toPitch - fromPitch;
            return new float[]{fromYaw + (float) step(yawDelta, yawLimit),
                    fromPitch + (float) step(pitchDelta, pitchLimit)};
        }

        private double step(float delta, double limit) {
            double magnitude = Math.abs(delta);
            if (magnitude < 1.0E-4D || limit <= 0.0D) return 0.0D;
            double fraction = exponential
                    ? 1.0D - Math.exp(-magnitude / limit)
                    : 1.0D;
            return Math.copySign(Math.min(magnitude, limit * Math.min(1.0D, fraction)), delta);
        }
    }
}
