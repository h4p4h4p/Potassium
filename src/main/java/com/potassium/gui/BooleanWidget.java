package com.potassium.gui;

import com.potassium.Potassium;
import com.potassium.settings.BooleanSetting;
import com.potassium.util.RenderUtil;

public class BooleanWidget extends Widget {

    private final BooleanSetting setting;

    public BooleanWidget(BooleanSetting setting) {
        this.setting = setting;
    }

    @Override
    public void draw(float mx, float my) {
        RenderUtil.drawGuiString(setting.getName(), x + 6, RenderUtil.guiTextY(y, height), Theme.TEXT_GRAY);

        float sw = 18;
        float sh = 7;
        float sx = x + width - sw - 6;
        float sy = y + height / 2f - sh / 2f;

        boolean enabled = setting.getValue();
        RenderUtil.drawRoundedRect(sx, sy, sw, sh, 2.5f, enabled ? Theme.ACCENT : Theme.TOGGLE_OFF);

        float dot = 5;
        float dx = sx + (enabled ? sw - dot - 1 : 1);
        RenderUtil.drawRoundedRect(dx, sy + 1, dot, sh - 2, 2f, Theme.KNOB);
    }

    @Override
    public float getPreferredWidth() {
        return Math.max(110, RenderUtil.getGuiTextWidth(setting.getName()) + 6 + 18 + 6 + 6);
    }

    @Override
    public boolean onClick(float mx, float my, int button) {
        if (button != 0) return false;
        setting.toggle();
        Potassium.scheduleSave();
        return true;
    }
}