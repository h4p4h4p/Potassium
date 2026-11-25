package com.potassium.module;

import com.potassium.settings.ModeSetting;
import com.potassium.settings.Setting;
import com.potassium.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;

/**
 * Breaks blocks faster than vanilla by rewriting the client-side dig state in
 * PlayerControllerMP.
 *
 * Vanilla re-arms blockHitDelay to 5 after every completed break, and while it is positive
 * onPlayerDamageBlock just decrements it, so chained blocks have a five tick pause between
 * them. Clearing it lets the next block start damage on the very next tick, which is all
 * Legit does.
 *
 * Damage adds a fixed amount of progress per tick on top of vanilla's own hardness-based
 * increment. Tick instead spreads the whole progress bar across a configured number of
 * ticks: vanilla adds the block's relative hardness itself, so this only has to top the
 * accumulator up to the value that puts the break on the chosen tick. Both stop short of
 * 1.0 because vanilla must be the one to finish the break and send STOP_DESTROY_BLOCK.
 *
 * The server still sees the C07 START/STOP pair at the timing produced here. NoCheatPlus
 * compares that elapsed time against the breaking duration of the block's material, so
 * small Tick values flag on slow blocks no matter how the client fills the bar.
 *
 * Both fields are private, so they are reached through ReflectionHelper with the MCP
 * and SRG names.
 */
public class FastBreak extends Module {

    public static final String MODE_LEGIT = "Legit";
    public static final String MODE_DAMAGE = "Damage";
    public static final String MODE_TICK = "Tick";

    private static final Logger LOGGER = LogManager.getLogger("Potassium");

    /** Leave the last sliver to vanilla so the break is still completed by its own code. */
    private static final float MAX_ACCUMULATOR = 0.99F;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_LEGIT, MODE_DAMAGE, MODE_TICK}, 0);
    private final SliderSetting damageSetting = new SliderSetting("Break Damage", 0.3, 0.05, 1.0, 0.05);
    private final SliderSetting ticksSetting = new SliderSetting("Ticks", 3, 1, 10, 1);

    private final Minecraft mc = Minecraft.getMinecraft();

    private Field hitDelayField;
    private Field damageField;
    private boolean fieldsUnavailable;

    /** Damage ticks already applied to the block being dug, for the Tick mode. */
    private int damageTicks;

    public FastBreak() {
        super("FastBreak", Category.WORLD);
        settings.add(modeSetting);
        settings.add(damageSetting);
        settings.add(ticksSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * Runs before the player's own tick, so the delay is already 0 when the attack key
     * reaches PlayerControllerMP#onPlayerDamageBlock later in the tick.
     */
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled() || !resolveFields()) return;

        PlayerControllerMP controller = mc.playerController;
        if (controller == null) return;

        String mode = modeSetting.getValue();
        try {
            hitDelayField.setInt(controller, 0);

            // Vanilla zeroes the accumulator whenever a break starts or finishes, so no
            // progress means nothing is being dug. A dig already underway that we did not
            // see start (module toggled on mid-break) has had one damage tick applied.
            float progress = damageField.getFloat(controller);
            if (progress <= 0.0F) {
                damageTicks = 0;
            } else if (damageTicks == 0) {
                damageTicks = 1;
            }

            if (MODE_DAMAGE.equals(mode)) {
                if (progress <= 0.0F) return;
                damageField.setFloat(controller,
                        Math.min(progress + (float) damageSetting.getValue(), MAX_ACCUMULATOR));
                return;
            }
            if (!MODE_TICK.equals(mode)) return;

            // With no progress the dig starts later this tick, so nothing is applied yet.
            if (progress <= 0.0F && !diggingBlock()) return;
            applyTickProgress(controller, progress);
        } catch (IllegalAccessException ignored) {
        }
    }

    /**
     * Fills the bar so the block gives way on the configured tick. The value written is what
     * the accumulator must hold before this tick's damage, so the break lands after
     * {@code Ticks} damage ticks counting from the one that starts the dig.
     */
    private void applyTickProgress(PlayerControllerMP controller, float progress)
            throws IllegalAccessException {
        int ticks = Math.max(1, (int) Math.round(ticksSetting.getValue()));
        float target;
        if (ticks <= 1) {
            target = MAX_ACCUMULATOR;
        } else {
            float needed = MAX_ACCUMULATOR * damageTicks / (ticks - 1);
            target = Math.min(MAX_ACCUMULATOR, needed);
        }
        if (target > progress) damageField.setFloat(controller, target);
        damageTicks++;
    }

    /** Whether the attack key is held on a block, i.e. a dig is about to start this tick. */
    private boolean diggingBlock() {
        if (mc.currentScreen != null) return false;
        if (mc.gameSettings == null || !mc.gameSettings.keyBindAttack.isKeyDown()) return false;
        MovingObjectPosition hit = mc.objectMouseOver;
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
    }

    private boolean resolveFields() {
        if (hitDelayField != null) return true;
        if (fieldsUnavailable) return false;

        try {
            Field hitDelay = ReflectionHelper.findField(PlayerControllerMP.class,
                    "blockHitDelay", "field_78781_i");
            Field damage = ReflectionHelper.findField(PlayerControllerMP.class,
                    "curBlockDamageMP", "field_78770_f");
            hitDelay.setAccessible(true);
            damage.setAccessible(true);
            hitDelayField = hitDelay;
            damageField = damage;
            return true;
        } catch (Exception e) {
            // Retrying every tick would only repeat the failure, so give up and say why.
            fieldsUnavailable = true;
            LOGGER.warn("FastBreak could not access PlayerControllerMP fields; module is inactive", e);
            return false;
        }
    }

    @Override
    public boolean isSettingVisible(Setting setting) {
        if (setting == damageSetting) return MODE_DAMAGE.equals(modeSetting.getValue());
        if (setting == ticksSetting) return MODE_TICK.equals(modeSetting.getValue());
        return super.isSettingVisible(setting);
    }

    @Override
    public String getTag() {
        String mode = modeSetting.getValue();
        return mode == null ? MODE_LEGIT : mode;
    }

    @Override
    public void onEnable() {
        damageTicks = 0;
    }

    @Override
    public void onDisable() {
        damageTicks = 0;
    }
}
