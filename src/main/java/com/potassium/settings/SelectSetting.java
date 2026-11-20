package com.potassium.settings;

import java.util.LinkedHashSet;
import java.util.Set;

/** A setting that stores any number of selected values from a fixed option list. */
public class SelectSetting extends Setting {

    private final String[] options;
    private final Set<String> selected = new LinkedHashSet<>();

    public SelectSetting(String name, String[] options) {
        super(name);
        this.options = options;
        captureDefault();
    }

    public String[] getOptions() {
        return options;
    }

    public boolean isSelected(String option) {
        return selected.contains(option);
    }

    public void toggle(String option) {
        if (!containsOption(option)) return;
        if (!selected.remove(option)) selected.add(option);
    }

    public int getSelectedCount() {
        return selected.size();
    }

    /** Compact field text; the dropdown itself shows the individual choices. */
    public String getDisplayValue() {
        if (selected.isEmpty()) return "None";
        return selected.size() + " selected";
    }

    private boolean containsOption(String option) {
        for (String candidate : options) {
            if (candidate.equals(option)) return true;
        }
        return false;
    }

    @Override
    public String toConfigString() {
        StringBuilder out = new StringBuilder();
        for (String option : selected) {
            if (out.length() > 0) out.append(',');
            out.append(option);
        }
        return out.toString();
    }

    @Override
    public void fromConfigString(String value) {
        selected.clear();
        if (value == null || value.isEmpty()) return;
        for (String option : value.split(",")) {
            option = option.trim();
            if (containsOption(option)) selected.add(option);
        }
    }
}
