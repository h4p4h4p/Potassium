package com.potassium.gui;

import com.potassium.Potassium;
import com.potassium.settings.SelectSetting;
import com.potassium.util.RenderUtil;

/**
 * Multi-select dropdown. The list is drawn after every panel, the same way the mode
 * dropdown is, so it is not covered by the settings rows below it.
 */
public class SelectWidget extends Widget {

    private static final float ROW = 12f;
    private static final float CARET_W = 4f;
    private static final float CARET_H = 3f;
    private static final float FIELD_PAD = 4f;
    private static final int MAX_ROWS = 8;

    private static SelectWidget open;

    private final SelectSetting setting;
    private int firstVisible;
    private float bottomLimit = Float.MAX_VALUE;

    public SelectWidget(SelectSetting setting) {
        this.setting = setting;
    }

    /** Only one dropdown can be open at a time, across all panels. */
    public static void closeOpen() {
        open = null;
    }

    public static boolean isOpen() {
        return open != null;
    }

    public static boolean handleOpenPopupClick(float mx, float my, int button) {
        if (open == null) return false;
        return open.listClick(mx, my, button);
    }

    public static boolean handleOpenPopupScroll(float mx, float my, int wheel) {
        if (open == null) return false;
        if (!open.insideList(mx, my)) return false;
        open.scroll(wheel);
        return true;
    }

    public static void drawOpenPopup(float mx, float my, float bottomLimit) {
        if (open == null) return;
        open.drawList(mx, my, bottomLimit);
    }

    @Override
    public void draw(float mx, float my) {
        boolean hovered = contains(mx, my) || (open == this && insideList(mx, my));
        RenderUtil.drawString(setting.getName(), x + 6, y + 4, hovered ? Theme.TEXT : Theme.TEXT_GRAY);
        drawModifiedMarker(!setting.isDefault(), setting.getName());

        float fieldX = x + width - 6 - fieldW();
        RenderUtil.drawRect(fieldX, y + 1, fieldW(), height - 2, open == this ? 0x40219609 : 0x26000000);

        String value = setting.getDisplayValue();
        int valueColor = setting.getSelectedCount() == 0 ? Theme.TEXT_GRAY
                : (hovered ? Theme.ACCENT : Theme.TEXT);
        RenderUtil.drawString(value, fieldX + FIELD_PAD, y + 4, valueColor);

        float caretX = fieldX + fieldW() - FIELD_PAD - CARET_W;
        float caretY = y + height / 2f - CARET_H / 2f;
        int caretColor = hovered ? Theme.ACCENT : Theme.TEXT_GRAY;
        if (open == this) {
            RenderUtil.drawRect(caretX, caretY + 2, CARET_W, 1, caretColor);
        } else {
            RenderUtil.drawRect(caretX, caretY, CARET_W, 1, caretColor);
            RenderUtil.drawRect(caretX + 1, caretY + 1, CARET_W - 2, 1, caretColor);
        }
    }

    private float fieldW() {
        return Math.max(38f, RenderUtil.getTextWidth(setting.getDisplayValue())
                + 10 + CARET_W + FIELD_PAD);
    }

    private int visibleRows() {
        return Math.min(setting.getOptions().length, MAX_ROWS);
    }

    private int maxFirstVisible() {
        return Math.max(0, setting.getOptions().length - visibleRows());
    }

    private float listW() {
        float widest = 0;
        for (String option : setting.getOptions()) {
            widest = Math.max(widest, RenderUtil.getTextWidth(option));
        }
        return Math.min(230f, Math.max(fieldW(), widest + 20));
    }

    private float listH() {
        return visibleRows() * ROW;
    }

    private float listX() {
        return x + width - 6 - listW();
    }

    /** Opens upward when the list would run off the bottom of the screen. */
    private float listY() {
        float below = y + height;
        float ly = below + listH() <= bottomLimit ? below : y - listH();
        return Math.max(4f, ly);
    }

    private boolean insideList(float mx, float my) {
        return mx >= listX() && mx <= listX() + listW()
                && my >= listY() && my <= listY() + listH();
    }

    private void scroll(int wheel) {
        if (wheel == 0) return;
        firstVisible += wheel > 0 ? -1 : 1;
        firstVisible = Math.max(0, Math.min(maxFirstVisible(), firstVisible));
    }

    private void drawList(float mx, float my, float bottomLimit) {
        this.bottomLimit = bottomLimit;

        float lx = listX();
        float ly = listY();
        float lw = listW();
        float lh = listH();
        String[] options = setting.getOptions();

        RenderUtil.drawRect(lx - 1, ly - 1, lw + 2, lh + 2, 0xFF07070A);
        RenderUtil.drawRect(lx, ly, lw, lh, 0xFF1B1D22);

        int rows = visibleRows();
        for (int row = 0; row < rows; row++) {
            int index = firstVisible + row;
            if (index >= options.length) break;

            float rowY = ly + row * ROW;
            boolean selected = setting.isSelected(options[index]);
            boolean hovered = mx >= lx && mx <= lx + lw && my >= rowY && my <= rowY + ROW;
            if (hovered) RenderUtil.drawRect(lx + 1, rowY + 1, lw - 2, ROW - 2, Theme.HOVER);

            float boxX = lx + 5;
            RenderUtil.drawRect(boxX, rowY + 3, 7, 7, selected ? Theme.ACCENT : 0xFF333333);
            if (selected) RenderUtil.drawRect(boxX + 2, rowY + 5, 3, 3, Theme.KNOB);

            int color = selected ? Theme.ACCENT : (hovered ? Theme.TEXT : Theme.TEXT_GRAY);
            RenderUtil.drawString(trimToWidth(options[index], lw - 22), lx + 15, rowY + 3, color);
        }

        if (options.length > rows) {
            float trackX = lx + lw - 4;
            float trackY = ly + 2;
            float trackH = lh - 4;
            RenderUtil.drawRect(trackX, trackY, 2, trackH, 0x1AFFFFFF);
            float thumbH = Math.max(10f, trackH * rows / options.length);
            float maxScroll = maxFirstVisible();
            float t = maxScroll == 0 ? 0f : firstVisible / maxScroll;
            RenderUtil.drawRect(trackX, trackY + t * (trackH - thumbH), 2, thumbH, 0xAA33D6FF);
        }
    }

    private String trimToWidth(String text, float maxWidth) {
        if (RenderUtil.getTextWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        while (text.length() > 0 && RenderUtil.getTextWidth(text + ellipsis) > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ellipsis;
    }

    private boolean listClick(float mx, float my, int button) {
        if (button != 0) {
            open = null;
            return true;
        }
        if (insideList(mx, my)) {
            float lx = listX();
            float lw = listW();
            // Leave the scrollbar strip alone; scrolling is done with the mouse wheel.
            if (mx < lx + lw - 5) {
                int row = (int) ((my - listY()) / ROW);
                int index = firstVisible + row;
                if (row >= 0 && row < visibleRows() && index < setting.getOptions().length) {
                    setting.toggle(setting.getOptions()[index]);
                    Potassium.scheduleSave();
                }
            }
            return true;
        }
        open = null;
        return true;
    }

    @Override
    public boolean onClick(float mx, float my, int button) {
        if (button != 0) return false;
        if (open == this) {
            open = null;
            return true;
        }
        ModeWidget.closeOpen();
        firstVisible = 0;
        open = this;
        return true;
    }

    @Override
    public float getPreferredWidth() {
        float marker = setting.isDefault() ? 0 : 7;
        return Math.max(120, RenderUtil.getTextWidth(setting.getName()) + marker + 14 + fieldW());
    }
}
