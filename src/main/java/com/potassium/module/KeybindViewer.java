package com.potassium.module;

import com.potassium.Potassium;
import com.potassium.gui.Theme;
import com.potassium.util.RenderUtil;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the modules that have a key bind under the watermark. If the Interface
 * watermark is hidden, the list starts at the watermark's normal top-left position.
 */
public class KeybindViewer extends Module {

    private static final int MARGIN = 2;
    private static final int LINE_GAP = 1;

    public KeybindViewer() {
        super("Keybind Viewer", Category.INTERFACE);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (!isEnabled() || Potassium.INSTANCE == null) return;

        List<Module> bound = new ArrayList<>();
        for (Module module : Potassium.INSTANCE.moduleManager.getModules()) {
            if (module.getKey() > 0) bound.add(module);
        }
        bound.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        int y = MARGIN;
        if (watermarkVisible()) y += RenderUtil.getTextHeight() + 2;

        if (bound.isEmpty()) {
            RenderUtil.drawString("No keybinds", MARGIN, y, Theme.TEXT_GRAY);
            return;
        }

        for (Module module : bound) {
            String key = keyName(module);
            String name = module.getName();
            int nameColor = module.isEnabled() ? Theme.ACCENT : Theme.TEXT_GRAY;

            RenderUtil.drawString(name, MARGIN, y, nameColor);
            RenderUtil.drawString("[" + key + "]", MARGIN + RenderUtil.getTextWidth(name) + 3, y,
                    Theme.TEXT_GRAY);
            y += RenderUtil.getTextHeight() + LINE_GAP;
        }
    }

    private boolean watermarkVisible() {
        Module module = Potassium.INSTANCE.moduleManager.getModule("Interface");
        return module instanceof Interface && module.isEnabled()
                && ((Interface) module).shouldShowWatermark();
    }

    private String keyName(Module module) {
        int key = module.getKey();
        String name = key < 256 ? Keyboard.getKeyName(key) : null;
        if (name == null || name.isEmpty()) name = "Key " + key;
        return module.isHoldBind() ? name + " (Hold)" : name;
    }

    @Override
    public boolean canBind() {
        return false;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}
