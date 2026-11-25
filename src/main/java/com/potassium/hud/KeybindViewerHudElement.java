package com.potassium.hud;

import com.potassium.Potassium;
import com.potassium.gui.Theme;
import com.potassium.module.HUD;
import com.potassium.module.Module;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * The bound-module list, movable while a screen is open. Until it is dragged it follows the
 * watermark: directly under it while the watermark shows, and at the top-left margin when
 * the watermark is hidden.
 */
public class KeybindViewerHudElement extends HudElement {

    private static final int LINE_GAP = 1;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final WatermarkHudElement watermark;

    public KeybindViewerHudElement(WatermarkHudElement watermark) {
        this.watermark = watermark;
        x = Integer.MIN_VALUE;
        y = Integer.MIN_VALUE;
    }

    @Override
    public String getId() {
        return "keybinds";
    }

    @Override
    public String getDisplayName() {
        return "Keybind Viewer";
    }

    @Override
    public int getX() {
        if (x != Integer.MIN_VALUE) return x;
        return watermark.isVisible() ? watermark.getX() : 2;
    }

    @Override
    public int getY() {
        if (y != Integer.MIN_VALUE) return y;
        if (!watermark.isVisible()) return 2;
        return watermark.getY() + watermark.getHeight() + 2;
    }

    /** Until it is dragged, the list keeps following the watermark. */
    @Override
    public boolean hasStoredPosition() {
        return x != Integer.MIN_VALUE && y != Integer.MIN_VALUE;
    }

    @Override
    public int getStoredX() {
        return getX();
    }

    @Override
    public int getStoredY() {
        return getY();
    }

    @Override
    public int getWidth() {
        List<Row> rows = rows();
        if (rows.isEmpty()) return RenderUtil.getGuiTextWidthInt("No keybinds");
        int widest = 0;
        for (Row row : rows) {
            widest = Math.max(widest, RenderUtil.getGuiTextWidthInt(row.name) + 3
                    + RenderUtil.getGuiTextWidthInt(row.key));
        }
        return widest;
    }

    @Override
    public int getHeight() {
        int count = Math.max(1, rows().size());
        return count * (RenderUtil.getGuiTextHeightInt() + LINE_GAP) - LINE_GAP;
    }

    @Override
    public boolean isVisible() {
        return isEnabled();
    }

    @Override
    public boolean isEnabled() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return false;
        Module module = Potassium.INSTANCE.moduleManager.getModule("HUD");
        return module instanceof HUD && module.isEnabled() && ((HUD) module).shouldShowKeybinds();
    }

    @Override
    public void render(int x, int y) {
        List<Row> rows = rows();
        if (rows.isEmpty()) {
            RenderUtil.drawGuiStringShadow("No keybinds", x, y, Theme.TEXT_GRAY);
            return;
        }
        for (Row row : rows) {
            int nameColor = row.module.isEnabled() ? Theme.ACCENT : Theme.TEXT_GRAY;
            RenderUtil.drawGuiStringShadow(row.name, x, y, nameColor);
            RenderUtil.drawGuiStringShadow(row.key, x + RenderUtil.getGuiTextWidthInt(row.name) + 3, y,
                    Theme.TEXT_GRAY);
            y += RenderUtil.getGuiTextHeightInt() + LINE_GAP;
        }
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return rows;

        for (Module module : Potassium.INSTANCE.moduleManager.getModules()) {
            if (module.getKey() > 0) rows.add(new Row(module, keyName(module)));
        }
        rows.sort((a, b) -> a.module.getName().compareToIgnoreCase(b.module.getName()));
        return rows;
    }

    private String keyName(Module module) {
        int key = module.getKey();
        String name = key < 256 ? Keyboard.getKeyName(key) : null;
        if (name == null || name.isEmpty()) name = "Key " + key;
        return "[" + (module.isHoldBind() ? name + " (Hold)" : name) + "]";
    }

    private static final class Row {
        private final Module module;
        private final String name;
        private final String key;

        private Row(Module module, String key) {
            this.module = module;
            this.name = module.getName();
            this.key = key;
        }
    }
}
