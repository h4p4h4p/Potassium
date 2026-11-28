package com.potassium.hud;

import com.potassium.Potassium;
import com.potassium.gui.Theme;
import com.potassium.module.HUD;
import com.potassium.module.Module;
import com.potassium.util.RenderUtil;

import java.util.Locale;

/** The blocks-per-second counter, toggled by the HUD module's BPS Counter setting. */
public class BpsHudElement extends HudElement {

    private final HudManager manager;

    public BpsHudElement(HudManager manager) {
        this.manager = manager;
        x = 2;
        y = 34;
    }

    @Override
    public String getId() {
        return "bps";
    }

    @Override
    public String getDisplayName() {
        return "BPS Counter";
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
        return hud != null && hud.isEnabled() && hud.shouldShowBps();
    }

    @Override
    public boolean isEnabled() {
        HUD hud = hudModule();
        return hud != null && hud.shouldShowBps();
    }

    @Override
    public void render(int x, int y) {
        String bps = String.format(Locale.ROOT, "%.2f", manager.getBlocksPerSecond());
        RenderUtil.drawGuiStringShadow(bps, x, y, Theme.ACCENT);
        RenderUtil.drawGuiStringShadow(" BPS", x + RenderUtil.getGuiTextWidth(bps), y, Theme.TEXT_GRAY);
    }

    private String label() {
        return String.format(Locale.ROOT, "%.2f", manager.getBlocksPerSecond()) + " BPS";
    }

    private HUD hudModule() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return null;
        Module module = Potassium.INSTANCE.moduleManager.getModule("HUD");
        return module instanceof HUD ? (HUD) module : null;
    }
}
