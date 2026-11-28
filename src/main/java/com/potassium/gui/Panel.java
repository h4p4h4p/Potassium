package com.potassium.gui;

import com.potassium.module.Category;
import com.potassium.module.Module;
import com.potassium.util.RenderUtil;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

public class Panel {

    private static final float HEADER_HEIGHT = 16f;
    private static final float DEFAULT_WIDTH = 122f;
    /**
     * Empty space trimmed from the column's right edge. The old value was 16; the extra 16
     * makes the module lists the requested 16 px skinnier without touching their left inset.
     */
    private static final float RIGHT_TRIM = 32f;
    private static final float SCROLLBAR_W = 3f;
    private static final float WHEEL_STEP = 12f;

    private final Category category;
    private final ClickGUI gui;
    private final List<ModuleButton> buttons = new ArrayList<>();

    private float x;
    private float y;
    private boolean collapsed;
    private float scroll;

    private boolean draggingHeader;
    private boolean manuallyMoved;
    private float dragOffsetX;
    private float dragOffsetY;

    private boolean draggingScrollbar;
    private float scrollGrab;

    private float forcedWidth = -1f;

    public Panel(Category category, ClickGUI gui) {
        this.category = category;
        this.gui = gui;
        List<Module> modules = new ArrayList<>(
                gui.getClient().moduleManager.getModulesInCategory(category));
        // Alphabetical, case-insensitive: registration order left the columns jumbled.
        modules.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (Module module : modules) {
            buttons.add(new ModuleButton(module, this));
        }
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public Category getCategory() {
        return category;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    private List<ModuleButton> visibleButtons() {
        List<ModuleButton> out = new ArrayList<>();
        for (ModuleButton button : buttons) {
            if (button.matchesFilter(gui.getSearchField())) out.add(button);
        }
        return out;
    }

    private float contentHeight() {
        float h = 0f;
        for (ModuleButton button : visibleButtons()) h += button.getHeight();
        return h;
    }

    private float viewHeight() {
        return Math.min(contentHeight(), Math.max(0f, gui.getMaxPanelHeight() - HEADER_HEIGHT));
    }

    private float maxScroll() {
        return Math.max(0f, contentHeight() - viewHeight());
    }

    private boolean hasScrollbar() {
        return maxScroll() > 0f;
    }

    private float bodyWidth() {
        // Always keep the scrollbar gutter reserved. Otherwise the first expansion that
        // makes this panel scroll narrows every row by 5 px and the whole list shifts.
        return getWidth() - SCROLLBAR_W - 2f;
    }

    /**
     * Width the settings column needs on its own. The side preview is left out so the
     * uniform column width shared by the other panels does not balloon while ESP is open.
     */
    public float getBaseWidth() {
        float w = DEFAULT_WIDTH;
        for (ModuleButton button : visibleButtons()) {
            // Measured at the size each part is drawn: the module name full size, the
            // column under it at the settings size.
            float bw = RenderUtil.getGuiTextWidth(button.getModule().getName());
            float previousTextScale = RenderUtil.getGuiTextScale();
            RenderUtil.setGuiTextScale(RenderUtil.SETTINGS_TEXT_SCALE);
            try {
                float bindW = button.getBindWidget() == null ? 0
                        : RenderUtil.getGuiTextWidth(button.getBindWidget().keyName());
                bw += bindW + 36;
                for (Widget widget : button.getWidgets()) {
                    bw = Math.max(bw, widget.getPreferredWidth() + 8);
                }
            } finally {
                RenderUtil.setGuiTextScale(previousTextScale);
            }
            w = Math.max(w, bw);
        }
        // A long category name such as CONFIGURATION can outgrow a column sized only by its
        // module rows.
        w = Math.max(w, RenderUtil.getGuiTextWidth(category.getName().toUpperCase()) + 24f + RIGHT_TRIM);
        if (hasScrollbar()) w += SCROLLBAR_W + 2f;
        return Math.max(0f, w - RIGHT_TRIM);
    }

    /** Full width this panel needs, including the preview column of an expanded module. */
    public float getNaturalWidth() {
        float w = getBaseWidth();
        if (collapsed) return w;
        for (ModuleButton button : visibleButtons()) {
            if (button.isExpanded() && button.hasSidePreview()) {
                w += ModuleButton.PREVIEW_WIDTH + 16;
                break;
            }
        }
        return w;
    }

    /**
     * The uniform column width is a floor, not a cap. ESP's side preview is drawn inside
     * the panel, so clamping the panel to the uniform width left the setting widgets only
     * about a third of the row and made their labels and values draw over each other.
     */
    public float getWidth() {
        if (forcedWidth <= 0f) return getNaturalWidth();
        return hasExpandedPreview() ? Math.max(forcedWidth, getNaturalWidth()) : forcedWidth;
    }

    private boolean hasExpandedPreview() {
        if (collapsed) return false;
        for (ModuleButton button : buttons) {
            if (button.hasSidePreview() && button.isExpanded()) return true;
        }
        return false;
    }

    public void setForcedWidth(float width) {
        this.forcedWidth = width;
    }

    public float getHeight() {
        return HEADER_HEIGHT + (collapsed ? 0f : viewHeight());
    }

    public boolean isInHeader(float mx, float my) {
        return mx >= x && mx <= x + getWidth() && my >= y && my <= y + HEADER_HEIGHT;
    }

    public boolean contains(float mx, float my) {
        return mx >= x && mx <= x + getWidth() && my >= y && my <= y + getHeight();
    }

    public void startHeaderDrag(float mx, float my) {
        draggingHeader = true;
        manuallyMoved = true;
        dragOffsetX = mx - x;
        dragOffsetY = my - y;
    }

    public void updateHeaderDrag(float mx, float my) {
        if (!draggingHeader) return;
        if (!Mouse.isButtonDown(0)) {
            draggingHeader = false;
            return;
        }
        x = mx - dragOffsetX;
        y = my - dragOffsetY;
    }

    public void stopHeaderDrag() {
        draggingHeader = false;
        draggingScrollbar = false;
    }

    public boolean isDragging() {
        return draggingHeader;
    }

    /** True while the header has been dragged; the wrap layout then leaves the panel where it is. */
    public boolean hasManualPosition() {
        return manuallyMoved;
    }

    public void resetManualPosition() {
        manuallyMoved = false;
    }

    public void toggleCollapsed() {
        SelectWidget.closeOpen();
        collapsed = !collapsed;
        scroll = 0f;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void scrollBy(float amount) {
        if (collapsed) return;
        if (amount != 0f) SelectWidget.closeOpen();
        scroll = clampScroll(scroll + amount);
    }

    private float clampScroll(float value) {
        float max = maxScroll();
        return value < 0f ? 0f : (value > max ? max : value);
    }

    public BindWidget getCapturingBind() {
        for (ModuleButton button : buttons) {
            BindWidget bindWidget = button.getBindWidget();
            if (bindWidget != null && bindWidget.isCapturing()) return bindWidget;
        }
        return null;
    }

    public TextInput getCapturingText() {
        for (ModuleButton button : buttons) {
            for (Widget widget : button.getWidgets()) {
                TextInput input = widget.getCapturingText();
                if (input != null) return input;
            }
        }
        return null;
    }

    public void draw(float mx, float my) {
        updateHeaderDrag(mx, my);
        if (draggingScrollbar) updateScrollbarDrag(my);

        scroll = clampScroll(scroll);

        float height = getHeight();
        float width = getWidth();
        RenderUtil.drawRect(x, y, width, height, Theme.PANEL_BG);
        RenderUtil.drawRect(x, y, width, HEADER_HEIGHT, Theme.HEADER_BG);
        if (mx >= x && mx <= x + width && my >= y && my <= y + HEADER_HEIGHT) {
            RenderUtil.drawRect(x, y, width, HEADER_HEIGHT, 0x0CFFFFFF);
        }

        RenderUtil.drawGuiStringBold(category.getName(), x + 8, RenderUtil.guiTextY(y, HEADER_HEIGHT), Theme.TEXT);
        // Module count on the right, the reference header's only furniture. Clicking it still
        // collapses the panel, so no +/- glyph is needed.
        String count = String.valueOf(visibleButtons().size());
        RenderUtil.drawGuiString(count, x + width - 8 - RenderUtil.getGuiTextWidth(count),
                RenderUtil.guiTextY(y, HEADER_HEIGHT), Theme.TEXT_FAINT);

        if (collapsed) return;

        float bodyTop = y + HEADER_HEIGHT;
        float view = viewHeight();
        float inner = bodyWidth();
        float by = bodyTop - scroll;
        // A scrolled row sticks out above the body; without clipping that sliver is drawn
        // last, on top of the category header.
        boolean clipped = view > 0f;
        if (clipped) RenderUtil.enableScissor(x, bodyTop, width, view, ClickGUI.pixelsPerUnit());
        for (ModuleButton button : visibleButtons()) {
            float bh = button.getHeight();
            if (by + bh > bodyTop && by < bodyTop + view) {
                button.setPosition(x, by, inner);
                button.draw(mx, my);
            }
            by += bh;
        }
        if (clipped) RenderUtil.disableScissor();

        if (hasScrollbar()) drawScrollbar(view);
    }

    private void drawScrollbar(float view) {
        float trackTop = y + HEADER_HEIGHT;
        RenderUtil.drawRect(x + getWidth() - SCROLLBAR_W - 1, trackTop, SCROLLBAR_W, view, 0x1AFFFFFF);

        float content = contentHeight();
        float thumbH = Math.max(18f, view * (view / content));
        float t = maxScroll() <= 0f ? 0f : scroll / maxScroll();
        float thumbY = trackTop + t * (view - thumbH);
        RenderUtil.drawRect(x + getWidth() - SCROLLBAR_W - 1, thumbY, SCROLLBAR_W, thumbH,
                (0x88 << 24) | (Theme.ACCENT & 0xFFFFFF));
    }

    private void updateScrollbarDrag(float my) {
        if (!Mouse.isButtonDown(0)) {
            draggingScrollbar = false;
            return;
        }
        float view = viewHeight();
        float content = contentHeight();
        float thumbH = Math.max(18f, view * (view / content));
        float trackTop = y + HEADER_HEIGHT;
        float usable = Math.max(1f, view - thumbH);
        scroll = clampScroll((my - scrollGrab - trackTop) / usable * maxScroll());
    }

    private boolean onScrollbar(float mx, float my) {
        if (!hasScrollbar() || collapsed) return false;
        return mx >= x + getWidth() - SCROLLBAR_W - 4
                && my >= y + HEADER_HEIGHT
                && my <= y + getHeight();
    }

    public boolean onClick(float mx, float my, int button) {
        if (my <= y + HEADER_HEIGHT) return false;

        if (onScrollbar(mx, my)) {
            if (button == 0) {
                float view = viewHeight();
                float content = contentHeight();
                float thumbH = Math.max(18f, view * (view / content));
                float trackTop = y + HEADER_HEIGHT;
                float t = Math.max(0f, Math.min(1f, (my - trackTop) / Math.max(1f, view)));
                scroll = clampScroll(t * maxScroll());
                draggingScrollbar = true;
                scrollGrab = my - (trackTop + t * (view - thumbH));
            }
            return true;
        }

        if (mx > x + bodyWidth()) return false;

        float bodyTop = y + HEADER_HEIGHT;
        float by = bodyTop - scroll;
        for (ModuleButton moduleButton : visibleButtons()) {
            float bh = moduleButton.getHeight();
            if (by + bh > bodyTop && my >= by && my <= by + bh) {
                return moduleButton.onClick(mx, my, button);
            }
            by += bh;
        }
        return false;
    }

    public ClickGUI getGui() {
        return gui;
    }
}
