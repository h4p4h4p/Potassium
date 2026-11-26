package com.potassium.gui;

import com.potassium.Potassium;
import com.potassium.settings.StringSetting;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;

public class StringWidget extends Widget implements TextInput {

    private final StringSetting setting;
    private final Minecraft mc = Minecraft.getMinecraft();
    private String buffer = "";
    private boolean capturing = false;
    private int cursorPos = 0;
    private long lastBlinkTime = 0;
    private boolean blinkState = true;
    private static final int BLINK_INTERVAL_MS = 530;
    private static final float FIELD_MAX_WIDTH = 94f;
    private static final float FIELD_MIN_WIDTH = 24f;
    private static final float FIELD_RIGHT_PAD = 6f;
    private static final float LABEL_GAP = 4f;
    private int maxLength = 64;

    public StringWidget(StringSetting setting) {
        this.setting = setting;
        this.buffer = setting.getValue() != null ? setting.getValue() : "";
        this.cursorPos = buffer.length();
    }

    @Override
    public void draw(float mx, float my) {
        RenderUtil.drawGuiString(setting.getName(), x + 6, RenderUtil.guiTextY(y, height), Theme.TEXT_GRAY);

        float fieldX = fieldX();
        float fieldY = y + 1;
        float fieldW = fieldW();
        float fieldH = height - 2;

        RenderUtil.drawRoundedRect(fieldX, fieldY, fieldW, fieldH, 2f,
                capturing ? Theme.ACCENT : Theme.FIELD_BG);

        String display = buffer;
        float textX = fieldX + 4;
        float textY = fieldY + fieldH / 2f - RenderUtil.getGuiTextHeight() / 2f;
        // The narrow column can be shorter than the value; clip it to the field instead of
        // letting the text run over the panel edge.
        RenderUtil.enableScissor(fieldX + 2f, fieldY, fieldW - 4f, fieldH, ClickGUI.pixelsPerUnit());
        if (display.isEmpty() && setting.getPlaceholder() != null) {
            RenderUtil.drawGuiString(setting.getPlaceholder(), textX, textY, Theme.TEXT_FAINT);
        } else {
            RenderUtil.drawGuiString(display, textX, textY, Theme.TEXT);
        }

        if (capturing) {
            long now = System.currentTimeMillis();
            if (now - lastBlinkTime >= BLINK_INTERVAL_MS) {
                blinkState = !blinkState;
                lastBlinkTime = now;
            }
            if (blinkState) {
                String beforeCursor = buffer.substring(0, Math.min(cursorPos, buffer.length()));
                float cursorX = textX + RenderUtil.getGuiTextWidth(beforeCursor);
                RenderUtil.drawRect(cursorX, textY, 1, RenderUtil.getGuiTextHeight(), Theme.TEXT);
            }
        }
        RenderUtil.disableScissor();
    }

    @Override
    public float getPreferredWidth() {
        return Math.max(110, RenderUtil.getGuiTextWidth(setting.getName()) + 6 + FIELD_MAX_WIDTH + 6);
    }

    /**
     * The text field starts after the label instead of at a fixed offset from the right
     * edge. The shared column width can be narrower than label plus the old 100 px field
     * (the panel trims its right edge), which drew the value text over the label.
     */
    private float fieldX() {
        float labelEnd = x + 6f + RenderUtil.getGuiTextWidth(setting.getName()) + LABEL_GAP;
        float right = x + width - FIELD_RIGHT_PAD;
        return Math.min(Math.max(labelEnd, right - FIELD_MAX_WIDTH), right - FIELD_MIN_WIDTH);
    }

    private float fieldW() {
        return Math.max(FIELD_MIN_WIDTH, x + width - FIELD_RIGHT_PAD - fieldX());
    }

    @Override
    public boolean onClick(float mx, float my, int button) {
        float fieldX = fieldX();
        float fieldY = y + 1;
        float fieldW = fieldW();
        float fieldH = height - 2;

        boolean hit = mx >= fieldX && mx <= fieldX + fieldW && my >= fieldY && my <= fieldY + fieldH;
        if (hit && button == 0) {
            capturing = true;
            return true;
        }
        if (!hit && capturing) {
            commitTextInput();
        }
        return false;
    }

    @Override
    public boolean isTextCapturing() {
        return capturing;
    }

    @Override
    public boolean hitsTextField(float mx, float my) {
        float fieldX = fieldX();
        float fieldY = y + 1;
        float fieldW = fieldW();
        float fieldH = height - 2;
        return mx >= fieldX && mx <= fieldX + fieldW && my >= fieldY && my <= fieldY + fieldH;
    }

    @Override
    public boolean onTextKey(char typedChar, int keyCode) {
        if (!capturing) return false;

        if (keyCode == 14) { // backspace
            if (cursorPos > 0) {
                buffer = buffer.substring(0, cursorPos - 1) + buffer.substring(cursorPos);
                cursorPos--;
            }
            return true;
        }
        if (keyCode == 211) { // delete
            if (cursorPos < buffer.length()) {
                buffer = buffer.substring(0, cursorPos) + buffer.substring(cursorPos + 1);
            }
            return true;
        }
        if (keyCode == 203) { // left arrow
            cursorPos = Math.max(0, cursorPos - 1);
            return true;
        }
        if (keyCode == 205) { // right arrow
            cursorPos = Math.min(buffer.length(), cursorPos + 1);
            return true;
        }
        if (keyCode == 28 || keyCode == 156) { // enter
            commitTextInput();
            return true;
        }
        if (keyCode == 1) { // escape
            cancelTextInput();
            return true;
        }

        if (typedChar >= ' ' && typedChar <= '~' && buffer.length() < maxLength) {
            buffer = buffer.substring(0, cursorPos) + typedChar + buffer.substring(cursorPos);
            cursorPos++;
            return true;
        }
        return false;
    }

    @Override
    public void commitTextInput() {
        if (capturing) {
            setting.setValue(buffer);
            Potassium.scheduleSave();
            capturing = false;
        }
    }

    @Override
    public void cancelTextInput() {
        buffer = setting.getValue() != null ? setting.getValue() : "";
        cursorPos = buffer.length();
        capturing = false;
    }
}