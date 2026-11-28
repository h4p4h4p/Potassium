package com.potassium.gui;

import com.potassium.Potassium;
import com.potassium.settings.KeySetting;
import com.potassium.util.RenderUtil;
import org.lwjgl.input.Keyboard;

/** The clickable key row for a {@link KeySetting}. */
public class KeyWidget extends Widget {

    /** The row listening for a key press, if any; the click GUI forwards keys here. */
    private static KeyWidget capturing;

    private final KeySetting setting;
    private boolean listening;

    public KeyWidget(KeySetting setting) {
        this.setting = setting;
    }

    public static KeyWidget capturing() {
        return capturing;
    }

    @Override
    public void draw(float mx, float my) {
        RenderUtil.drawGuiString(setting.getName(), x + 6, RenderUtil.guiTextY(y, height),
                Theme.TEXT_GRAY);

        String value;
        int color;
        if (listening) {
            boolean blink = (System.currentTimeMillis() / 450L) % 2 == 0;
            value = "Listening...";
            color = blink ? Theme.ACCENT : Theme.TEXT_GRAY;
        } else {
            value = setting.keyName();
            color = setting.isBound() ? Theme.ACCENT : Theme.TEXT_GRAY;
        }

        float vw = RenderUtil.getGuiTextWidth(value);
        RenderUtil.drawGuiString(value, x + width - 6 - vw, RenderUtil.guiTextY(y, height), color);
    }

    @Override
    public boolean onClick(float mx, float my, int button) {
        if (button != 0) return false;
        startListening();
        return true;
    }

    private void startListening() {
        if (capturing != null) capturing.stopListening();
        capturing = this;
        listening = true;
    }

    private void stopListening() {
        listening = false;
        if (capturing == this) capturing = null;
    }

    public void setKey(int key) {
        setting.setValue(key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_DELETE ? KeySetting.NONE : key);
        stopListening();
        Potassium.scheduleSave();
    }

    @Override
    public float getPreferredWidth() {
        String longest = "Listening...";
        if (RenderUtil.getGuiTextWidth(setting.keyName()) > RenderUtil.getGuiTextWidth(longest)) {
            longest = setting.keyName();
        }
        return Math.max(110, RenderUtil.getGuiTextWidth(setting.getName())
                + RenderUtil.getGuiTextWidth(longest) + 24);
    }
}
