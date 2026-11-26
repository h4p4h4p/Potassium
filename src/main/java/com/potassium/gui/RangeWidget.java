package com.potassium.gui;

import com.potassium.Potassium;
import com.potassium.settings.RangeSetting;
import com.potassium.util.RenderUtil;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Locale;

/**
 * Two-handle slider for a {@link RangeSetting}. The knob nearest the click is the one that
 * drags, and the fill between them shows the range the setting covers. The value text is also
 * a text field: clicking it lets both ends be typed as "low-high", the way the single sliders
 * take a typed number.
 */
public class RangeWidget extends Widget implements TextInput {

    private static final float KNOB = 6f;
    private static final int MAX_CHARS = 32;

    private final RangeSetting setting;
    private float trackX;
    private float trackW;
    private boolean draggingLow;
    private boolean draggingHigh;

    private boolean capturing;
    private final StringBuilder buffer = new StringBuilder();

    public RangeWidget(RangeSetting setting) {
        this.setting = setting;
    }

    @Override
    public void draw(float mx, float my) {
        if (draggingLow || draggingHigh) {
            if (Mouse.isButtonDown(0)) {
                update(mx);
            } else {
                draggingLow = false;
                draggingHigh = false;
                Potassium.scheduleSave();
            }
        }

        boolean hovered = contains(mx, my);
        computeTrack();
        float lowX = knobX(setting.getLow());
        float highX = knobX(setting.getHigh());

        RenderUtil.drawGuiString(setting.getName(), x + 6, RenderUtil.guiTextY(y, height), hovered ? Theme.TEXT : Theme.TEXT_GRAY);

        RenderUtil.drawRect(trackX, y + height / 2f - 2f, trackW, 4, Theme.TRACK);
        if (highX - lowX > 0.5f) {
            RenderUtil.drawRect(lowX, y + height / 2f - 2f, highX - lowX, 4,
                    draggingLow || draggingHigh || hovered ? MARKER : Theme.ACCENT);
        }
        drawKnob(lowX, draggingLow || hovered);
        drawKnob(highX, draggingHigh || hovered);

        String value = display();
        float valueX = x + width - 6 - RenderUtil.getGuiTextWidth(value);
        if (capturing) {
            RenderUtil.drawRect(valueX - 3, y + 1, RenderUtil.getGuiTextWidth(value) + 6, height - 2,
                    (0x40 << 24) | (Theme.ACCENT & 0xFFFFFF));
        }
        RenderUtil.drawGuiString(value, valueX, RenderUtil.guiTextY(y, height), capturing ? MARKER : Theme.ACCENT);
        if (capturing && (System.currentTimeMillis() / 450L) % 2 == 0) {
            RenderUtil.drawRect(valueX + RenderUtil.getGuiTextWidth(value) + 1, RenderUtil.guiTextY(y, height), 1,
                    RenderUtil.getGuiTextHeight(), MARKER);
        }
    }

    private void drawKnob(float knobX, boolean highlight) {
        float knobLeft = Math.max(trackX, Math.min(knobX - KNOB / 2f, trackX + trackW - KNOB));
        RenderUtil.drawRect(knobLeft, y + height / 2f - 4f, KNOB, 8,
                highlight ? MARKER : Theme.KNOB);
    }

    private String display() {
        if (capturing) return buffer.length() == 0 ? "..." : buffer.toString();
        return format(setting.getLow()) + "-" + format(setting.getHigh());
    }

    private String format(double value) {
        if (setting.getStep() >= 1.0D) return String.format(Locale.ROOT, "%.0f", value);
        int decimals = setting.getStep() >= 0.1D ? 1 : 2;
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private float knobX(double value) {
        double range = setting.getMax() - setting.getMin();
        double frac = range == 0.0D ? 0.0D : (value - setting.getMin()) / range;
        if (Double.isNaN(frac) || Double.isInfinite(frac)) frac = 0.0D;
        frac = Math.max(0.0D, Math.min(1.0D, frac));
        return trackX + (float) (trackW * frac);
    }

    private void computeTrack() {
        float labelZone = RenderUtil.getGuiTextWidth(setting.getName()) + 10;
        trackX = x + labelZone;
        float valueWidth = RenderUtil.getGuiTextWidth(display());
        trackW = Math.max((x + width - 12 - valueWidth) - trackX, 12);
    }

    @Override
    public float getPreferredWidth() {
        float valueWidth = RenderUtil.getGuiTextWidth(display());
        return Math.max(110, RenderUtil.getGuiTextWidth(setting.getName()) + 10 + 56 + valueWidth + 10);
    }

    private boolean onTrack(float mx, float my) {
        float ty = y + height / 2f - 2f;
        return mx >= trackX - 5 && mx <= trackX + trackW + 5 && my >= ty - 5 && my <= ty + 9;
    }

    @Override
    public boolean onClick(float mx, float my, int button) {
        if (button != 0) return false;
        computeTrack();
        if (hitsTextField(mx, my)) {
            if (!capturing) {
                capturing = true;
                buffer.setLength(0);
            }
            draggingLow = false;
            draggingHigh = false;
            return true;
        }
        if (!onTrack(mx, my)) return false;

        float lowX = knobX(setting.getLow());
        float highX = knobX(setting.getHigh());
        if (Math.abs(highX - lowX) <= 1.0f) {
            // A closed range like Delay's 0-0 puts both handles on one pixel, where the nearest
            // handle cannot be told apart and always answers "low". The low end is clamped by
            // the high one, so that drag would do nothing at all. The side of the click decides
            // instead: right opens the upper end, left opens the lower one.
            boolean upper = mx >= lowX;
            draggingLow = !upper;
            draggingHigh = upper;
        } else {
            boolean lowSide = Math.abs(mx - lowX) <= Math.abs(mx - highX);
            draggingLow = lowSide;
            draggingHigh = !lowSide;
        }
        update(mx);
        return true;
    }

    private void update(float mx) {
        if (setting.getStep() <= 0.0D) return;
        double frac = MathHelper.clamp_double((mx - trackX) / Math.max(trackW, 1f), 0, 1);
        double raw = setting.getMin() + frac * (setting.getMax() - setting.getMin());
        double snapped = Math.round(raw / setting.getStep()) * setting.getStep();
        if (draggingLow) setting.setLow(snapped);
        else if (draggingHigh) setting.setHigh(snapped);
    }

    @Override
    public boolean isTextCapturing() {
        return capturing;
    }

    @Override
    public boolean hitsTextField(float mx, float my) {
        String shown = format(setting.getLow()) + "-" + format(setting.getHigh());
        float vw = RenderUtil.getGuiTextWidth(shown);
        float vx = x + width - 6 - vw;
        return mx >= vx - 3 && mx <= x + width - 3 && my >= y && my <= y + height;
    }

    @Override
    public boolean onTextKey(char typedChar, int keyCode) {
        if (!capturing) return false;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            cancelTextInput();
            return true;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            commitTextInput();
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (buffer.length() > 0) buffer.setLength(buffer.length() - 1);
            return true;
        }
        if (accepts(typedChar) && buffer.length() < MAX_CHARS) {
            buffer.append(typedChar);
        }
        return true;
    }

    /** Digits, one decimal point per end and the single dash that separates the two ends. */
    private boolean accepts(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c == '-') return buffer.indexOf("-") < 0 && buffer.length() > 0;
        if (c == '.') {
            int lastDash = buffer.lastIndexOf("-");
            return buffer.substring(lastDash + 1).indexOf(".") < 0;
        }
        return false;
    }

    @Override
    public void commitTextInput() {
        if (!capturing) return;
        capturing = false;
        String text = buffer.toString();
        buffer.setLength(0);
        if (text.isEmpty()) return;
        int split = text.indexOf('-');
        try {
            if (split < 0) {
                double value = Double.parseDouble(text);
                setting.setRange(value, value);
            } else {
                setting.setRange(Double.parseDouble(text.substring(0, split)),
                        Double.parseDouble(text.substring(split + 1)));
            }
        } catch (NumberFormatException ignored) {
        }
        Potassium.scheduleSave();
    }

    @Override
    public void cancelTextInput() {
        capturing = false;
        buffer.setLength(0);
    }
}
