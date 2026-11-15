package com.potassium.command;

import com.potassium.Potassium;
import com.potassium.module.Module;

public class ToggleCommand extends ChatCommand {

    public ToggleCommand() {
        super("toggle", "t");
    }

    @Override
    public String getUsage() {
        return "%toggle <module>";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            CommandManager.sendMessage("\u00a7cUsage: " + getUsage());
            return;
        }
        if (Potassium.INSTANCE == null) return;
        Module module = Potassium.INSTANCE.moduleManager.getModule(args[0]);
        if (module == null) {
            CommandManager.sendMessage("\u00a7cModule '\u00a7f" + args[0] + "\u00a7c' not found. Type \u00a7f%help");
            return;
        }
        module.toggle();
        CommandManager.sendMessage("\u00a7a" + module.getName() + "\u00a77 toggled \u00a7a" + (module.isEnabled() ? "on" : "off"));
    }
}