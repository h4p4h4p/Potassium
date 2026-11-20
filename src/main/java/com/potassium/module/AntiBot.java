package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;

import java.util.Locale;
import java.util.UUID;

/**
 * Identifies NPCs and fake players so target and ESP modules can skip them. Each check can be
 * turned off separately because some servers hide real players from the tab list or use
 * unusual names and UUIDs.
 */
public class AntiBot extends Module {

    private static AntiBot instance;

    private final BooleanSetting tabListSetting = new BooleanSetting("Tab List", true);
    private final BooleanSetting uuidSetting = new BooleanSetting("UUID Check", true);
    private final BooleanSetting nameSetting = new BooleanSetting("Name Check", true);

    private final Minecraft mc = Minecraft.getMinecraft();

    public AntiBot() {
        super("AntiBot", Category.COMBAT);
        settings.add(tabListSetting);
        settings.add(uuidSetting);
        settings.add(nameSetting);
        instance = this;
    }

    /** True only while AntiBot is enabled and the entity looks like a bot. */
    public static boolean isBot(Entity entity) {
        AntiBot module = instance;
        return module != null && module.isEnabled() && module.isBotPlayer(entity);
    }

    private boolean isBotPlayer(Entity entity) {
        if (!(entity instanceof EntityPlayer)) return false;

        EntityPlayer player = (EntityPlayer) entity;
        if (player == mc.thePlayer) return false;

        UUID uuid = player.getUniqueID();
        if (uuidSetting.getValue()) {
            if (uuid == null) return true;
            if (uuid.version() == 2) return true;
            if (player.getGameProfile() == null || player.getGameProfile().getId() == null) return true;
        }

        if (nameSetting.getValue() && isBotName(player.getName())) return true;

        if (tabListSetting.getValue() && mc.getNetHandler() != null) {
            if (uuid != null && mc.getNetHandler().getPlayerInfo(uuid) == null) return true;
        }

        return false;
    }

    private boolean isBotName(String name) {
        if (name == null) return true;

        String clean = EnumChatFormatting.getTextWithoutFormattingCodes(name);
        if (clean == null) return true;
        clean = clean.trim().toLowerCase(Locale.ROOT);

        return clean.isEmpty()
                || clean.equals("npc")
                || clean.startsWith("npc ")
                || clean.startsWith("npc_")
                || clean.startsWith("npc-")
                || clean.contains("[npc]")
                || clean.startsWith("citizens")
                || clean.equals("bot")
                || clean.startsWith("bot ")
                || clean.startsWith("bot_")
                || clean.endsWith(" bot");
    }

    @Override
    public String getTag() {
        int checks = 0;
        if (tabListSetting.getValue()) checks++;
        if (uuidSetting.getValue()) checks++;
        if (nameSetting.getValue()) checks++;
        return checks + (checks == 1 ? " check" : " checks");
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}
