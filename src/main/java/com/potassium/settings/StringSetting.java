package com.potassium.settings;

public class StringSetting extends Setting {

    private String value;
    private String placeholder;

    public StringSetting(String name, String value) {
        super(name);
        this.value = value;
        captureDefault();
    }

    /** Faint hint drawn in the text field while the value is empty; never saved. */
    public StringSetting withPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        return this;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public String toConfigString() {
        return value == null ? "" : value;
    }

    @Override
    public void fromConfigString(String s) {
        value = s;
    }
}