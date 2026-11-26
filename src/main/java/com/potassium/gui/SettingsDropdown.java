package com.potassium.gui;

import com.potassium.settings.Setting;
import com.potassium.util.RenderUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collapsible group of settings inside a module, the value group LiquidBounce's click GUI uses.
 * The header row toggles the group; the nested rows stay real widgets, so sliders can still be
 * dragged and typed into without every panel having to know about grouped settings.
 */
public class SettingsDropdown extends Widget {

    private static final float ROW = 13f;
    private static final float INDENT = 6f;
    private static final float CARET_W = 4f;
    private static final float CARET_H = 3f;

    /** Open groups survive the widget rebuild that a search or a mode change triggers. */
    private static final Set<String> EXPANDED = new HashSet<>();

    private final String key;
    private final String title;
    private final List<Setting> settings;
    private final List<Widget> children = new ArrayList<>();

    private boolean expanded;
    private boolean forcedExpanded;

    public SettingsDropdown(String moduleName, String title, List<Setting> settings) {
        this.key = moduleName + "\u0000" + title;
        this.title = title;
        this.settings = new ArrayList<>(settings);
        this.expanded = EXPANDED.contains(key);
        for (Setting setting : this.settings) {
            Widget widget = forSetting(setting);
            if (widget != null) children.add(widget);
        }
    }

    /** Filtered search results stay open, without overwriting the group's own state. */
    public void setForcedExpanded(boolean forcedExpanded) {
        this.forcedExpanded = forcedExpanded;
    }

    private boolean isExpanded() {
        return expanded || forcedExpanded;
    }

    @Override
    public float getHeight() {
        return ROW + (isExpanded() ? childrenHeight() : 0f);
    }

    private float childrenHeight() {
        float h = 0f;
        for (Widget child : children) h += child.getHeight();
        return h;
    }

    @Override
    public void draw(float mx, float my) {
        boolean hovered = mx >= x && mx <= x + width && my >= y && my <= y + ROW;
        if (hovered) RenderUtil.drawRect(x, y, width, ROW, Theme.HOVER);

        RenderUtil.drawGuiString(title, x + 6, y + (ROW - RenderUtil.getGuiTextHeight()) / 2f,
                hovered ? Theme.TEXT : Theme.TEXT_DIM);

        float caretX = x + width - 10;
        float caretY = y + ROW / 2f - CARET_H / 2f;
        int caretColor = hovered ? Theme.ACCENT : Theme.TEXT_GRAY;
        if (isExpanded()) {
            RenderUtil.drawRect(caretX, caretY + 2, CARET_W, 1, caretColor);
        } else {
            RenderUtil.drawRect(caretX, caretY, CARET_W, 1, caretColor);
            RenderUtil.drawRect(caretX + 1, caretY + 1, CARET_W - 2, 1, caretColor);
        }

        if (!isExpanded()) return;

        // Guide down the nested rows so it reads as one group rather than free-floating settings.
        RenderUtil.drawRect(x + 3, y + ROW, 1, childrenHeight(), Theme.SEPARATOR);

        float wy = y + ROW;
        for (Widget child : children) {
            child.setPosition(x + INDENT, wy, width - INDENT, child.getHeight());
            child.draw(mx, my);
            wy += child.getHeight();
        }
    }

    @Override
    public boolean onClick(float mx, float my, int button) {
        if (mx < x || mx > x + width || my < y || my > y + getHeight()) return false;

        if (my <= y + ROW) {
            if (button != 0) return false;
            if (!forcedExpanded) {
                expanded = !expanded;
                if (expanded) EXPANDED.add(key);
                else EXPANDED.remove(key);
            }
            return true;
        }

        if (!isExpanded()) return false;
        for (Widget child : children) {
            if (child.contains(mx, my)) return child.onClick(mx, my, button);
        }
        return true;
    }

    @Override
    public TextInput getCapturingText() {
        for (Widget child : children) {
            TextInput input = child.getCapturingText();
            if (input != null) return input;
        }
        return null;
    }

    @Override
    public float getPreferredWidth() {
        float w = RenderUtil.getGuiTextWidth(title) + 26;
        for (Widget child : children) {
            w = Math.max(w, child.getPreferredWidth() + INDENT);
        }
        return w;
    }
}
