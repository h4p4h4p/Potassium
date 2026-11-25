package com.potassium.hud;

import com.potassium.gui.Theme;
import com.potassium.util.RenderUtil;

/**
 * A free text line on the HUD. The text is stored with tokens such as %fps%, %bps% and
 * %server% so one line can mix static words with live values.
 */
public class TextHudElement extends HudElement {

    private final HudManager manager;
    private final String id;
    private String text;

    public TextHudElement(HudManager manager, String id, String text, int x, int y) {
        this.manager = manager;
        this.id = id;
        this.text = text == null ? "" : text;
        this.x = x;
        this.y = y;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return text.isEmpty() ? "Text" : text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text == null ? "" : text;
    }

    public String resolve() {
        return manager.resolve(text);
    }

    @Override
    public int getWidth() {
        return RenderUtil.getGuiTextWidthInt(resolve());
    }

    @Override
    public int getHeight() {
        return RenderUtil.getGuiTextHeightInt();
    }

    @Override
    public boolean isVisible() {
        return !resolve().isEmpty();
    }

    @Override
    public void render(int x, int y) {
        String resolved = resolve();
        if (!resolved.isEmpty()) RenderUtil.drawGuiStringShadow(resolved, x, y, Theme.TEXT);
    }
}
