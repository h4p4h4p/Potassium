package com.potassium.config;

import com.potassium.command.CommandManager;
import com.potassium.gui.Theme;
import com.potassium.hud.HudManager;
import com.potassium.hud.TextHudElement;
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
    private static final String CONFIG_VERSION = "10";

    private final File file;

    public ConfigManager() {
        this.file = new File(Minecraft.getMinecraft().mcDataDir, "config/potassium.cfg");
    }

    public void save(ModuleManager modules, HudManager hud) {
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
        if (hud != null) saveHud(lines, hud);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.write(file.toPath(), lines, Charset.forName("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    private static void saveHud(List<String> lines, HudManager hud) {
        lines.add("Hud.watermark.x=" + hud.getWatermark().getStoredX());
        lines.add("Hud.watermark.y=" + hud.getWatermark().getStoredY());
        if (hud.getModuleList().hasStoredPosition()) {
            // The list is anchored by its right edge so the corner stays where it was placed.
            lines.add("Hud.moduleList.right=" + hud.getModuleList().getStoredRightEdge());
            lines.add("Hud.moduleList.y=" + hud.getModuleList().getStoredY());
        }
        if (hud.getKeybindViewer().hasStoredPosition()) {
            lines.add("Hud.keybinds.x=" + hud.getKeybindViewer().getStoredX());
            lines.add("Hud.keybinds.y=" + hud.getKeybindViewer().getStoredY());
        }
        lines.add("Hud.fps.x=" + hud.getFps().getStoredX());
        lines.add("Hud.fps.y=" + hud.getFps().getStoredY());
        lines.add("Hud.textCount=" + hud.getTexts().size());
        for (int i = 0; i < hud.getTexts().size(); i++) {
            TextHudElement text = hud.getTexts().get(i);
            lines.add("Hud.text." + i + ".value=" + text.getText());
            lines.add("Hud.text." + i + ".x=" + text.getX());
            lines.add("Hud.text." + i + ".y=" + text.getY());
        }
    }

    private static void loadHud(Map<String, String> values, HudManager hud) {
        hud.getWatermark().setX(intValue(values, "Hud.watermark.x", hud.getWatermark().getX()));
        hud.getWatermark().setY(intValue(values, "Hud.watermark.y", hud.getWatermark().getY()));
        if (values.containsKey("Hud.moduleList.right")) {
            hud.getModuleList().setRightEdge(intValue(values, "Hud.moduleList.right",
                    hud.getModuleList().getStoredRightEdge()));
        } else if (values.containsKey("Hud.moduleList.x")) {
            // Older configs stored the left edge; keep that spot and anchor on its right edge.
            hud.getModuleList().setX(intValue(values, "Hud.moduleList.x", hud.getModuleList().getX()));
        }
        if (values.containsKey("Hud.moduleList.y")) {
            hud.getModuleList().setY(intValue(values, "Hud.moduleList.y", hud.getModuleList().getY()));
        }
        if (values.containsKey("Hud.keybinds.x")) {
            hud.getKeybindViewer().setX(intValue(values, "Hud.keybinds.x",
                    hud.getKeybindViewer().getX()));
        }
        if (values.containsKey("Hud.keybinds.y")) {
            hud.getKeybindViewer().setY(intValue(values, "Hud.keybinds.y",
                    hud.getKeybindViewer().getY()));
        }
        hud.getFps().setX(intValue(values, "Hud.fps.x", hud.getFps().getX()));
        hud.getFps().setY(intValue(values, "Hud.fps.y", hud.getFps().getY()));

        hud.clearTexts();
        int count = Math.max(0, intValue(values, "Hud.textCount", 0));
        for (int i = 0; i < count; i++) {
            String text = values.get("Hud.text." + i + ".value");
            int x = intValue(values, "Hud.text." + i + ".x", 2);
            int y = intValue(values, "Hud.text." + i + ".y", 2 + i * 10);
            hud.addText(text == null ? "" : text, x, y);
        }
    }

    private static int intValue(Map<String, String> values, String key, int fallback) {
        String raw = values.get(key);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** CPS used to be a single number; the range slider stores "low-high". */
    private static void migrateSingleToRange(Map<String, String> values, String key) {
        String raw = values.get(key);
        if (raw == null || raw.indexOf('-') >= 0) return;
        try {
            double value = Double.parseDouble(raw);
            values.put(key, value + "-" + value);
        } catch (NumberFormatException ignored) {
        }
    }

    private static void copyValue(Map<String, String> values, String from, String to) {
        String value = values.get(from);
        if (value != null) values.putIfAbsent(to, value);
    }

    /** Carries a renamed module's settings over to its new config keys. */
    private static void migrateRenamedModule(Map<String, String> values, String from, String to) {
        for (Map.Entry<String, String> entry : new ArrayList<>(values.entrySet())) {
            String key = entry.getKey();
            if (!key.startsWith(from + ".")) continue;
            values.putIfAbsent(to + key.substring(from.length()), entry.getValue());
        }
    }

    /** Moves a renamed module's keys and clears the old prefix for a module taking that name. */
    private static void moveRenamedModule(Map<String, String> values, String from, String to) {
        migrateRenamedModule(values, from, to);
        values.keySet().removeIf(key -> key.startsWith(from + "."));
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

    public void load(ModuleManager modules, HudManager hud) {
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
        // The shipped accent then moved from red to the reference menu's periwinkle. Follow it
        // only for configs still on the old default; a deliberately chosen colour is kept.
        if (accent != null && configVersion < 6 && accent.equalsIgnoreCase("FF5555")) {
            accent = "425AD2";
            values.put("potassium.accent", accent);
        }
        // And now to banana amber, the client's own gold. Same rule: only configs that never
        // changed the shipped default follow it, so a hand-picked colour stays put.
        if (accent != null && configVersion < 7 && accent.equalsIgnoreCase("425AD2")) {
            accent = "F0A612";
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
        // The Interface module became GUI, then HUD. Both old prefixes still populate the
        // HUD settings; GUI wins if a config happens to contain both.
        migrateRenamedModule(values, "GUI", "HUD");
        migrateRenamedModule(values, "Interface", "HUD");
        // Keybind Viewer used to be its own module; it is a HUD toggle now.
        if ("true".equalsIgnoreCase(values.get("Keybind Viewer.enabled"))) {
            values.putIfAbsent("HUD.Keybind Viewer", "true");
        }
        // The HUD's Panel Mode dropdown was renamed to Background; keep the saved choice.
        copyValue(values, "HUD.Panel Mode", "HUD.Background");
        // Backtrack briefly carried the KBDelay name; those keys belong to Backtrack again.
        if (configVersion < 5) {
            moveRenamedModule(values, "KBDelay", "Backtrack");
        }
        // Eagle's edge guard is now Scaffold's Legit mode; carry the gates over. Its Distance
        // measured from the player, while Legit now sneaks at the block edge, so it has no
        // equivalent and the new Edge Lookahead keeps its default.
        migrateRenamedModule(values, "LegitScaffold", "Eagle");
        copyValue(values, "Eagle.While Sneaking", "Scaffold.While Sneaking");
        copyValue(values, "Eagle.While In Air", "Scaffold.While In Air");

        // Speed's mode slot 2 held the removed HurtBoost; LowHop was added there in v3.
        if (configVersion < 3 && "2".equals(values.get("Speed.Mode"))) {
            values.put("Speed.Mode", "0");
        }

        // KillAura's Rotations lost the Off entry and Alert Distance became its own switch,
        // so both Mode indices shift for configs written before version 4.
        if (configVersion < 4) {
            String rotations = values.get("KillAura.Rotations");
            if ("1".equals(rotations)) values.put("KillAura.Rotations", "0");
            else if ("2".equals(rotations)) values.put("KillAura.Rotations", "1");
            if ("2".equals(values.get("KillAura.Mode"))) {
                values.put("KillAura.Mode", "0");
                values.putIfAbsent("KillAura.Alert Distance", "true");
            }
        }

        // KillAura now ships with Silent rotations: the camera stays free while the server
        // receives the aim. Configs still on the old Normal default follow it; Normal can be
        // selected again afterwards, and saving with the new version keeps that choice.
        if (configVersion < 8 && "0".equals(values.get("KillAura.Rotations"))) {
            values.put("KillAura.Rotations", "1");
        }

        // Flight's removed NCP and Vulcan entries used to clamp to Packet at slots 2 and 3.
        // Rewound now takes slot 2, so keep configs written before it existed on Packet.
        if (configVersion < 9) {
            String flightMode = values.get("Flight.Mode");
            if ("2".equals(flightMode) || "3".equals(flightMode)) {
                values.put("Flight.Mode", "1");
            }
        }
        // Flight's Damage mode became Adjust's Rewound mode; carry its speed over. The old
        // knockback boost has no equivalent in Adjust's staged speed profile.
        if (configVersion < 10) {
            copyValue(values, "Flight.Damage Speed", "Flight.Speed");
        }

        // KillAura's Movement Fix was a boolean; Strict (1) is the old true state.
        String oldKillAuraMoveFix = values.get("KillAura.Movement Fix");
        if (oldKillAuraMoveFix != null
                && (oldKillAuraMoveFix.equalsIgnoreCase("true")
                || oldKillAuraMoveFix.equalsIgnoreCase("false"))) {
            values.put("KillAura.Movement Fix",
                    oldKillAuraMoveFix.equalsIgnoreCase("true") ? "1" : "0");
        }

        // AntiKnockback's knockback multipliers used to be stored as 0..100 percentages.
        migrateAntiKnockbackScale(values, "AntiKnockback.Horizontal");
        migrateAntiKnockbackScale(values, "AntiKnockback.Vertical");
        migrateScaffoldTower(values);
        // KillAura and LeftClicker dropped Jitter for a CPS range; a single CPS becomes a
        // range whose ends meet, which is the old no-jitter behaviour.
        migrateSingleToRange(values, "KillAura.CPS");
        migrateSingleToRange(values, "LeftClicker.CPS");
        // Scaffold's Place Delay became a tick range; the old single value was a fixed delay.
        String oldPlaceDelay = values.get("Scaffold.Place Delay");
        if (oldPlaceDelay != null && !values.containsKey("Scaffold.Delay")) {
            values.put("Scaffold.Delay", oldPlaceDelay + "-" + oldPlaceDelay);
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

        if (hud != null) loadHud(values, hud);
    }
}