package com.potassium.gui;

import com.potassium.module.Esp;
import com.potassium.module.Module;
import com.potassium.settings.Setting;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleButton {

    private static final float ROW_HEIGHT = 13f;
    private static final float EXPAND_ZONE = 12f;
    public static final float PREVIEW_WIDTH = 88f;
    private static final float PREVIEW_MIN_HEIGHT = 74f;

    private final Module module;
    private final Panel panel;
    private final List<Widget> widgets = new ArrayList<>();
    private final List<Setting> builtSettings = new ArrayList<>();
    private final Minecraft mc = Minecraft.getMinecraft();
    private BindWidget bindWidget;
    private boolean expanded;
    private boolean builtWhileFiltering;
    private EntityOtherPlayerMP previewPlayer;

    private float x;
    private float y;
    private float width;

    public ModuleButton(Module module, Panel panel) {
        this.module = module;
        this.panel = panel;
        refreshWidgets();
    }

    public void setPosition(float x, float y, float width) {
        this.x = x;
        this.y = y;
        this.width = width;
    }

    public float getHeight() {
        float h = ROW_HEIGHT;
        if (expanded) h += Math.max(widgetsHeight(), getPreviewMinHeight());
        return h;
    }

    public boolean hasSidePreview() {
        return module instanceof Esp;
    }

    public float getPreviewMinHeight() {
        return hasSidePreview() ? PREVIEW_MIN_HEIGHT : 0;
    }

    private float widgetsHeight() {
        float h = 0;
        for (Widget widget : widgets) h += widget.getHeight();
        return h;
    }

    private void refreshWidgets() {
        SearchField search = panel.getGui().getSearchField();
        List<Setting> visible = new ArrayList<>();
        for (Setting setting : module.getSettings()) {
            if (!module.isSettingVisible(setting)) continue;
            if (!matchesSearch(setting, search)) continue;
            visible.add(setting);
        }
        // A module with no settings still needs its bind row, so an empty widget list
        // cannot be treated as already built while the bind widget is still missing.
        boolean needsBind = module.canBind() && bindWidget == null;
        boolean same = !needsBind && builtSettings.size() == visible.size()
                && builtWhileFiltering == search.isFiltering();
        if (same) {
            for (int i = 0; i < visible.size(); i++) {
                if (builtSettings.get(i) != visible.get(i)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;

        widgets.clear();
        builtSettings.clear();
        builtWhileFiltering = search.isFiltering();
        for (int i = 0; i < visible.size(); i++) {
            Setting setting = visible.get(i);
            String group = setting.getGroup();
            if (group == null) {
                addSettingWidget(setting);
                continue;
            }
            int end = i + 1;
            while (end < visible.size() && group.equals(visible.get(end).getGroup())) end++;
            SettingsDropdown dropdown = new SettingsDropdown(
                    module.getName(), group, visible.subList(i, end));
            dropdown.setForcedExpanded(search.isFiltering());
            widgets.add(dropdown);
            i = end - 1;
        }
        builtSettings.addAll(visible);
        if (module.canBind()) {
            bindWidget = new BindWidget(module);
            widgets.add(bindWidget);
        }
    }

    private void addSettingWidget(Setting setting) {
        Widget widget = Widget.forSetting(setting);
        if (widget != null) widgets.add(widget);
    }

    private boolean matchesSearch(Setting setting, SearchField search) {
        if (search == null || !search.isFiltering()) return true;
        if (search.matches(setting.getName())) return true;
        String group = setting.getGroup();
        return group != null && search.matches(group);
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** Hidden entirely while a search is active and no setting of this module matches. */
    public boolean matchesFilter(SearchField search) {
        if (search == null || !search.isFiltering()) return true;
        for (Setting setting : module.getSettings()) {
            if (module.isSettingVisible(setting) && matchesSearch(setting, search)) return true;
        }
        return false;
    }

    public BindWidget getBindWidget() {
        return bindWidget;
    }

    public List<Widget> getWidgets() {
        return widgets;
    }

    public Module getModule() {
        return module;
    }

    public void draw(float mx, float my) {
        refreshWidgets();
        boolean hovered = mx >= x && mx <= x + width && my >= y && my <= y + ROW_HEIGHT;
        // Rows bleed to the panel's full width, the way the reference highlights them; the
        // scrollbar gutter is still part of the row's fill.
        float full = panel.getWidth();

        if (module.isEnabled()) {
            RenderUtil.drawRect(x, y, full, ROW_HEIGHT, Theme.ACCENT);
        }
        if (hovered) {
            RenderUtil.drawRect(x, y, full, ROW_HEIGHT, Theme.HOVER);
        }

        int textColor = module.isEnabled() ? Theme.TEXT : Theme.TEXT_GRAY;
        RenderUtil.drawGuiString(module.getName(), x + 7, RenderUtil.guiTextY(y, ROW_HEIGHT), textColor);

        // The expander only appears under the cursor; at rest the rows stay as clean as the
        // reference, which shows nothing on the right.
        if (hovered) {
            String indicator = expanded ? "-" : "+";
            RenderUtil.drawGuiString(indicator, x + width - 5 - RenderUtil.getGuiTextWidth(indicator),
                    RenderUtil.guiTextY(y, ROW_HEIGHT), module.isEnabled() ? 0xFFFFFFFF : Theme.TEXT_GRAY);
        }

        if (expanded) {
            float wh = Math.max(widgetsHeight(), getPreviewMinHeight());
            boolean preview = hasSidePreview();
            RenderUtil.drawRect(x, y + ROW_HEIGHT, full, wh, Theme.BODY_BG);

            float bodyRight = x + width - 4;
            float pw = PREVIEW_WIDTH;
            float px = preview ? bodyRight - pw + 3 : bodyRight;
            float widgetW = preview ? px - (x + 4) - 8 : width - 8;

            float wy = y + ROW_HEIGHT;
            for (Widget widget : widgets) {
                widget.setPosition(x + 4, wy, widgetW, widget.getHeight());
                widget.draw(mx, my);
                wy += widget.getHeight();
            }
            if (preview) {
                RenderUtil.drawRect(px - 5, y + ROW_HEIGHT, 1, wh, Theme.SEPARATOR);
                drawEspPreview((Esp) module, px, y + ROW_HEIGHT, pw, wh);
            }
            RenderUtil.drawRect(x, y + ROW_HEIGHT + wh - 1, full, 1, Theme.SEPARATOR);
        } else {
            RenderUtil.drawRect(x, y + ROW_HEIGHT - 1, full, 1, Theme.SEPARATOR);
        }
    }

    private void drawEspPreview(Esp esp, float px, float py, float pw, float ph) {
        EntityOtherPlayerMP player = getPreviewEntity();
        if (player == null) return;

        int color = esp.getPreviewColor();

        float cx = px + pw / 2f;
        float top = py + 4f;
        float bottom = py + ph - 4f;
        float scale = (bottom - top) / 1.8f;

        // The model is rendered inside the GUI pass. The separator drawn just before this
        // leaves the last GUI colour set, so reset it or the player texture is tinted (and
        // made translucent) by that rectangle. Lighting, depth, culling and texture state are
        // restored before the 2D ESP frame is drawn, so the frame cannot inherit depth writes
        // from the model.
        RenderUtil.GlState glState = RenderUtil.saveGlState();
        RenderManager renderManager = mc.getRenderManager();
        float previousViewY = renderManager.playerViewY;
        boolean previousShadow = renderManager.isRenderShadow();

        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableColorMaterial();
        GlStateManager.enableRescaleNormal();
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, bottom, 50.0F);
        GlStateManager.scale(-scale, scale, scale);
        GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        // Undo the display rotation so the model faces the viewer; vanilla's inventory
        // preview does the same after setting up the lights.
        GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
        // A world shadow does not belong in a GUI preview and was being rendered as a dark
        // patch over the model. playerViewY 180 keeps the preview oriented like inventory.
        renderManager.setPlayerViewY(180.0F);
        renderManager.setRenderShadow(false);
        renderManager.renderEntityWithPosYaw(player, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
        RenderHelper.disableStandardItemLighting();

        GlStateManager.popMatrix();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableColorMaterial();
        renderManager.setPlayerViewY(previousViewY);
        renderManager.setRenderShadow(previousShadow);
        RenderUtil.restoreGlState(glState);

        // The frame is a 2D overlay on top of the model. Draw it without the model's depth
        // writes, then restore the GUI state once more for the next widget.
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);

        float h = bottom - top;
        float w = h * 0.5f;
        float xr = cx - w / 2f;
        float len = Math.max(4f, Math.min(h / 6f, 10f));
        if (esp.getPreviewCorners()) {
            RenderUtil.drawRect(xr, top, len, 1, color);
            RenderUtil.drawRect(xr, top, 1, len, color);
            RenderUtil.drawRect(xr + w - len, top, len, 1, color);
            RenderUtil.drawRect(xr + w - 1, top, 1, len, color);
            RenderUtil.drawRect(xr, top + h - 1, len, 1, color);
            RenderUtil.drawRect(xr, top + h - len, 1, len, color);
            RenderUtil.drawRect(xr + w - len, top + h - 1, len, 1, color);
            RenderUtil.drawRect(xr + w - 1, top + h - len, 1, len, color);
        } else {
            RenderUtil.drawRect(xr, top, w, 1, color);
            RenderUtil.drawRect(xr, top + h - 1, w, 1, color);
            RenderUtil.drawRect(xr, top, 1, h, color);
            RenderUtil.drawRect(xr + w - 1, top, 1, h, color);
        }
        if (esp.getPreviewNames()) {
            String name = "[" + player.getName() + "]";
            RenderUtil.drawGuiString(name, cx - RenderUtil.getGuiTextWidth(name) / 2f, top - 11f, esp.getPreviewNameColor());
        }

        RenderUtil.restoreGlState(glState);
    }

    private EntityOtherPlayerMP getPreviewEntity() {
        if (mc.theWorld == null || mc.getSession() == null) return null;
        if (previewPlayer == null || previewPlayer.worldObj != mc.theWorld) {
            previewPlayer = new EntityOtherPlayerMP(mc.theWorld, mc.getSession().getProfile());
        }

        double rx = getRenderPos("renderPosX");
        double ry = getRenderPos("renderPosY");
        double rz = getRenderPos("renderPosZ");
        previewPlayer.setPosition(rx, ry, rz);
        previewPlayer.lastTickPosX = rx;
        previewPlayer.lastTickPosY = ry;
        previewPlayer.lastTickPosZ = rz;
        previewPlayer.prevPosX = rx;
        previewPlayer.prevPosY = ry;
        previewPlayer.prevPosZ = rz;
        previewPlayer.rotationYaw = 0;
        previewPlayer.rotationPitch = 0;
        previewPlayer.rotationYawHead = 0;
        previewPlayer.renderYawOffset = 0;
        previewPlayer.limbSwing = 0;
        previewPlayer.limbSwingAmount = 0;
        previewPlayer.prevLimbSwingAmount = 0;
        previewPlayer.swingProgress = 0;
        previewPlayer.prevSwingProgress = 0;
        previewPlayer.onGround = true;
        previewPlayer.hurtTime = 0;
        return previewPlayer;
    }

    private static final Map<String, Field> RENDER_POS_FIELDS = new HashMap<>();

    private double getRenderPos(String name) {
        try {
            Field field = RENDER_POS_FIELDS.get(name);
            if (field == null) {
                field = RenderManager.class.getDeclaredField(name);
                field.setAccessible(true);
                RENDER_POS_FIELDS.put(name, field);
            }
            return field.getDouble(mc.getRenderManager());
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    public boolean onClick(float mx, float my, int button) {
        if (my > y + ROW_HEIGHT) {
            if (!expanded) return false;
            for (Widget widget : widgets) {
                if (widget.contains(mx, my)) {
                    return widget.onClick(mx, my, button);
                }
            }
            return false;
        }

        if (button == 1) {
            expanded = !expanded;
            return true;
        }

        module.toggle();
        return true;
    }
}