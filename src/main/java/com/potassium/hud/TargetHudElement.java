package com.potassium.hud;

import com.potassium.module.TargetHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/**
 * The draggable anchor for TargetHUD.
 *
 * <p>The TargetHUD module draws the panel itself in normal play, so this element reports
 * itself as not visible and the regular HUD pass never draws it twice. Chat editing does not
 * check visibility, only {@link #isEnabled()}, so it still appears on the edit canvas with a
 * placeholder when there is no target to show. Until it is dragged it sits where the panel
 * used to: centred above the hotbar.</p>
 */
public class TargetHudElement extends HudElement {

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean positioned;

    @Override
    public String getId() {
        return "targetHud";
    }

    @Override
    public String getDisplayName() {
        return "TargetHUD";
    }

    @Override
    public int getWidth() {
        TargetHud module = TargetHud.instance();
        return module == null ? (int) TargetHud.WIDTH : (int) module.getScaledWidth();
    }

    @Override
    public int getHeight() {
        TargetHud module = TargetHud.instance();
        return module == null ? 46 : (int) module.getScaledHeight();
    }

    /** The module renders it; this element only carries the position and edit hitbox. */
    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        TargetHud module = TargetHud.instance();
        return module != null && module.isEnabled();
    }

    @Override
    public int getX() {
        if (positioned) return x;
        ScaledResolution sr = new ScaledResolution(mc);
        return sr.getScaledWidth() / 2 - getWidth() / 2;
    }

    @Override
    public int getY() {
        if (positioned) return y;
        ScaledResolution sr = new ScaledResolution(mc);
        return sr.getScaledHeight() - 96;
    }

    @Override
    public void setX(int x) {
        this.x = x;
        positioned = true;
    }

    @Override
    public void setY(int y) {
        this.y = y;
        positioned = true;
    }

    @Override
    public boolean hasStoredPosition() {
        return positioned;
    }

    @Override
    public int getStoredX() {
        return getX();
    }

    @Override
    public int getStoredY() {
        return getY();
    }

    @Override
    public void render(int x, int y) {
        TargetHud module = TargetHud.instance();
        if (module != null) module.renderAt(x, y);
    }
}
