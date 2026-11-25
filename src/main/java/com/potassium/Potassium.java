package com.potassium;

import com.potassium.command.CommandManager;
import com.potassium.config.ConfigManager;
import com.potassium.gui.ClickGUI;
import com.potassium.hud.HudEditOverlay;
import com.potassium.hud.HudManager;
import com.potassium.module.Module;
import com.potassium.module.ModuleManager;
import com.potassium.util.MovementInputStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = Potassium.MODID, name = Potassium.NAME, version = Potassium.VERSION)
public class Potassium {

    public static final String MODID = "potassium";
    public static final String NAME = "Potassium";
    public static final String VERSION = "0.2";

    public static Potassium INSTANCE;

    public ModuleManager moduleManager;
    public ConfigManager configManager;
    public ClickGUI clickGui;
    public CommandManager commandManager;
    public HudManager hudManager;
    public HudEditOverlay hudEditOverlay;

    public final KeyBinding clickGuiKey = new KeyBinding("ClickGUI", Keyboard.KEY_K, "Potassium");

    private final Minecraft mc = Minecraft.getMinecraft();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        INSTANCE = this;
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        net.minecraftforge.fml.client.registry.ClientRegistry.registerKeyBinding(clickGuiKey);
        MinecraftForge.EVENT_BUS.register(this);
        MovementInputStack.init();
        moduleManager = new ModuleManager();
        clickGui = new ClickGUI();
        commandManager = new CommandManager();
        hudManager = new HudManager();
        hudEditOverlay = new HudEditOverlay(hudManager);
        configManager = new ConfigManager();
        configManager.load(moduleManager, hudManager);
    }

    public static void scheduleSave() {
        if (INSTANCE != null) INSTANCE.saveConfig();
    }

    public void saveConfig() {
        if (configManager != null && moduleManager != null) {
            configManager.save(moduleManager, hudManager);
        }
    }

    public void closeGui() {
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        saveConfig();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (clickGuiKey.isPressed()) {
            if (mc.currentScreen != null && mc.currentScreen instanceof ClickGUI) {
                mc.displayGuiScreen(null);
            } else if (mc.currentScreen == null) {
                mc.displayGuiScreen(clickGui);
            }
        }
        updateModuleKeys();
    }

    private void updateModuleKeys() {
        boolean guiOpen = mc.currentScreen != null;
        for (Module module : moduleManager.getModules()) {
            int key = module.getKey();
            boolean down = key > 0 && key < 256 && Keyboard.isKeyDown(key);
            boolean wasDown = module.isBindDown();
            module.setBindDown(down);

            if (module.isHoldBind()) {
                // Hold binds follow the key exactly, including when the GUI has focus.
                boolean shouldEnable = !guiOpen && down;
                if (module.isEnabled() != shouldEnable) module.setEnabled(shouldEnable);
                continue;
            }

            if (!guiOpen && down && !wasDown) {
                module.toggle();
            }
        }
    }

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Module hudModule = moduleManager.getModule("HUD");
        if (hudModule == null || !hudModule.isEnabled()) return;
        // The chat HUD editor redraws the whole HUD on top of its background; drawing it
        // here too stacked a second set of background plates behind every text row.
        if (mc.currentScreen instanceof GuiChat) return;
        hudManager.render();
    }
}