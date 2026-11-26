package com.potassium.command;

import com.potassium.module.Diagnostics;

import java.io.File;

/** Prints, resets or exports the combat diagnostics session. */
public class DiagnosticsCommand extends ChatCommand {

    public DiagnosticsCommand() {
        super("diag", "diagnostics");
    }

    @Override
    public String getUsage() {
        return "%diag | %diag reset | %diag export";
    }

    @Override
    public void execute(String[] args) {
        Diagnostics diagnostics = Diagnostics.instance();
        if (diagnostics == null) {
            CommandManager.sendMessage("\u00a7cDiagnostics is not available.");
            return;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            diagnostics.reset();
            CommandManager.sendMessage("\u00a7aDiagnostics reset.");
            return;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("export")) {
            File file = diagnostics.export();
            if (file == null) {
                CommandManager.sendMessage("\u00a7cCould not write the diagnostics file.");
            } else {
                CommandManager.sendMessage("\u00a7aSaved diagnostics to \u00a7f" + file.getPath());
            }
            return;
        }

        for (String line : diagnostics.report()) {
            CommandManager.sendMessage(line);
        }
    }
}
