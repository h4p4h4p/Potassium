package com.potassium.settings;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Two-ended numeric range, the "0..0" slider pair LiquidBounce uses for delays. Both ends stay
 * inside min..max and ordered, so dragging one past the other stops it at the other instead of
 * storing an inverted range.
 */
public class RangeSetting extends Setting {

    private final double min;
    private final double max;
    private final double step;
    private double low;
    private double high;

    public RangeSetting(String name, double low, double high, double min, double max, double step) {
        super(name);
        this.min = min;
        this.max = max;
        this.step = step;
        this.low = clamp(low);
        this.high = clamp(high);
        if (this.low > this.high) {
            double swap = this.low;
            this.low = this.high;
            this.high = swap;
        }
        captureDefault();
    }

    public double getLow() {
        return low;
    }

    public double getHigh() {
        return high;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getStep() {
        return step;
    }

    public void setLow(double value) {
        low = Math.min(clamp(value), high);
    }

    public void setHigh(double value) {
        high = Math.max(clamp(value), low);
    }

    /**
     * Sets both ends at once from a typed value like "3-8". The order the pair is clamped in
     * matters: going through setLow and setHigh would clamp the first end against a stale
     * second one, so a typed "60-120" could collapse against a stored 0-0.
     */
    public void setRange(double low, double high) {
        double first = clamp(low);
        double second = clamp(high);
        this.low = Math.min(first, second);
        this.high = Math.max(first, second);
    }

    /**
     * A value between the two ends, the per-update draw LiquidBounce's float and int ranges
     * make: a speed set to 100-130 varies every tick instead of sitting on one number.
     */
    public double random() {
        if (low >= high) return low;
        return ThreadLocalRandom.current().nextDouble(low, high);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toConfigString() {
        return low + "-" + high;
    }

    @Override
    public void fromConfigString(String s) {
        // Split on the separator, not on a sign inside an exponent. Values here are never
        // negative, but config text is still parsed defensively.
        int split = -1;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' && s.charAt(i - 1) != 'e' && s.charAt(i - 1) != 'E') {
                split = i;
                break;
            }
        }
        if (split < 0) return;
        try {
            double parsedLow = Double.parseDouble(s.substring(0, split));
            double parsedHigh = Double.parseDouble(s.substring(split + 1));
            low = clamp(parsedLow);
            high = clamp(parsedHigh);
            if (low > high) {
                double swap = low;
                low = high;
                high = swap;
            }
        } catch (NumberFormatException ignored) {
        }
    }
}
