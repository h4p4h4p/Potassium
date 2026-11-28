package com.potassium.module;

import com.potassium.module.render.motionblur.MotionBlurRenderer;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;

/**
 * OpenAdjust's {@code MotionBlurModule}: three post-process blurs over the world frame, with
 * the same mode list and strength slider.
 */
public class MotionBlur extends Module {

    public static MotionBlur INSTANCE;

    public final ModeSetting mode;
    public final SliderSetting strength;
    public final MotionBlurRenderer renderer;

    public MotionBlur() {
        super("Motion Blur", Category.RENDER);
        INSTANCE = this;
        mode = new ModeSetting("Mode",
                new String[]{"Phosphor Blur", "Monkey Blur", "Moulberry Blur"}, 0);
        strength = new SliderSetting("Strength", 1, 1, 10, 0.1);
        settings.add(mode);
        settings.add(strength);
        renderer = new MotionBlurRenderer(this);
    }

    @Override
    public void onEnable() {
        renderer.reset();
    }

    @Override
    public void onDisable() {
        renderer.reset();
    }

    public float getStrength() {
        return (float) strength.getValue();
    }
}
