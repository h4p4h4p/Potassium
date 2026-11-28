package com.potassium.gui;

import com.potassium.Potassium;
import com.potassium.module.Category;
import com.potassium.module.HUD;
import com.potassium.module.Module;
import com.potassium.util.GuiFont;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClickGUI extends GuiScreen {

    private static final float PANEL_MARGIN = 8f;
    private static final float PANEL_GAP = 4f;

    /**
     * Physical pixels per ClickGUI unit. The interface is authored at a 2 px/unit design
     * scale and drawn at 80% of it; unlike every other screen it does not follow Minecraft's
     * GUI Scale setting, so changing that setting leaves it exactly the same size.
     */
    public static final float PIXELS_PER_UNIT = 1.6f;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final List<Panel> panels = new ArrayList<>();
    private final SearchField searchField = new SearchField();

    private float maxPanelHeight = 200f;
    /** Shared column width, frozen after the screen opens so expanding a module cannot
     *  make the other categories jump sideways. */
    private float uniformWidth = -1f;

    public ClickGUI() {
        for (Category category : Category.values()) {
            panels.add(new Panel(category, this));
        }
    }

    public Potassium getClient() {
        return Potassium.INSTANCE;
    }

    public SearchField getSearchField() {
        return searchField;
    }

    public float getMaxPanelHeight() {
        return maxPanelHeight;
    }

    public static float pixelsPerUnit() {
        return PIXELS_PER_UNIT;
    }

    /** The screen's own viewport in ClickGUI units, taken from the framebuffer. */
    private float viewWidth() {
        return mc.displayWidth / PIXELS_PER_UNIT;
    }

    private float viewHeight() {
        return mc.displayHeight / PIXELS_PER_UNIT;
    }

    @Override
    public void initGui() {
        // Layout measures text, so it has to measure with the same bake the drawing uses.
        GuiFont.pushScale(PIXELS_PER_UNIT);
        try {
            layout();
            for (Panel panel : panels) {
                panel.resetManualPosition();
            }
            uniformWidth = -1f;
            reflow();
        } finally {
            GuiFont.popScale();
        }
    }

    private void layout() {
        maxPanelHeight = Math.max(60f, viewHeight() - PANEL_MARGIN);
        searchField.layout(Math.round(viewHeight()));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);

        ScaledResolution sr = new ScaledResolution(mc);
        int scaleFactor = sr.getScaleFactor();
        int mx = Math.round(mouseX * scaleFactor / PIXELS_PER_UNIT);
        int my = Math.round(mouseY * scaleFactor / PIXELS_PER_UNIT);
        float viewHeight = viewHeight();

        // One transform for the whole screen: every coordinate below is a ClickGUI unit, so
        // the size is set by PIXELS_PER_UNIT instead of Minecraft's GUI scale.
        GlStateManager.pushMatrix();
        GlStateManager.scale(PIXELS_PER_UNIT / scaleFactor, PIXELS_PER_UNIT / scaleFactor, 1.0F);
        GuiFont.pushScale(PIXELS_PER_UNIT);
        try {
            RenderUtil.drawRect(0, 0, viewWidth(), viewHeight, getDimColor());

            layout();
            reflow();
            for (Panel panel : panels) {
                panel.draw(mx, my);
            }
            // After every panel, so open dropdowns are not painted over by the settings below
            // them. They belong to the settings column, so they draw at its smaller size too.
            float previousTextScale = RenderUtil.getGuiTextScale();
            RenderUtil.setGuiTextScale(RenderUtil.SETTINGS_TEXT_SCALE);
            try {
                ModeWidget.drawOpenPopup(mx, my, viewHeight - 4f);
                searchField.draw(mx, my);
                SelectWidget.drawOpenPopup(mx, my, viewHeight - 4f);
            } finally {
                RenderUtil.setGuiTextScale(previousTextScale);
            }
        } finally {
            GuiFont.popScale();
            GlStateManager.popMatrix();
        }
    }

    private void reflow() {
        float margin = PANEL_MARGIN / 2f;
        float availWidth = Math.max(60f, viewWidth() - margin * 2f);
        float availHeight = Math.max(60f, viewHeight() - margin * 2f);

        // Freeze the shared width once per screen. Recomputing it from the settings
        // columns while a module was expanded made every category shift sideways;
        // the panels now keep their positions, and only a side preview can grow.
        if (uniformWidth <= 0f) {
            for (Panel panel : panels) {
                uniformWidth = Math.max(uniformWidth, panel.getBaseWidth());
            }
        }
        for (Panel panel : panels) {
            panel.setForcedWidth(uniformWidth);
        }

        // Wrap the columns into rows so a high GUI scale does not push the last categories
        // past the right edge, and split the available height between the rows so the
        // columns in the lower rows scroll instead of running off the bottom.
        List<List<Panel>> rows = wrapRows(availWidth);
        for (int pass = 0; pass < 4; pass++) {
            maxPanelHeight = Math.max(60f,
                    (availHeight - (rows.size() - 1) * PANEL_GAP) / rows.size());
            List<List<Panel>> next = wrapRows(availWidth);
            boolean sameShape = next.size() == rows.size();
            rows = next;
            if (sameShape) break;
        }

        float y = margin;
        for (List<Panel> row : rows) {
            float x = margin;
            for (Panel panel : row) {
                float slot = uniformWidth > 0f ? uniformWidth : panel.getWidth();
                if (!panel.isDragging() && !panel.hasManualPosition()) {
                    panel.setPosition(x, y);
                }
                // Advance by the frozen slot width, not the panel's live width: an ESP
                // side preview grows inside its column instead of shoving the next panel.
                x += slot + PANEL_GAP;
            }
            // Every row takes its full share of the height, used or not. Sized from the live
            // panel heights instead, expanding a module grew its row and pushed every row
            // below it down, which moved categories that had not been touched at all.
            y += maxPanelHeight + PANEL_GAP;
        }
    }

    /** Packs the panels left to right into as many rows as the scaled width needs. */
    private List<List<Panel>> wrapRows(float availWidth) {
        List<List<Panel>> rows = new ArrayList<>();
        List<Panel> row = new ArrayList<>();
        float used = 0f;
        for (Panel panel : panels) {
            float width = uniformWidth > 0f ? uniformWidth : panel.getWidth();
            if (!row.isEmpty() && used + PANEL_GAP + width > availWidth) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0f;
            }
            if (!row.isEmpty()) used += PANEL_GAP;
            row.add(panel);
            used += width;
        }
        if (!row.isEmpty()) rows.add(row);
        return rows;
    }

    private int getDimColor() {
        int alpha = 153;
        if (Potassium.INSTANCE != null) {
            Module hudModule = Potassium.INSTANCE.moduleManager.getModule("HUD");
            if (hudModule instanceof HUD) {
                alpha = ((HUD) hudModule).getBgDimAlpha();
            }
        }
        return (alpha << 24) | 0x000000;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mx = Math.round(Mouse.getEventX() * viewWidth() / mc.displayWidth);
        int my = Math.round(viewHeight() - Mouse.getEventY() * viewHeight() / mc.displayHeight - 1);
        GuiFont.pushScale(PIXELS_PER_UNIT);
        try {
            float previousTextScale = RenderUtil.getGuiTextScale();
            RenderUtil.setGuiTextScale(RenderUtil.SETTINGS_TEXT_SCALE);
            try {
                if (SelectWidget.handleOpenPopupScroll(mx, my, wheel)) return;
            } finally {
                RenderUtil.setGuiTextScale(previousTextScale);
            }
            for (Panel panel : panels) {
                if (panel.isCollapsed() || !panel.contains(mx, my)) continue;
                panel.scrollBy(-wheel * 14f);
                return;
            }
        } finally {
            GuiFont.popScale();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        GuiFont.pushScale(PIXELS_PER_UNIT);
        try {
            clickAt(toView(mouseX), toView(mouseY), mouseButton);
        } finally {
            GuiFont.popScale();
        }
    }

    /** Vanilla hands the screen GUI-scaled coordinates; the ClickGUI works in its own units. */
    private int toView(int coordinate) {
        return Math.round(coordinate * new ScaledResolution(mc).getScaleFactor() / PIXELS_PER_UNIT);
    }

    private void clickAt(int mouseX, int mouseY, int mouseButton) {
        TextInput capturing = findCapturingText();
        if (capturing != null) {
            if (capturing.hitsTextField(mouseX, mouseY)) {
                capturing.onClick(mouseX, mouseY, mouseButton);
                return;
            } else {
                capturing.commitTextInput();
            }
        }

        // A mode list swallows every click while it is open: pick, or dismiss without
        // letting the click through to the module row and toggle it. The list is laid out at
        // the settings text size, so its hit boxes are tested at that size as well.
        float previousTextScale = RenderUtil.getGuiTextScale();
        RenderUtil.setGuiTextScale(RenderUtil.SETTINGS_TEXT_SCALE);
        try {
            if (ModeWidget.handleOpenPopupClick(mouseX, mouseY, mouseButton)) {
                return;
            }
            if (SelectWidget.handleOpenPopupClick(mouseX, mouseY, mouseButton)) {
                return;
            }
        } finally {
            RenderUtil.setGuiTextScale(previousTextScale);
        }

        if (searchField.onClick(mouseX, mouseY, mouseButton)) {
            return;
        }
        // Clicking anywhere else moves focus out of the search box. Otherwise a stale
        // search focus would keep consuming the key that the bind widget is waiting for.
        searchField.unfocus();

        for (Panel panel : panels) {
            if (panel.isInHeader(mouseX, mouseY)) {
                if (mouseButton == 0) {
                    if (mouseX >= panel.getX() + panel.getWidth() - 20 && mouseX <= panel.getX() + panel.getWidth()) {
                        panel.toggleCollapsed();
                    } else {
                        panel.startHeaderDrag(mouseX, mouseY);
                    }
                }
                return;
            }
        }

        for (Panel panel : panels) {
            if (!panel.isCollapsed() && panel.contains(mouseX, mouseY)) {
                panel.onClick(mouseX, mouseY, mouseButton);
                return;
            }
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        for (Panel panel : panels) {
            panel.stopHeaderDrag();
        }
        getClient().saveConfig();
    }

    private TextInput findCapturingText() {
        for (Panel panel : panels) {
            TextInput input = panel.getCapturingText();
            if (input != null) return input;
        }
        return null;
    }

    private BindWidget findCapturingBind() {
        for (Panel panel : panels) {
            BindWidget widget = panel.getCapturingBind();
            if (widget != null) return widget;
        }
        return null;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE && ModeWidget.isOpen()) {
            ModeWidget.closeOpen();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE && SelectWidget.isOpen()) {
            SelectWidget.closeOpen();
            return;
        }

        TextInput textInput = findCapturingText();
        if (textInput != null) {
            textInput.onTextKey(typedChar, keyCode);
            return;
        }

        BindWidget capturing = findCapturingBind();
        if (capturing != null) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_DELETE) {
                // Escape while listening clears the bind rather than only leaving the
                // listener, the same unbind Delete performs; there is no other key left to
                // press to abandon the capture without choosing something.
                capturing.setBind(-1);
            } else {
                capturing.setBind(keyCode);
            }
            return;
        }

        if (searchField.isFocused()) {
            searchField.onKey(typedChar, keyCode);
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            return;
        }

        if (Potassium.INSTANCE != null) {
            if (Potassium.INSTANCE.clickGuiKey.getKeyCode() == keyCode) {
                close();
                return;
            }
            for (Module module : getClient().moduleManager.getModules()) {
                if (module.getKey() == keyCode && module.getKey() != -1) {
                    module.toggle();
                    return;
                }
            }
        }

        super.keyTyped(typedChar, keyCode);
    }

    public void close() {
        TextInput textInput = findCapturingText();
        if (textInput != null) textInput.commitTextInput();
        ModeWidget.closeOpen();
        SelectWidget.closeOpen();
        searchField.unfocus();
        mc.displayGuiScreen(null);
        getClient().saveConfig();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
