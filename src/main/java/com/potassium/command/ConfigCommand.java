package com.potassium.command;

import com.potassium.Potassium;
import com.potassium.module.LocalConfigs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;

/**
 * Lists and loads the files the Configurations panel also reads.
 *
 * <p>Both entry points share {@link LocalConfigs#configFiles()}, so a file dropped into the
 * folder is available from chat and from the dropdown without two different rules about what
 * counts as a config. Names may be given with or without the .cfg suffix, and matching is
 * case-insensitive like the file picker's.</p>
 */
public class ConfigCommand extends ChatCommand {

    public ConfigCommand() {
        super("config", "cfg", "configs");
    }

    @Override
    public String getUsage() {
        return "%config list | %config <name>";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            list();
            return;
        }
        // Join the arguments so a file name containing spaces can still be typed in chat.
        String requested = String.join(" ", args).trim();
        if (requested.isEmpty()) {
            CommandManager.sendMessage("\u00a7cUsage: " + getUsage());
            return;
        }
        String fileName = requested.toLowerCase().endsWith(".cfg")
                ? requested : requested + ".cfg";
        File found = null;
        for (File file : LocalConfigs.configFiles()) {
            if (file.getName().equalsIgnoreCase(fileName)) {
                found = file;
                break;
            }
        }
        if (found == null) {
            CommandManager.sendMessage("\u00a7cConfig '\u00a7f" + requested
                    + "\u00a7c' was not found. Type \u00a7f%config list");
            return;
        }

        if (Potassium.INSTANCE == null || Potassium.INSTANCE.configManager == null
                || Potassium.INSTANCE.moduleManager == null) {
            return;
        }

        String text;
        try {
            text = new String(Files.readAllBytes(found.toPath()), Charset.forName("UTF-8"));
        } catch (IOException e) {
            CommandManager.sendMessage("\u00a7cCould not read \u00a7f" + found.getName());
            return;
        }

        if (Potassium.INSTANCE.configManager.importText(text,
                Potassium.INSTANCE.moduleManager, Potassium.INSTANCE.hudManager)) {
            CommandManager.sendMessage("\u00a7aLoaded config \u00a7f" + found.getName()
                    + "\u00a77 (previous config saved as potassium.cfg.bak)");
        } else {
            CommandManager.sendMessage("\u00a7cCould not apply \u00a7f" + found.getName());
        }
    }

    private void list() {
        List<File> files = LocalConfigs.configFiles();
        if (files.isEmpty()) {
            CommandManager.sendMessage("\u00a77No .cfg files in \u00a7f"
                    + LocalConfigs.configDirectory().getPath());
            return;
        }
        CommandManager.sendMessage("\u00a7eConfigs \u00a77(" + files.size() + "):");
        for (File file : files) {
            CommandManager.sendMessage("\u00a77- \u00a7f" + file.getName());
        }
    }
}
