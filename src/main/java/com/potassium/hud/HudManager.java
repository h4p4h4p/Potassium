package com.potassium.hud;

import com.potassium.Potassium;
import com.potassium.module.HUD;
import com.potassium.module.Module;
import com.potassium.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns the HUD elements and the live values the text tokens read.
 *
 * Watermark and module list keep their HUD module toggles; every element has its own
 * position, which on-canvas editing moves and the config persists. Text elements are a
 * dynamic list so the user can place any number of lines.
 */
public class HudManager {

    /** The speed is averaged over a short window of real time, not a fixed tick count. */
    private static final long BPS_WINDOW_NANOS = 250_000_000L;
    /** A tick that arrives later than this means the game was paused or stalled. */
    private static final long BPS_MAX_TICK_NANOS = 500_000_000L;
    /** Enough for the Timer module's maximum of ten times vanilla, plus slack. */
    private static final int BPS_MAX_SAMPLES = 256;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final WatermarkHudElement watermark = new WatermarkHudElement();
    private final ModuleListHudElement moduleList = new ModuleListHudElement();
    private final KeybindViewerHudElement keybindViewer = new KeybindViewerHudElement(watermark);
    private final FpsHudElement fps = new FpsHudElement();
    private final BpsHudElement bps = new BpsHudElement(this);
    private final TargetHudElement targetHud = new TargetHudElement();
    private final BlinkTextHudElement blinkText = new BlinkTextHudElement();
    private final List<TextHudElement> texts = new ArrayList<>();
    private final List<HudElement> elements = new ArrayList<>();

    private final double[] sampleDistances = new double[BPS_MAX_SAMPLES];
    private final long[] sampleDurations = new long[BPS_MAX_SAMPLES];
    private final long[] sampleTimes = new long[BPS_MAX_SAMPLES];
    private int sampleIndex;
    private int sampleCount;
    private double blocksPerSecond;
    private double lastX;
    private double lastZ;
    private long lastSampleTime;
    private boolean hasLast;
    private int textCounter;

    public HudManager() {
        elements.add(watermark);
        elements.add(moduleList);
        elements.add(keybindViewer);
        elements.add(fps);
        elements.add(bps);
        elements.add(targetHud);
        elements.add(blinkText);
        MinecraftForge.EVENT_BUS.register(this);
    }

    public WatermarkHudElement getWatermark() {
        return watermark;
    }

    public ModuleListHudElement getModuleList() {
        return moduleList;
    }

    public KeybindViewerHudElement getKeybindViewer() {
        return keybindViewer;
    }

    public FpsHudElement getFps() {
        return fps;
    }

    public BpsHudElement getBps() {
        return bps;
    }

    public TargetHudElement getTargetHud() {
        return targetHud;
    }

    public BlinkTextHudElement getBlinkText() {
        return blinkText;
    }

    public List<TextHudElement> getTexts() {
        return texts;
    }

    public List<HudElement> getElements() {
        return elements;
    }

    public double getBlocksPerSecond() {
        return blocksPerSecond;
    }

    public void clearTexts() {
        for (TextHudElement text : texts) elements.remove(text);
        texts.clear();
        textCounter = 0;
    }

    public TextHudElement addText(String value, int x, int y) {
        TextHudElement element = new TextHudElement(this, "text" + textCounter++, value, x, y);
        texts.add(element);
        elements.add(element);
        return element;
    }

    public void removeText(TextHudElement element) {
        texts.remove(element);
        elements.remove(element);
    }

    public void render() {
        // Shadows first, then all text, so one element's shadow can never cover another
        // element's text when the user places two HUD rows close together.
        for (HudElement element : elements) {
            if (element.isVisible()) drawShadow(element);
        }
        for (HudElement element : elements) {
            if (element.isVisible()) element.render(element.getX(), element.getY());
        }
    }

    /** Called while editing, which draws hidden elements too so they can be positioned. */
    public void renderEditable(HudElement element) {
        drawShadow(element);
        element.render(element.getX(), element.getY());
    }

    /**
     * Puts the configured plate behind the element's text: the soft shadow plate by default,
     * or the Regular flat panel, which covers only the text itself.
     */
    private void drawShadow(HudElement element) {
        int w = element.getWidth();
        int h = element.getHeight();
        if (w <= 0 || h <= 0) return;

        HUD hud = hudModule();
        if (hud != null && !hud.usesShadowPanels()) {
            RenderUtil.drawHudPanel(element.getX(), element.getY(), w, h);
            return;
        }
        RenderUtil.drawHudShadow(element.getX(), element.getY(), w, h);
    }

    private HUD hudModule() {
        if (Potassium.INSTANCE == null || Potassium.INSTANCE.moduleManager == null) return null;
        Module module = Potassium.INSTANCE.moduleManager.getModule("HUD");
        return module instanceof HUD ? (HUD) module : null;
    }

    /** Replaces the supported tokens with their current values. */
    public String resolve(String template) {
        if (template == null || template.isEmpty()) return "";
        String out = template;
        out = out.replace("%fps%", Integer.toString(Minecraft.getDebugFPS()));
        out = out.replace("%bps%", String.format(Locale.ROOT, "%.2f", blocksPerSecond));
        out = out.replace("%server%", serverName());
        out = out.replace("%player%", playerName());
        out = out.replace("%version%", Potassium.VERSION);
        return out;
    }

    private String serverName() {
        return mc.getCurrentServerData() == null ? "Singleplayer" : mc.getCurrentServerData().serverIP;
    }

    private String playerName() {
        return mc.thePlayer != null ? mc.thePlayer.getName() : mc.getSession().getUsername();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            resetBps();
            hasLast = false;
            return;
        }

        long now = System.nanoTime();
        if (!hasLast) {
            lastX = player.posX;
            lastZ = player.posZ;
            lastSampleTime = now;
            hasLast = true;
            return;
        }

        double distance = Math.hypot(player.posX - lastX, player.posZ - lastZ);
        long duration = now - lastSampleTime;
        lastX = player.posX;
        lastZ = player.posZ;
        lastSampleTime = now;

        // A teleport, dimension change or stalled game is not walking; start over rather
        // than let one huge step dominate the reading.
        if (duration <= 0L || duration > BPS_MAX_TICK_NANOS || distance > 10.0D) {
            resetBps();
            return;
        }

        sampleDistances[sampleIndex] = distance;
        sampleDurations[sampleIndex] = duration;
        sampleTimes[sampleIndex] = now;
        sampleIndex = (sampleIndex + 1) % BPS_MAX_SAMPLES;
        if (sampleCount < BPS_MAX_SAMPLES) sampleCount++;
        updateBlocksPerSecond(now);
    }

    /**
     * Divides the distance covered by the time it actually took. Counting ticks instead
     * would under-report whenever the Timer module raises the tick rate, and a stalled
     * frame would count as if no time had passed at all.
     */
    private void updateBlocksPerSecond(long now) {
        int oldest = (sampleIndex - sampleCount + BPS_MAX_SAMPLES) % BPS_MAX_SAMPLES;
        double distance = 0.0D;
        long windowStart = 0L;
        boolean found = false;

        for (int i = 0; i < sampleCount; i++) {
            int index = (oldest + i) % BPS_MAX_SAMPLES;
            if (now - sampleTimes[index] > BPS_WINDOW_NANOS) continue;
            if (!found) {
                found = true;
                windowStart = sampleTimes[index] - sampleDurations[index];
            }
            distance += sampleDistances[index];
        }

        if (!found) {
            blocksPerSecond = 0.0D;
            return;
        }

        long elapsed = now - windowStart;
        blocksPerSecond = elapsed > 0L ? distance * 1.0E9D / elapsed : 0.0D;
    }

    private void resetBps() {
        sampleIndex = 0;
        sampleCount = 0;
        blocksPerSecond = 0.0D;
    }
}
