package com.potassium.config;

import com.potassium.command.CommandManager;
import com.potassium.gui.Theme;
import com.potassium.module.Module;
import com.potassium.module.ModuleManager;
import com.potassium.settings.Setting;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private static final String CONFIG_VERSION_KEY = "potassium.configVersion";
    private static final String CONFIG_VERSION = "3";

    private final File file;

    public ConfigManager() {
        this.file = new File(Minecraft.getMinecraft().mcDataDir, "config/potassium.cfg");
    }

    public void save(ModuleManager modules) {
        List<String> lines = new ArrayList<>();
        lines.add(CONFIG_VERSION_KEY + "=" + CONFIG_VERSION);
        lines.add("potassium.accent=" + String.format("%06X", Theme.ACCENT & 0xFFFFFF));
        lines.add("potassium.rainbow=" + CommandManager.rainbow);
        for (Module module : modules.getModules()) {
            lines.add(module.getName() + ".enabled=" + module.isEnabled());
            lines.add(module.getName() + ".key=" + module.getKey());
            lines.add(module.getName() + ".bindMode=" + (module.isHoldBind() ? "hold" : "toggle"));
            for (Setting setting : module.getSettings()) {
                lines.add(module.getName() + "." + setting.getName() + "=" + setting.toConfigString());
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.write(file.toPath(), lines, Charset.forName("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    /** Carries a renamed module's settings over to its new config keys. */
    private static void migrateRenamedModule(Map<String, String> values, String from, String to) {
        for (Map.Entry<String, String> entry : new ArrayList<>(values.entrySet())) {
            String key = entry.getKey();
            if (!key.startsWith(from + ".")) continue;
            values.putIfAbsent(to + key.substring(from.length()), entry.getValue());
        }
    }

    /** Scaffold.Tower was a boolean before the Tower mode setting existed. */
    private static void migrateScaffoldTower(Map<String, String> values) {
        String raw = values.get("Scaffold.Tower");
        if (raw == null) return;
        if (raw.equalsIgnoreCase("true")) values.put("Scaffold.Tower", "1");
        else if (raw.equalsIgnoreCase("false")) values.put("Scaffold.Tower", "0");
    }

    /** Converts the old 0..100 AntiKnockback sliders to the current -1..1 multipliers. */
    private static void migrateAntiKnockbackScale(Map<String, String> values, String key) {
        String raw = values.get(key);
        if (raw == null) return;
        try {
            double old = Double.parseDouble(raw);
            if (Math.abs(old) <= 1.0D) return;
            double scaled = old / 100.0D;
            double snapped = Math.round(scaled * 10.0D) / 10.0D;
            values.put(key, Double.toString(Math.max(-1.0D, Math.min(1.0D, snapped))));
        } catch (NumberFormatException ignored) {
        }
    }

    public void load(ModuleManager modules) {
        if (!file.exists()) return;
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file.toPath(), Charset.forName("UTF-8"))) {
                int idx = line.indexOf('=');
                if (idx < 0) continue;
                values.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        } catch (IOException e) {
            return;
        }

        // Old configs predate the version key and the Reversal mode, so mode 2 is the removed
        // Simulations entry. With the key present, mode 2 is Reversal and must be kept.
        boolean legacyConfig = !values.containsKey(CONFIG_VERSION_KEY);
        int configVersion = 0;
        String version = values.get(CONFIG_VERSION_KEY);
        if (version != null) {
            try {
                configVersion = Integer.parseInt(version);
            } catch (NumberFormatException ignored) {
            }
        }

        String accent = values.get("potassium.accent");
        // The default accent moved from the old blue to red; only carry configs that never
        // changed it. Saving with the new version stops a deliberate 1B395C from being reset.
        if (accent != null && configVersion < 2 && accent.equalsIgnoreCase("1B395C")) {
            accent = "FF5555";
            values.put("potassium.accent", accent);
        }
        if (accent != null) {
            try {
                Theme.ACCENT = 0xFF000000 | (Integer.parseInt(accent, 16) & 0xFFFFFF);
            } catch (NumberFormatException ignored) {
            }
        }
        String rainbow = values.get("potassium.rainbow");
        if (rainbow != null) {
            CommandManager.rainbow = rainbow.equalsIgnoreCase("true");
        }

        // Renamed modules keep their old settings and binds.
        migrateRenamedModule(values, "Sprint", "AutoSprint");
        migrateRenamedModule(values, "Internet", "Blink");
        migrateRenamedModule(values, "LegitScaffold", "Eagle");

        // Speed's mode slot 2 held the removed HurtBoost; LowHop was added there in v3.
        if (configVersion < 3 && "2".equals(values.get("Speed.Mode"))) {
            values.put("Speed.Mode", "0");
        }

        // AntiKnockback's knockback multipliers used to be stored as 0..100 percentages.
        migrateAntiKnockbackScale(values, "AntiKnockback.Horizontal");
        migrateAntiKnockbackScale(values, "AntiKnockback.Vertical");
        migrateScaffoldTower(values);
        // Simulations was removed from AntiKnockback; use Modify rather than clamping to Lag.
        if (legacyConfig && "2".equals(values.get("AntiKnockback.Mode"))) {
            values.put("AntiKnockback.Mode", "0");
        }

        for (Module module : modules.getModules()) {
            String bindMode = values.get(module.getName() + ".bindMode");
            module.setHoldBind("hold".equalsIgnoreCase(bindMode));

            String enabled = values.get(module.getName() + ".enabled");
            // A hold bind is only meant to be on while its key is down, so never restore it as on.
            if (enabled != null && !(module.isHoldBind() && enabled.equalsIgnoreCase("true"))) {
                module.setEnabled(enabled.equalsIgnoreCase("true"));
            }
            String key = values.get(module.getName() + ".key");
            if (key != null) {
                try {
                    module.setKey(Integer.parseInt(key));
                } catch (NumberFormatException ignored) {
                }
            }
            for (Setting setting : module.getSettings()) {
                String value = values.get(module.getName() + "." + setting.getName());
                if (value != null) setting.fromConfigString(value);
            }
        }
    }
}