package com.potassium.command;

import com.potassium.Potassium;
import com.potassium.module.Module;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

public class BindCommand extends ChatCommand {

    public BindCommand() {
        super("bind", "key");
    }

    @Override
    public String getUsage() {
        return "%bind <module> <key> [toggle|hold] | %bind <module> none";
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 2 || args.length > 3) {
            CommandManager.sendMessage("\u00a7cUsage: " + getUsage());
            return;
        }
        if (Potassium.INSTANCE == null) return;
        Module module = Potassium.INSTANCE.moduleManager.getModule(args[0]);
        if (module == null) {
            CommandManager.sendMessage("\u00a7cModule '\u00a7f" + args[0] + "\u00a7c' not found. Type \u00a7f%help");
            return;
        }

        boolean hold = false;
        if (args.length == 3) {
            String mode = args[2].toLowerCase();
            if (!mode.equals("toggle") && !mode.equals("hold")) {
                CommandManager.sendMessage("\u00a7cUsage: " + getUsage());
                return;
            }
            hold = mode.equals("hold");
        }

        String keyName = args[1];
        if (keyName.equalsIgnoreCase("none") || keyName.equalsIgnoreCase("unbind")
                || keyName.equalsIgnoreCase("delete")) {
            module.setKey(-1);
            module.setHoldBind(false);
            CommandManager.sendMessage("\u00a7a" + module.getName() + "\u00a77 unbound");
            Potassium.scheduleSave();
            return;
        }

        // LWJGL key names are uppercase, and getKeyIndex is case-sensitive.
        int code = Keyboard.getKeyIndex(keyName.toUpperCase(Locale.ROOT));
        if (code == Keyboard.KEY_NONE) {
            try {
                code = Integer.parseInt(keyName);
            } catch (NumberFormatException ignored) {
                code = Keyboard.KEY_NONE;
            }
        }
        if (code == Keyboard.KEY_NONE) {
            CommandManager.sendMessage("\u00a7cInvalid key: " + args[1]);
            return;
        }
        module.setKey(code);
        module.setHoldBind(hold);
        CommandManager.sendMessage("\u00a7a" + module.getName() + "\u00a77 bound to \u00a7a["
                + Keyboard.getKeyName(code) + "]\u00a77 (" + (hold ? "hold" : "toggle") + ")");
        Potassium.scheduleSave();
    }
}