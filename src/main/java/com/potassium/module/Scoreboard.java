package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.StringSetting;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the vanilla sidebar with a client-rendered copy so its text and score numbers can be
 * changed without touching the server's scoreboard data.
 */
public class Scoreboard extends Module {

    private static final String REPLACE_GROUP = "Replace Text";
    private static final int MAX_ROWS = 15;
    private static final int ROW_BG = 0x50000000;
    private static final int HEADER_BG = 0x60000000;
    private static final int TEXT_COLOR = 553648127;

    private final BooleanSetting renderSetting = new BooleanSetting("Render Scoreboard", true);
    private final BooleanSetting showNumbersSetting = new BooleanSetting("Show Score Numbers", true);
    private final StringSetting replaceTextSetting =
            new StringSetting("Text", "").withPlaceholder("text to replace");
    private final StringSetting replacementSetting =
            new StringSetting("Replacement", "").withPlaceholder("replacement");

    private final Minecraft mc = Minecraft.getMinecraft();

    /** The value another mod may have set before this module hid the vanilla sidebar. */
    private boolean previousRenderObjective = true;

    public Scoreboard() {
        super("Scoreboard", Category.RENDER);
        replaceTextSetting.setGroup(REPLACE_GROUP);
        replacementSetting.setGroup(REPLACE_GROUP);
        settings.add(renderSetting);
        settings.add(showNumbersSetting);
        settings.add(replaceTextSetting);
        settings.add(replacementSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRender(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (!isEnabled() || !renderSetting.getValue()) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;

        ScaledResolution resolution = event.resolution;
        FontRenderer font = mc.fontRendererObj;

        net.minecraft.scoreboard.Scoreboard scoreboard = mc.theWorld.getScoreboard();
        ScoreObjective objective = sidebarObjective(scoreboard);
        if (objective == null) return;

        List<Score> scores = visibleScores(scoreboard, objective);
        if (scores.isEmpty()) return;

        String title = replace(objective.getDisplayName());
        int width = font.getStringWidth(title);

        List<String> lines = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        for (Score score : scores) {
            String line = replace(ScorePlayerTeam.formatPlayerName(
                    scoreboard.getPlayersTeam(score.getPlayerName()), score.getPlayerName()));
            String number = EnumChatFormatting.RED.toString() + score.getScorePoints();
            lines.add(line);
            numbers.add(number);
            String measured = showNumbersSetting.getValue() ? line + ": " + number : line;
            width = Math.max(width, font.getStringWidth(measured));
        }

        int height = resolution.getScaledHeight();
        int screenWidth = resolution.getScaledWidth();
        int rowsHeight = scores.size() * font.FONT_HEIGHT;
        int bottom = height / 2 + rowsHeight / 3;
        int left = screenWidth - width - 3;
        int right = screenWidth - 1;
        int backgroundWidth = right - left + 2;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        for (int i = 0; i < scores.size(); i++) {
            int row = i + 1;
            int y = bottom - row * font.FONT_HEIGHT;

            RenderUtil.drawRect(left - 2, y, backgroundWidth, font.FONT_HEIGHT, ROW_BG);
            font.drawString(lines.get(i), left, y, TEXT_COLOR);
            if (showNumbersSetting.getValue()) {
                String number = numbers.get(i);
                font.drawString(number, right - font.getStringWidth(number), y, TEXT_COLOR);
            }

            if (row == scores.size()) {
                RenderUtil.drawRect(left - 2, y - font.FONT_HEIGHT - 1,
                        backgroundWidth, font.FONT_HEIGHT, HEADER_BG);
                RenderUtil.drawRect(left - 2, y - 1, backgroundWidth, 1, ROW_BG);
                font.drawString(title, left + (width - font.getStringWidth(title)) / 2,
                        y - font.FONT_HEIGHT, TEXT_COLOR);
            }
        }
    }

    private ScoreObjective sidebarObjective(net.minecraft.scoreboard.Scoreboard scoreboard) {
        ScorePlayerTeam team = scoreboard.getPlayersTeam(mc.thePlayer.getName());
        if (team != null) {
            int color = team.getChatFormat().getColorIndex();
            if (color >= 0) {
                ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(3 + color);
                if (objective != null) return objective;
            }
        }
        return scoreboard.getObjectiveInDisplaySlot(1);
    }

    private List<Score> visibleScores(net.minecraft.scoreboard.Scoreboard scoreboard,
                                      ScoreObjective objective) {
        List<Score> visible = new ArrayList<>();
        for (Score score : scoreboard.getSortedScores(objective)) {
            String name = score.getPlayerName();
            if (name != null && !name.startsWith("#")) visible.add(score);
        }
        if (visible.size() > MAX_ROWS) {
            return new ArrayList<>(visible.subList(visible.size() - MAX_ROWS, visible.size()));
        }
        return visible;
    }

    /** Applies the optional literal text replacement to the title and each row. */
    private String replace(String text) {
        String from = replaceTextSetting.getValue();
        if (text == null || from == null || from.isEmpty()) return text == null ? "" : text;
        String to = replacementSetting.getValue();
        return text.replace(from, to == null ? "" : to);
    }

    @Override
    public void onEnable() {
        previousRenderObjective = GuiIngameForge.renderObjective;
        GuiIngameForge.renderObjective = false;
    }

    @Override
    public void onDisable() {
        GuiIngameForge.renderObjective = previousRenderObjective;
    }

}
