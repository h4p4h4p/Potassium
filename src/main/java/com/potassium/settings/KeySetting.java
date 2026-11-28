package com.potassium.settings;

import org.lwjgl.input.Keyboard;

/** A single key a module reads directly, such as Scaffold's tower key. */
public class KeySetting extends Setting {

    /** 0 means unbound, which for these keys means "fall back to the vanilla key". */
    public static final int NONE = 0;

    private int value;

    public KeySetting(String name, int value) {
        super(name);
        this.value = value;
        captureDefault();
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public boolean isBound() {
        return value > 0 && value < 256;
    }

    public boolean isDown() {
        return isBound() && Keyboard.isKeyDown(value);
    }

    public String keyName() {
        if (!isBound()) return "None";
        String name = Keyboard.getKeyName(value);
        return name == null || name.isEmpty() ? "Key " + value : name;
    }

    @Override
    public String toConfigString() {
        return Integer.toString(value);
    }

    @Override
    public void fromConfigString(String s) {
        try {
            value = Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            value = NONE;
        }
        if (value < 0 || value > 255) value = NONE;
    }
}
