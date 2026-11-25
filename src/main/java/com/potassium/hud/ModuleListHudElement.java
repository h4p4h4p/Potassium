package com.potassium.hud;

import com.potassium.Potassium;
import com.potassium.gui.Theme;
import com.potassium.module.HUD;
import com.potassium.module.Module;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The enabled-module list, right-aligned like the old fixed one but movable. */
public class ModuleListHudElement extends HudElement {

    private static final int LINE_GAP = 1;

    private final Minecraft mc = Minecraft.getMinecraft();

    /**
     * The right edge the list is anchored to, which is what a drag places.
     *
     * <p>Keeping the right edge instead of the left one is what pins the list to the top right
     * corner: every row is right-aligned, so a module appearing or leaving changes the width.
     * Anchored on the left, that change would slide the whole list sideways, which is what
     * made the corner wander when modules were toggled.</p>
     */
    private int rightEdge = Integer.MIN_VALUE;

    public ModuleListHudElement() {
        // No stored edge until the user drags it: the default follows the right screen edge.
        y = 2;
    }

    @Override
    public String getId() {
        return "modulelist";
    }

    @Override
    public String getDisplayName() {
        return "Module List";
    }

    @Override
    public int getX() {
        if (rightEdge != Integer.MIN_VALUE) return rightEdge - getWidth();
        ScaledResolution sr = new ScaledResolution(mc);
        return Math.max(2, sr.getScaledWidth() - 2 - getWidth());
    }

    /** Dragging grabs the left edge; the right edge it lands on is what gets kept. */
    @Override
    public void setX(int x) {
        rightEdge = x + getWidth();
    }

    /** Places the list by the corner it is anchored to. */
    public void setRightEdge(int right) {
        rightEdge = right;
    }

    public int getStoredRightEdge() {
        return rightEdge == Integer.MIN_VALUE ? getX() + getWidth() : rightEdge;
    }

    /** Until it is dragged, the list keeps following the right screen edge. */
    @Override
    public boolean hasStoredPosition() {
        return rightEdge != Integer.MIN_VALUE;
    }

    @Override
    public int getStoredX() {
        return getX();
    }

    @Override
    public int getWidth() {
        int widest = 0;
        for (Row row : rows()) {
            widest = Math.max(widest, RenderUtil.getGuiTextWidthInt(row.text()));
        }
        return widest;
    }

    @Override
    public int getHeight() {
        int count = rows().size();
        if (count == 0) return 0;
        return count * (RenderUtil.getGuiTextHeightInt() + LINE_GAP) - LINE_GAP;
    }

    @Override
    public boolean isVisible() {
        HUD hud = hudModule();
        return hud != null && hud.isEnabled() && hud.shouldShowModuleList() && !rows().isEmpty();
    }

    @Override
    public boolean isEnabled() {
        HUD hud = hudModule();
        return hud != null && hud.shouldShowModuleList();
    }

    @Override
    public void render(int x, int y) {
        List<Row> rows = rows();
        int widest = getWidth();
        for (Row row : rows) {
            int rowWidth = RenderUtil.getGuiTextWidthInt(row.text());
            int rowX = x + widest - rowWidth;
            RenderUtil.drawGuiStringShadow(row.module.getName(), rowX, y, Theme.ACCENT);
            if (!row.suffix.isEmpty()) {
                RenderUtil.drawGuiStringShadow(row.suffix,
                        rowX + RenderUtil.getGuiTextWidthInt(row.module.getName()), y, Theme.TEXT_GRAY);
            }
            y += RenderUtil.getGuiTextHeightInt() + LINE_GAP;
        }
    }

    private List<Row> rows() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) {
            return Collections.emptyList();
        }
        HUD hud = hudModule();
        if (hud == null) return Collections.emptyList();
        boolean hideTags = hud.shouldHideTags();
        List<Row> rows = new ArrayList<>();
        for (Module module : Potassium.INSTANCE.moduleManager.getEnabledModules()) {
            if (hud.isModuleHidden(module.getName())) continue;
            String tag = hideTags ? null : module.getTag();
            rows.add(new Row(module, tag == null || tag.isEmpty() ? "" : " " + tag));
        }
        // Adjust's list is widest-first, and equal widths keep registration order (stable sort).
        rows.sort((a, b) -> Float.compare(RenderUtil.getGuiTextWidth(b.text()),
                RenderUtil.getGuiTextWidth(a.text())));
        return rows;
    }

    private HUD hudModule() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return null;
        Module module = Potassium.INSTANCE.moduleManager.getModule("HUD");
        return module instanceof HUD ? (HUD) module : null;
    }

    private static final class Row {
        private final Module module;
        private final String suffix;

        private Row(Module module, String suffix) {
            this.module = module;
            this.suffix = suffix;
        }

        private String text() {
            return module.getName() + suffix;
        }
    }
}
