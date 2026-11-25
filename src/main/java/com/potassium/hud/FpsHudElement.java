package com.potassium.hud;

import com.potassium.Potassium;
import com.potassium.gui.Theme;
import com.potassium.module.HUD;
import com.potassium.module.Module;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;

/** The FPS counter, toggled by the HUD module's FPS Counter setting. */
public class FpsHudElement extends HudElement {

    public FpsHudElement() {
        x = 2;
        y = 24;
    }

    @Override
    public String getId() {
        return "fps";
    }

    @Override
    public String getDisplayName() {
        return "FPS Counter";
    }

    @Override
    public int getWidth() {
        return RenderUtil.getGuiTextWidthInt(label());
    }

    @Override
    public int getHeight() {
        return RenderUtil.getGuiTextHeightInt();
    }

    @Override
    public boolean isVisible() {
        HUD hud = hudModule();
        return hud != null && hud.isEnabled() && hud.shouldShowFps();
    }

    @Override
    public boolean isEnabled() {
        HUD hud = hudModule();
        return hud != null && hud.shouldShowFps();
    }

    @Override
    public void render(int x, int y) {
        String fps = Integer.toString(Minecraft.getDebugFPS());
        RenderUtil.drawGuiStringShadow(fps, x, y, Theme.ACCENT);
        RenderUtil.drawGuiStringShadow(" FPS", x + RenderUtil.getGuiTextWidth(fps), y, Theme.TEXT_GRAY);
    }

    private String label() {
        return Minecraft.getDebugFPS() + " FPS";
    }

    private HUD hudModule() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return null;
        Module module = Potassium.INSTANCE.moduleManager.getModule("HUD");
        return module instanceof HUD ? (HUD) module : null;
    }
}
