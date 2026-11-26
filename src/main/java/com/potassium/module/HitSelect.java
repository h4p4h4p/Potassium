package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Chooses which clicks the clickers and KillAura are allowed to spend.
 *
 * <p>Burst puts a short pause on a target after it has been hit, so repeated clicks do not
 * all go out at the aura's full CPS. Allow Second Hit lets one extra click through per
 * pause, which is enough to keep a server-side hit registered without turning the pause
 * into a full stop. Critical instead suppresses clicks while the player is rising, so the
 * next hit lands during the fall and counts as a critical.</p>
 *
 * <p>The gate is consulted only at the moment an attack would otherwise be sent, so a
 * suppressed tick does not throw away the clicker's CPS budget and the aim keeps tracking.</p>
 */
public class HitSelect extends Module {

    public static final String MODE_BURST = "Burst";
    public static final String MODE_CRITICAL = "Critical";

    private static HitSelect instance;

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_BURST, MODE_CRITICAL}, 0);
    private final SliderSetting pauseSetting = new SliderSetting("Pause", 500, 0, 1000, 50);
    private final BooleanSetting secondHitSetting = new BooleanSetting("Allow Second Hit", true);
    private final BooleanSetting fakeSwingSetting = new BooleanSetting("Fake Swing", true);

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Map<Integer, Integer> pausedUntil = new HashMap<>();
    private final Set<Integer> secondHitUsed = new HashSet<>();
    private net.minecraft.world.World lastWorld;

    public HitSelect() {
        super("HitSelect", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(pauseSetting);
        settings.add(secondHitSetting);
        settings.add(fakeSwingSetting);
        instance = this;
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** True when this attack should be held back for the target. */
    public static boolean suppressing(EntityLivingBase target) {
        HitSelect module = instance;
        if (module == null || !module.isEnabled()) return false;
        if (target == null || !target.isEntityAlive() || module.mc.thePlayer == null) return false;

        if (module.isCriticalMode()) {
            return !module.mc.thePlayer.onGround && module.mc.thePlayer.motionY > 0.0D;
        }

        Integer until = module.pausedUntil.get(target.getEntityId());
        if (until == null || module.mc.thePlayer.ticksExisted >= until) return false;
        // One extra click is allowed per pause window.
        return !module.secondHitSetting.getValue()
                || module.secondHitUsed.contains(target.getEntityId());
    }

    /** Marks the one extra click in the current pause as used. */
    public static void consumeSecondHit(EntityLivingBase target) {
        HitSelect module = instance;
        if (module == null || !module.isEnabled() || target == null) return;
        if (module.isCriticalMode() || !module.secondHitSetting.getValue()) return;

        Integer until = module.pausedUntil.get(target.getEntityId());
        if (until == null || module.mc.thePlayer == null
                || module.mc.thePlayer.ticksExisted >= until) {
            return;
        }
        module.secondHitUsed.add(target.getEntityId());
    }

    /** Starts the pause window after a real hit. */
    public static void noteHit(EntityLivingBase target) {
        HitSelect module = instance;
        if (module == null || !module.isEnabled() || target == null || module.mc.thePlayer == null) {
            return;
        }
        if (module.isCriticalMode()) return;

        int pause = Math.max(0, (int) Math.round(module.pauseSetting.getValue() / 50.0D));
        if (pause <= 0) {
            module.pausedUntil.remove(target.getEntityId());
            module.secondHitUsed.remove(target.getEntityId());
            return;
        }

        // A second hit inside an existing pause keeps the original window. Resetting it
        // here would make every click restart the pause and defeat the click limit.
        Integer current = module.pausedUntil.get(target.getEntityId());
        if (current != null && module.mc.thePlayer.ticksExisted < current) return;

        module.pausedUntil.put(target.getEntityId(),
                module.mc.thePlayer.ticksExisted + pause);
        module.secondHitUsed.remove(target.getEntityId());
    }

    /** Keeps the attack animation moving on a suppressed click without sending a hit. */
    public static void fakeSwing() {
        HitSelect module = instance;
        if (module == null || !module.isEnabled() || !module.fakeSwingSetting.getValue()) return;
        if (module.mc.thePlayer != null) module.mc.thePlayer.swingItem();
    }

    private boolean isCriticalMode() {
        return MODE_CRITICAL.equals(modeSetting.getValue());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (mc.thePlayer == null) {
            pausedUntil.clear();
            secondHitUsed.clear();
            lastWorld = null;
            return;
        }
        if (mc.theWorld != lastWorld) {
            lastWorld = mc.theWorld;
            pausedUntil.clear();
            secondHitUsed.clear();
        }
        int now = mc.thePlayer.ticksExisted;
        pausedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        secondHitUsed.removeIf(id -> !pausedUntil.containsKey(id));
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        pausedUntil.clear();
        secondHitUsed.clear();
    }
}
