package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.ModeSetting;
import com.potassium.settings.RangeSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.ClickState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

public class LeftClicker extends Module {

    public static final String MODE_NORMAL = "Normal";
    public static final String MODE_TRIGGER = "Trigger";

    // Old configs selected NCP as mode index 2. Without this it would clamp to Trigger.
    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_NORMAL, MODE_TRIGGER}, 0) {
        @Override
        public void fromConfigString(String s) {
            if ("2".equals(s.trim())) {
                setIndex(0);
                return;
            }
            super.fromConfigString(s);
        }
    };
    private final BooleanSetting weaponOnlySetting = new BooleanSetting("Weapon Only", false);
    private final SliderSetting delaySetting = new SliderSetting("Delay", 100, 0, 1000, 10);
    private final RangeSetting cpsSetting = new RangeSetting("CPS", 12, 12, 1, 20, 1);
    private final BooleanSetting blockHitSetting = new BooleanSetting("BlockHit", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean wasDown;
    private long pressTime;
    private int lastAttackTick = -1;
    private int resumeBlockTick = -1;
    private double clickBudget = 1.0;

    public LeftClicker() {
        super("LeftClicker", Category.COMBAT);
        settings.add(modeSetting);
        settings.add(weaponOnlySetting);
        settings.add(delaySetting);
        settings.add(cpsSetting);
        settings.add(blockHitSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
            reset();
            return;
        }

        if (weaponOnlySetting.getValue() && !holdingWeapon()) {
            reset();
            return;
        }

        boolean blockHit = blockHitSetting.getValue();
        boolean down = Mouse.isButtonDown(0);
        boolean rightDown = Mouse.isButtonDown(1);
        long now = System.currentTimeMillis();
        int tick = mc.thePlayer.ticksExisted;

        if (down && !wasDown) {
            pressTime = now;
            lastAttackTick = -1;
            clickBudget = 1.0;
        }
        wasDown = down;

        if (resumeBlockTick >= 0) {
            if (resumeBlockTick == tick) {
                resumeBlockTick = -1;
                ItemStack held = mc.thePlayer.getHeldItem();
                if (blockHit && rightDown && held != null && mc.playerController != null
                        && !mc.thePlayer.isUsingItem() && ClickState.canUse(tick)) {
                    mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
                    ClickState.markUse(tick);
                }
            } else if (resumeBlockTick < tick) {
                resumeBlockTick = -1;
            }
        }

        if (!down) {
            reset();
            return;
        }

        if (blockHit) {
            if (!rightDown) {
                reset();
                return;
            }
            if (mc.thePlayer.isUsingItem()) {
                lastAttackTick = tick;
                return;
            }

            if (now - pressTime < (long) delaySetting.getValue()) return;
            if (tick == lastAttackTick) return;
            if (!ClickState.canAttack(tick)) return;

            if (clickDue()) {
                blockHitClick();
                lastAttackTick = tick;
            }
            return;
        }

        if (mc.thePlayer.isUsingItem()) {
            lastAttackTick = tick;
            return;
        }

        if (now - pressTime < (long) delaySetting.getValue()) return;
        if (tick == lastAttackTick) return;
        if (!ClickState.canAttack(tick)) return;

        if (clickDue()) {
            click();
            lastAttackTick = tick;
        }
    }

    /**
     * Built-in tick spreader. A fraction of a click is banked every tick from the CPS setting, so
     * attacks are paced across ticks instead of being rolled as a burst-prone chance. At most one
     * attack can be released per tick, and leftover credit is capped so lag cannot cause a burst.
     */
    private boolean clickDue() {
        // The CPS range replaces the old Jitter percentage: every tick banks a fraction of a
        // click drawn from the range, so the pacing varies without a burst-prone chance roll.
        double cps = cpsSetting.random();
        if (cps <= 0.0) return false;

        clickBudget += cps / 20.0;
        if (clickBudget < 1.0) return false;

        clickBudget = Math.min(1.0, clickBudget - 1.0);
        return true;
    }

    private void blockHitClick() {
        if (mc.playerController == null || mc.getNetHandler() == null) return;
        if (mc.objectMouseOver == null || mc.objectMouseOver.entityHit == null) return;

        int tick = mc.thePlayer.ticksExisted;
        mc.thePlayer.stopUsingItem();
        mc.playerController.onStoppedUsingItem(mc.thePlayer);

        MovingObjectPosition over = mc.objectMouseOver;
        if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && over.entityHit != null) {
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, over.entityHit);
            ClickState.markAttack(tick);
        }

        // Re-block on the next tick instead of sending use and attack together.
        resumeBlockTick = tick + 1;
    }

    private boolean triggerMode() {
        return MODE_TRIGGER.equals(modeSetting.getValue());
    }

    private boolean holdingWeapon() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) return false;
        Item item = held.getItem();
        return item instanceof ItemSword || item instanceof ItemAxe;
    }

    private void click() {
        if (triggerMode()) {
            clickEntityOnly();
            return;
        }
        int tick = mc.thePlayer.ticksExisted;
        MovingObjectPosition over = mc.objectMouseOver;
        if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && over.entityHit != null) {
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, over.entityHit);
            ClickState.markAttack(tick);
        } else {
            mc.thePlayer.swingItem();
            if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                mc.playerController.onPlayerDamageBlock(over.getBlockPos(), over.sideHit);
                ClickState.markAttack(tick);
            }
        }
    }

    private void clickEntityOnly() {
        MovingObjectPosition over = mc.objectMouseOver;
        if (over == null) return;
        if (over.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) return;
        Entity target = over.entityHit;
        if (target == null) return;
        mc.thePlayer.swingItem();
        mc.playerController.attackEntity(mc.thePlayer, target);
        ClickState.markAttack(mc.thePlayer.ticksExisted);
    }

    private void reset() {
        wasDown = false;
        pressTime = 0;
        lastAttackTick = -1;
        clickBudget = 1.0;
    }

    @Override
    public String getTag() {
        long low = Math.round(cpsSetting.getLow());
        long high = Math.round(cpsSetting.getHigh());
        return low == high ? low + " CPS" : low + "-" + high + " CPS";
    }

    @Override
    public void onEnable() {
        resumeBlockTick = -1;
        reset();
    }

    @Override
    public void onDisable() {
        resumeBlockTick = -1;
        reset();
    }
}