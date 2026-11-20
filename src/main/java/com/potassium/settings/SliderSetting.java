package com.potassium.settings;

public class SliderSetting extends Setting {

    private final double min;
    private final double max;
    private final double step;
    private double value;

    public SliderSetting(String name, double value, double min, double max, double step) {
        super(name);
        this.min = min;
        this.max = max;
        this.step = step;
        setValue(value);
        captureDefault();
    }

    public double getValue() {
        return value;
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

    /** Slider dragging and programmatic defaults stay inside the configured range. */
    public void setValue(double value) {
        this.value = Math.max(min, Math.min(max, value));
    }

    /**
     * Manual text entry can deliberately go outside the slider range. The slider still uses
     * min/max for dragging and display; only the stored value is left exact.
     */
    public void setValueExact(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return;
        this.value = value;
    }

    @Override
    public String toConfigString() {
        return Double.toString(value);
    }

    @Override
    public void fromConfigString(String s) {
        try {
            setValueExact(Double.parseDouble(s));
        } catch (NumberFormatException ignored) {
        }
    }
}