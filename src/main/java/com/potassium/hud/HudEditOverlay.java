package com.potassium.hud;

import com.potassium.Potassium;
import com.potassium.gui.Theme;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.List;

/**
 * Direct, on-canvas HUD editing. There is no separate editor screen: while the chat is
 * open the cursor is visible, so a HUD row can be clicked and dragged, and a selected
 * text row is edited by typing. Editing stays inactive in every other screen, so the
 * ClickGUI, inventory and menus keep their own clicks and keys.
 */
public class HudEditOverlay {

    private static final int MAX_TEXT_LENGTH = 128;
    private static final long CARET_BLINK_MS = 500L;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final HudManager hud;

    private HudElement selected;
    private HudElement dragging;
    private int dragOffsetX;
    private int dragOffsetY;
    private int caret;
    private boolean swallowRelease;

    public HudEditOverlay(HudManager hud) {
        this.hud = hud;
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Drops the current edit target, saving any text change first. */
    public void clearSelection() {
        boolean hadSelection = selected != null;
        selected = null;
        dragging = null;
        caret = 0;
        if (hadSelection) save();
    }

    /**
     * Draws the editor layer. The chat draws its own dark background over the HUD, so the
     * whole HUD is redrawn here to keep it legible and clickable while editing.
     */
    public void render(int mouseX, int mouseY) {
        updateDrag(mouseX, mouseY);

        List<HudElement> elements = hud.getElements();
        for (HudElement element : elements) {
            if (!element.isEnabled()) continue;
            hud.renderEditable(element);
            if (element.getWidth() == 0 || element.getHeight() == 0) {
                RenderUtil.drawGuiString(element.getDisplayName(), element.getX(), element.getY(),
                        Theme.TEXT_FAINT);
            }
        }

        HudElement hovered = hudElementAt(mouseX, mouseY);
        if (hovered != null && hovered != selected && dragging == null) {
            drawOutline(hovered, 0x80FFFFFF);
        }
        if (selected != null && selected.isEnabled()) {
            drawOutline(selected, Theme.ACCENT);
            if (selected instanceof TextHudElement) {
                drawCaret((TextHudElement) selected);
            }
        }
    }

    /** Starts selecting/dragging a HUD row. Returns true when the click hit one. */
    public boolean mousePressed(int mouseX, int mouseY, int button) {
        if (button != 0) return false;

        HudElement hit = hudElementAt(mouseX, mouseY);
        if (hit == null) {
            clearSelection();
            return false;
        }

        if (selected != hit) {
            clearSelection();
            selected = hit;
            caret = hit instanceof TextHudElement
                    ? ((TextHudElement) hit).getText().length() : 0;
        }
        dragging = hit;
        dragOffsetX = mouseX - hit.getX();
        dragOffsetY = mouseY - hit.getY();
        return true;
    }

    public void mouseReleased() {
        if (dragging == null) return;
        dragging = null;
        save();
    }

    /** Handles one keyboard event while a text row is selected. */
    public boolean keyTyped(char typedChar, int keyCode) {
        if (!(selected instanceof TextHudElement)) return false;
        TextHudElement element = (TextHudElement) selected;
        String text = element.getText();
        caret = Math.max(0, Math.min(caret, text.length()));

        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN
                || keyCode == Keyboard.KEY_NUMPADENTER) {
            clearSelection();
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (caret > 0) {
                element.setText(text.substring(0, caret - 1) + text.substring(caret));
                caret--;
            }
            return true;
        }
        if (keyCode == Keyboard.KEY_DELETE) {
            if (caret < text.length()) {
                element.setText(text.substring(0, caret) + text.substring(caret + 1));
            }
            return true;
        }
        if (keyCode == Keyboard.KEY_LEFT) {
            if (caret > 0) caret--;
            return true;
        }
        if (keyCode == Keyboard.KEY_RIGHT) {
            if (caret < text.length()) caret++;
            return true;
        }
        if (keyCode == Keyboard.KEY_HOME) {
            caret = 0;
            return true;
        }
        if (keyCode == Keyboard.KEY_END) {
            caret = text.length();
            return true;
        }
        if (typedChar >= ' ' && typedChar != 127 && text.length() < MAX_TEXT_LENGTH) {
            element.setText(text.substring(0, caret) + typedChar + text.substring(caret));
            caret++;
        }
        // Every key belongs to the active line until Enter or Escape ends editing.
        return true;
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!isChatOpen()) return;
        render(event.mouseX, event.mouseY);
    }

    @SubscribeEvent
    public void onMouseInput(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!isChatOpen()) return;
        if (Mouse.getEventButton() != 0) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int mouseX = Mouse.getEventX() * sr.getScaledWidth() / mc.displayWidth;
        int mouseY = sr.getScaledHeight()
                - Mouse.getEventY() * sr.getScaledHeight() / mc.displayHeight - 1;

        if (Mouse.getEventButtonState()) {
            if (mousePressed(mouseX, mouseY, 0)) {
                // The row owns this click; do not let the slot/button underneath use it too.
                event.setCanceled(true);
                swallowRelease = true;
            }
        } else {
            boolean swallow = swallowRelease;
            swallowRelease = false;
            mouseReleased();
            if (swallow) event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onKeyboardInput(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!isChatOpen()) return;
        if (!(selected instanceof TextHudElement)) return;

        int key = Keyboard.getEventKey();
        char character = Keyboard.getEventCharacter();
        if (!Keyboard.getEventKeyState() && key != 0) return;
        if (key == 0 && character < ' ') return;

        keyTyped(character, key);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (isChatOpen()) return;

        boolean hadSelection = selected != null;
        selected = null;
        dragging = null;
        caret = 0;
        swallowRelease = false;
        if (hadSelection) save();
    }

    /** Editing is only armed while the chat is open, where typing is expected. */
    private boolean isChatOpen() {
        return mc.currentScreen instanceof GuiChat;
    }

    private void updateDrag(int mouseX, int mouseY) {
        if (dragging == null) return;
        if (!Mouse.isButtonDown(0)) {
            mouseReleased();
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        int maxX = Math.max(0, sr.getScaledWidth() - dragging.getHitWidth());
        int maxY = Math.max(0, sr.getScaledHeight() - dragging.getHitHeight());
        dragging.setX(Math.max(0, Math.min(maxX, mouseX - dragOffsetX)));
        dragging.setY(Math.max(0, Math.min(maxY, mouseY - dragOffsetY)));
    }

    private HudElement hudElementAt(int mouseX, int mouseY) {
        List<HudElement> elements = hud.getElements();
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement element = elements.get(i);
            if (!element.isEnabled()) continue;
            int x = element.getX();
            int y = element.getY();
            if (mouseX < x - 2 || mouseX > x + element.getHitWidth() + 2) continue;
            if (mouseY < y - 2 || mouseY > y + element.getHitHeight() + 2) continue;
            return element;
        }
        return null;
    }

    private void drawOutline(HudElement element, int color) {
        float x = element.getX() - 1;
        float y = element.getY() - 1;
        float w = element.getHitWidth() + 2;
        float h = element.getHitHeight() + 2;
        RenderUtil.drawRect(x, y, w, 1, color);
        RenderUtil.drawRect(x, y + h - 1, w, 1, color);
        RenderUtil.drawRect(x, y, 1, h, color);
        RenderUtil.drawRect(x + w - 1, y, 1, h, color);
    }

    private void drawCaret(TextHudElement element) {
        if ((System.currentTimeMillis() / CARET_BLINK_MS) % 2L != 0L) return;
        String text = element.getText();
        int index = Math.max(0, Math.min(caret, text.length()));
        String before = hud.resolve(text.substring(0, index));
        float x = element.getX() + RenderUtil.getGuiTextWidth(before);
        RenderUtil.drawRect(x, element.getY(), 1f, RenderUtil.getGuiTextHeight(), Theme.TEXT);
    }

    private void save() {
        if (Potassium.INSTANCE != null) Potassium.scheduleSave();
    }
}
