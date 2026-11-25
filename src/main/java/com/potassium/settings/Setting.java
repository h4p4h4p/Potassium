package com.potassium.settings;

public abstract class Setting {

    private final String name;
    private String defaultConfig = "";
    private String group;

    protected Setting(String name) {
        this.name = name;
    }

    /** Call at the end of the subclass constructor so right-click reset has something to restore. */
    protected void captureDefault() {
        defaultConfig = toConfigString();
    }

    public boolean isDefault() {
        return defaultConfig.equals(toConfigString());
    }

    public String getName() {
        return name;
    }

    /**
     * Title of the dropdown this setting belongs under, or null for a top-level row. Settings
     * sharing a title and declared next to each other are collected into one dropdown.
     */
    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public abstract String toConfigString();

    public abstract void fromConfigString(String value);
}
