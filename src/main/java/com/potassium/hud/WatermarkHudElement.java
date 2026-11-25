package com.potassium.hud;

import com.potassium.Potassium;
import com.potassium.gui.Theme;
import com.potassium.module.HUD;
import com.potassium.module.Module;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;

/** The client watermark, optionally replaced by the HUD's custom text. */
public class WatermarkHudElement extends HudElement {

    private final Minecraft mc = Minecraft.getMinecraft();

    public WatermarkHudElement() {
        x = 2;
        y = 2;
    }

    @Override
    public String getId() {
        return "watermark";
    }

    @Override
    public String getDisplayName() {
        return "Watermark";
    }

    @Override
    public int getWidth() {
        HUD hud = hudModule();
        String custom = hud == null ? "" : hud.getWatermarkText();
        if (!custom.isEmpty()) return RenderUtil.getGuiTextWidthInt(custom);
        return RenderUtil.getGuiTextWidthInt("[" + Potassium.NAME + " v" + Potassium.VERSION
                + " - " + playerName() + "]");
    }

    @Override
    public int getHeight() {
        return RenderUtil.getGuiTextHeightInt();
    }

    @Override
    public boolean isVisible() {
        HUD hud = hudModule();
        return hud != null && hud.isEnabled() && hud.shouldShowWatermark();
    }

    @Override
    public boolean isEnabled() {
        HUD hud = hudModule();
        return hud != null && hud.shouldShowWatermark();
    }

    @Override
    public void render(int x, int y) {
        HUD hud = hudModule();
        String custom = hud == null ? "" : hud.getWatermarkText();
        if (!custom.isEmpty()) {
            RenderUtil.drawGuiStringShadow(custom, x, y, Theme.ACCENT);
            return;
        }

        String prefix = "[";
        String suffix = " v" + Potassium.VERSION + " - " + playerName() + "]";
        RenderUtil.drawGuiStringShadow(prefix, x, y, 0xFFFFFFFF);
        float cursor = x + RenderUtil.getGuiTextWidth(prefix);
        RenderUtil.drawGuiStringShadow(Potassium.NAME, cursor, y, Theme.ACCENT);
        cursor += RenderUtil.getGuiTextWidth(Potassium.NAME);
        RenderUtil.drawGuiStringShadow(suffix, cursor, y, 0xFFFFFFFF);
    }

    private HUD hudModule() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return null;
        Module module = Potassium.INSTANCE.moduleManager.getModule("HUD");
        return module instanceof HUD ? (HUD) module : null;
    }

    private String playerName() {
        return mc.thePlayer != null ? mc.thePlayer.getName() : mc.getSession().getUsername();
    }
}
