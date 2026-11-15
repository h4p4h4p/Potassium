package com.potassium.command;

import com.potassium.Potassium;
import com.potassium.gui.ClickGUI;
import net.minecraft.client.Minecraft;

public class ClickGuiCommand extends ChatCommand {

    public ClickGuiCommand() {
        super("clickgui", "gui", "config");
    }

    @Override
    public String getUsage() {
        return "%clickgui";
    }

    @Override
    public void execute(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.currentScreen instanceof ClickGUI) return;
            if (Potassium.INSTANCE == null || Potassium.INSTANCE.clickGui == null) return;
            mc.displayGuiScreen(Potassium.INSTANCE.clickGui);
        });
    }
}