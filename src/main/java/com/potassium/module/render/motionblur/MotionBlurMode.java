package com.potassium.module.render.motionblur;

/** The three blur implementations OpenAdjust ships. */
public enum MotionBlurMode {

    PHOSPHOR("Phosphor Blur"),
    MONKEY("Monkey Blur"),
    MOULBERRY("Moulberry Blur");

    private final String displayName;

    MotionBlurMode(String displayName) {
        this.displayName = displayName;
    }

    public String getName() {
        return displayName;
    }

    public static MotionBlurMode byName(String name) {
        for (MotionBlurMode mode : values()) {
            if (mode.displayName.equals(name)) return mode;
        }
        return PHOSPHOR;
    }
}
