package com.potassium.module;

import com.potassium.Potassium;
import com.potassium.command.CommandManager;
import com.potassium.config.ConfigManager;
import com.potassium.settings.SelectSetting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The local config browser.
 *
 * <p>Files dropped into {@code config/potassium/configs} appear in the Config dropdown; picking
 * one loads it immediately. The folder is created on first use so the path exists to drop files
 * into, and the list is refreshed once a second instead of on every frame the dropdown is open.
 * Loading is deferred to the next client tick: the click arrives inside the ClickGUI's own
 * widget loop, and applying a config there changes module and widget state underneath it.</p>
 */
public class LocalConfigs extends Module {

    private static final long CACHE_MILLIS = 1000L;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final SelectSetting configSetting;

    private String[] cachedOptions;
    private long cachedAt;
    private String pendingFile;

    public LocalConfigs() {
        super("Configs", Category.CONFIGURATIONS);

        configSetting = new SelectSetting("Config", new String[0]) {
            @Override
            public String[] getOptions() {
                return listConfigs();
            }

            @Override
            public String getDisplayValue() {
                String selected = selectedConfig();
                return selected == null ? "None" : selected;
            }

            /** Single selection: picking a row replaces the previous choice and loads it. */
            @Override
            public void toggle(String option) {
                for (String candidate : getOptions()) {
                    if (!candidate.equals(option) && isSelected(candidate)) super.toggle(candidate);
                }
                if (!isSelected(option)) super.toggle(option);
                pendingFile = option;
            }
        };
        settings.add(configSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || pendingFile == null) return;

        String name = pendingFile;
        pendingFile = null;
        load(name);
    }

    private void load(String name) {
        File file = new File(configDirectory(), name);
        if (!file.isFile()) {
            CommandManager.sendMessage("\u00a7cConfig '\u00a7f" + name + "\u00a7c' is no longer in "
                    + configDirectory().getPath());
            return;
        }

        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
        } catch (IOException e) {
            CommandManager.sendMessage("\u00a7cCould not read \u00a7f" + file.getName());
            return;
        }

        ConfigManager manager = Potassium.INSTANCE == null ? null : Potassium.INSTANCE.configManager;
        if (manager == null || Potassium.INSTANCE.moduleManager == null) return;

        if (manager.importText(text, Potassium.INSTANCE.moduleManager,
                Potassium.INSTANCE.hudManager)) {
            CommandManager.sendMessage("\u00a7aLoaded config \u00a7f" + name
                    + "\u00a77 (previous config saved as potassium.cfg.bak)");
        } else {
            CommandManager.sendMessage("\u00a7cCould not apply \u00a7f" + file.getName());
        }

        // Loading a config may have switched this module on through its persisted state.
        if (isEnabled()) setEnabled(false);
    }

    /** The folder users drop their .cfg files into. */
    public static File configDirectory() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/potassium/configs");
    }

    /** Every .cfg file in the config folder, sorted for both the picker and %config. */
    public static List<File> configFiles() {
        File directory = configDirectory();
        if (!directory.exists()) directory.mkdirs();

        File[] files = directory.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".cfg"));
        List<File> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) result.add(file);
            }
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    private String[] listConfigs() {
        long now = System.currentTimeMillis();
        if (cachedOptions != null && now - cachedAt < CACHE_MILLIS) return cachedOptions;

        List<String> names = new ArrayList<>();
        for (File file : configFiles()) names.add(file.getName());
        cachedOptions = names.toArray(new String[0]);
        cachedAt = now;
        return cachedOptions;
    }

    private String selectedConfig() {
        for (String option : configSetting.getOptions()) {
            if (configSetting.isSelected(option)) return option;
        }
        return null;
    }

    @Override
    public void onEnable() {
        String selected = selectedConfig();
        if (selected == null) {
            CommandManager.sendMessage("\u00a77Drop .cfg files in \u00a7f"
                    + configDirectory().getPath() + "\u00a77, then pick one in Configs.");
            setEnabled(false);
            return;
        }
        pendingFile = selected;
    }

    @Override
    public void onDisable() {
        pendingFile = null;
    }

    @Override
    public String getTag() {
        return Integer.toString(listConfigs().length);
    }

    /** Loading is an action; restoring this row as on at startup would only repeat it. */
    @Override
    public boolean isPersistentEnabled() {
        return false;
    }
}
