package com.potassium.hud;

import com.potassium.gui.Theme;
import com.potassium.module.KillAura;
import com.potassium.util.RenderUtil;

/**
 * A small "Blinking" row shown while KillAura holds a Blink block pulse.
 *
 * <p>Normal play only draws it during an active pulse; the chat editor always draws it, faint
 * when no pulse is running, so the row can be picked up and positioned before the first one.</p>
 */
public class BlinkTextHudElement extends HudElement {

    private static final String LABEL = "Blinking";

    public BlinkTextHudElement() {
        x = 2;
        y = 34;
    }

    @Override
    public String getId() {
        return "blinkText";
    }

    @Override
    public String getDisplayName() {
        return "Blink Text";
    }

    @Override
    public int getWidth() {
        return RenderUtil.getGuiTextWidthInt(LABEL);
    }

    @Override
    public int getHeight() {
        return RenderUtil.getGuiTextHeightInt();
    }

    @Override
    public boolean isVisible() {
        KillAura aura = KillAura.instance();
        return aura != null && aura.isEnabled() && aura.isBlinkTextEnabled()
                && aura.isBlinkModeSelected() && aura.isBlinkBlockActive();
    }

    /** Off hides the row from the editor; on previews it even between pulses. */
    @Override
    public boolean isEnabled() {
        KillAura aura = KillAura.instance();
        return aura != null && aura.isBlinkTextEnabled() && aura.isBlinkModeSelected();
    }

    @Override
    public void render(int x, int y) {
        KillAura aura = KillAura.instance();
        boolean active = aura != null && aura.isBlinkBlockActive();
        RenderUtil.drawGuiStringShadow(LABEL, x, y, active ? Theme.ACCENT : Theme.TEXT_FAINT);
    }
}
