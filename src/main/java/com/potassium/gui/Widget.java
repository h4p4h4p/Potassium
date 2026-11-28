package com.potassium.gui;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ColorSetting;
import com.potassium.settings.KeySetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.RangeSetting;
import com.potassium.settings.SelectSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import com.potassium.settings.StringSetting;

public abstract class Widget {

    protected static final int MARKER = 0xFFF5BE5A;

    protected float x;
    protected float y;
    protected float width;
    protected float height = 13;

    public void setPosition(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public float getHeight() {
        return height;
    }

    public boolean contains(float mx, float my) {
        return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }

    /** The text field inside this widget that is capturing keys right now, if any. */
    public TextInput getCapturingText() {
        return this instanceof TextInput && ((TextInput) this).isTextCapturing() ? (TextInput) this : null;
    }

    /** Builds the row for a setting, so top-level and grouped settings share one mapping. */
    public static Widget forSetting(Setting setting) {
        if (setting instanceof SliderSetting) return new SliderWidget((SliderSetting) setting);
        if (setting instanceof RangeSetting) return new RangeWidget((RangeSetting) setting);
        if (setting instanceof BooleanSetting) return new BooleanWidget((BooleanSetting) setting);
        if (setting instanceof ModeSetting) return new ModeWidget((ModeSetting) setting);
        if (setting instanceof ColorSetting) return new ColorWidget((ColorSetting) setting);
        if (setting instanceof StringSetting) return new StringWidget((StringSetting) setting);
        if (setting instanceof SelectSetting) return new SelectWidget((SelectSetting) setting);
        if (setting instanceof KeySetting) return new KeyWidget((KeySetting) setting);
        return null;
    }

    public abstract void draw(float mx, float my);

    public boolean onClick(float mx, float my, int button) {
        return false;
    }

    public void onDrag(float mx, float my) {
    }

    public float getPreferredWidth() {
        return 100;
    }
}