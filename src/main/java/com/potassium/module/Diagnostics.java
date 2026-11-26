package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.util.RotationUtil;
import com.potassium.util.SilentLook;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures the parts of combat that are otherwise invisible: whether silent aim moves on
 * the mouse grid, how often a Blink pulse was up when damage arrived, and whether the server
 * corrected the player's position.
 *
 * <p>Off-grid and repeated rotation deltas are the two patterns a GCD-based Aim check looks
 * for, so the numbers here map directly to the checks that flag the aura. The report is
 * available in chat and can be written next to the config for comparison between settings.</p>
 */
public class Diagnostics extends Module {

    private static final String HANDLER_NAME = "potassium_diagnostics";
    private static Diagnostics instance;

    private final BooleanSetting rotationsSetting = new BooleanSetting("Rotations", true);
    private final BooleanSetting blinkSetting = new BooleanSetting("Blink", true);
    private final BooleanSetting damageSetting = new BooleanSetting("Damage", true);
    private final BooleanSetting setbacksSetting = new BooleanSetting("Setbacks", true);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final AtomicInteger setbacks = new AtomicInteger();

    private int trackedTicks;
    private int rotationTicks;
    private int rotationSamples;
    private int offGridSamples;
    private int repeatedDeltas;
    private float lastSentYaw;
    private float lastSentPitch;
    private float lastDeltaYaw;
    private float lastDeltaPitch;
    private boolean hasSent;
    private double cameraGapSum;
    private float maxCameraGap;

    private int blinkModeTicks;
    private int blinkTicks;
    private int blinkPulses;
    private boolean lastBlinkActive;
    private float lastHealth = -1.0F;
    private int blinkHits;
    private int gapHits;
    private float blinkDamage;
    private float gapDamage;
    private int attacks;

    private Channel boundChannel;

    public Diagnostics() {
        super("Diagnostics", Category.COMBAT);
        settings.add(rotationsSetting);
        settings.add(blinkSetting);
        settings.add(damageSetting);
        settings.add(setbacksSetting);
        instance = this;
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static Diagnostics instance() {
        return instance;
    }

    /** Called by KillAura and LeftClicker for every attack they send. */
    public static void noteAttack() {
        Diagnostics diagnostics = instance;
        if (diagnostics != null && diagnostics.isEnabled()) diagnostics.attacks++;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        arm();
        if (!isEnabled()) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            hasSent = false;
            lastHealth = -1.0F;
            return;
        }
        trackedTicks++;

        if (damageSetting.getValue()) {
            float health = player.getHealth();
            if (lastHealth >= 0.0F && health < lastHealth - 1.0E-4F) {
                float damage = lastHealth - health;
                if (isBlinkActive()) {
                    blinkDamage += damage;
                    blinkHits++;
                } else {
                    gapDamage += damage;
                    gapHits++;
                }
            }
            lastHealth = health;
        }

        KillAura aura = KillAura.instance();
        if (blinkSetting.getValue() && aura != null && aura.isEnabled()
                && aura.isBlinkModeSelected()) {
            blinkModeTicks++;
            boolean active = aura.isBlinkBlockActive();
            if (active) {
                blinkTicks++;
                if (!lastBlinkActive) blinkPulses++;
            }
            lastBlinkActive = active;
        } else {
            lastBlinkActive = false;
        }

        if (rotationsSetting.getValue() && SilentLook.sentAimTick() == player.ticksExisted) {
            trackRotation(player);
        } else {
            hasSent = false;
        }
    }

    private void trackRotation(EntityPlayerSP player) {
        float yaw = SilentLook.sentAimYaw();
        float pitch = SilentLook.sentAimPitch();
        float cameraYaw = SilentLook.cameraYaw();
        float cameraPitch = SilentLook.cameraPitch();

        float gap = Math.max(Math.abs(MathHelper.wrapAngleTo180_float(yaw - cameraYaw)),
                Math.abs(pitch - cameraPitch));
        cameraGapSum += gap;
        maxCameraGap = Math.max(maxCameraGap, gap);
        rotationTicks++;

        if (hasSent) {
            float deltaYaw = MathHelper.wrapAngleTo180_float(yaw - lastSentYaw);
            float deltaPitch = pitch - lastSentPitch;
            rotationSamples++;

            double step = RotationUtil.mouseStep();
            if (step > 1.0E-4D) {
                double yawError = Math.abs(deltaYaw / step - Math.round(deltaYaw / step));
                double pitchError = Math.abs(deltaPitch / step - Math.round(deltaPitch / step));
                if (yawError > 0.02D || pitchError > 0.02D) offGridSamples++;
            }
            // A stationary aim repeats a zero delta; that is not the constant-rotation
            // pattern an Aim check looks for, so only count real movement.
            boolean moved = Math.abs(deltaYaw) > 1.0E-4F || Math.abs(deltaPitch) > 1.0E-4F;
            if (moved && Math.abs(deltaYaw - lastDeltaYaw) < 1.0E-4F
                    && Math.abs(deltaPitch - lastDeltaPitch) < 1.0E-4F) {
                repeatedDeltas++;
            }
            lastDeltaYaw = deltaYaw;
            lastDeltaPitch = deltaPitch;
        } else {
            hasSent = true;
        }

        lastSentYaw = yaw;
        lastSentPitch = pitch;
    }

    private boolean isBlinkActive() {
        KillAura aura = KillAura.instance();
        return aura != null && aura.isBlinkBlockActive();
    }

    /** A compact multi-line report; the command prints each line separately. */
    public String[] report() {
        double offGridPercent = rotationSamples == 0 ? 0.0D
                : 100.0D * offGridSamples / rotationSamples;
        double coverage = blinkModeTicks == 0 ? 0.0D
                : 100.0D * blinkTicks / blinkModeTicks;
        double averageGap = rotationTicks == 0 ? 0.0D : cameraGapSum / rotationTicks;
        int totalHits = blinkHits + gapHits;
        float totalDamage = blinkDamage + gapDamage;

        return new String[]{
                "\u00a7eDiagnostics \u00a77(" + trackedTicks + " ticks, "
                        + String.format(Locale.ROOT, "%.1f", trackedTicks / 20.0D) + "s)"
                        + (isEnabled() ? "" : " \u00a78[off]"),
                "\u00a77Rotations: \u00a7f" + rotationSamples + " samples, \u00a7c"
                        + offGridSamples + " off-grid \u00a77("
                        + String.format(Locale.ROOT, "%.1f", offGridPercent)
                        + "%), \u00a7c" + repeatedDeltas + " repeats",
                "\u00a77Camera gap: \u00a7f" + String.format(Locale.ROOT, "%.1f", averageGap)
                        + "\u00b0 avg, \u00a7f" + String.format(Locale.ROOT, "%.1f", maxCameraGap)
                        + "\u00b0 max",
                "\u00a77Blink: \u00a7f" + blinkPulses + " pulses, \u00a7f"
                        + String.format(Locale.ROOT, "%.1f", coverage) + "% coverage",
                "\u00a77Damage: \u00a7f" + String.format(Locale.ROOT, "%.1f", totalDamage)
                        + " total \u00a77(block \u00a7a"
                        + String.format(Locale.ROOT, "%.1f", blinkDamage) + " \u00a77/ \u00a7c"
                        + String.format(Locale.ROOT, "%.1f", gapDamage) + "\u00a77, "
                        + totalHits + " hits)",
                "\u00a77Attacks: \u00a7f" + attacks + "  \u00a77Setbacks: \u00a7c"
                        + setbacks.get()
        };
    }

    /** Saves the current report next to the main config and returns the file, or null. */
    public File export() {
        try {
            File directory = new File(mc.mcDataDir, "config/potassium");
            if (!directory.exists() && !directory.mkdirs()) return null;
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                    .format(new Date());
            File file = new File(directory, "diagnostics-" + stamp + ".txt");
            StringBuilder text = new StringBuilder();
            for (String line : report()) text.append(line).append('\n');
            Files.write(file.toPath(), text.toString().getBytes(Charset.forName("UTF-8")));
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    public void reset() {
        trackedTicks = 0;
        rotationTicks = 0;
        rotationSamples = 0;
        offGridSamples = 0;
        repeatedDeltas = 0;
        cameraGapSum = 0.0D;
        maxCameraGap = 0.0F;
        blinkModeTicks = 0;
        blinkTicks = 0;
        blinkPulses = 0;
        lastBlinkActive = false;
        blinkHits = 0;
        gapHits = 0;
        blinkDamage = 0.0F;
        gapDamage = 0.0F;
        attacks = 0;
        setbacks.set(0);
        hasSent = false;
        lastHealth = -1.0F;
    }

    /** Registers the inbound observer once per connection, like Backtrack's handler. */
    private void arm() {
        if (!setbacksSetting.getValue()) return;
        if (mc.getNetHandler() == null) return;

        NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == null) return;
        Channel channel = manager.channel();
        if (channel == null || !channel.isActive()) return;

        if (boundChannel != channel) {
            Channel oldChannel = boundChannel;
            if (oldChannel != null && oldChannel.isActive()
                    && oldChannel.pipeline().get(HANDLER_NAME) != null) {
                oldChannel.eventLoop().execute(() -> {
                    try {
                        oldChannel.pipeline().remove(HANDLER_NAME);
                    } catch (Exception ignored) {
                    }
                });
            }
            boundChannel = channel;
        }

        if (channel.pipeline().get(HANDLER_NAME) != null) return;
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(HANDLER_NAME) == null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER_NAME,
                            new SetbackHandler());
                }
            } catch (Exception ignored) {
            }
        });
    }

    private final class SetbackHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof S08PacketPlayerPosLook) setbacks.incrementAndGet();
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public String getTag() {
        return Integer.toString(offGridSamples);
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
    }
}
