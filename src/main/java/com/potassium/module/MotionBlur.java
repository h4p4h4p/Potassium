package com.potassium.module;

import com.potassium.module.render.motionblur.MotionBlurRenderer;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

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
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * OpenAdjust hooks its patched EntityRenderer. The last world pass leaves the same camera
     * matrices bound, and this event runs between the world and the GUI, so all three blurs can
     * draw here without a mixin into a class OptiFine also transforms.
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!isEnabled()) return;
        renderer.beginFrame();
        renderer.captureMatrices(event.partialTicks);
        renderer.renderMonkey();
        renderer.renderPost();
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
