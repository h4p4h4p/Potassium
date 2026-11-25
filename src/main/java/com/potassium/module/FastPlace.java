package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.SliderSetting;
import com.potassium.util.ClickState;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

/**
 * Places against the block under the crosshair every client tick while right-click is held.
 * Unlike RightClicker there is no CPS timer, so no ticks are skipped between placements.
 */
public class FastPlace extends Module {

    private final SliderSetting rangeSetting = new SliderSetting("Range", 5, 1, 5, 1);
    private final BooleanSetting blocksOnlySetting = new BooleanSetting("Blocks Only", true);

    private final Minecraft mc = Minecraft.getMinecraft();

    public FastPlace() {
        super("FastPlace", Category.PLAYER);
        settings.add(rangeSetting);
        settings.add(blocksOnlySetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        if (mc.playerController == null || !Mouse.isButtonDown(1)) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        boolean blockItem = held != null && held.getItem() instanceof ItemBlock;
        if (blocksOnlySetting.getValue() && !blockItem) return;

        int tick = mc.thePlayer.ticksExisted;
        if (!ClickState.canUse(tick)) return;

        MovingObjectPosition over = mc.objectMouseOver;
        if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && withinRange(over)) {
            // Swing after the use, like vanilla: a placement with no arm animation is held
            // against the player by the blockplace checks.
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                    over.getBlockPos(), over.sideHit, over.hitVec)) {
                mc.thePlayer.swingItem();
            }
            ClickState.markUse(tick);
            return;
        }

        // With Blocks Only off, non-block items still right-click normally each tick.
        if (!blockItem && held != null) {
            mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
            mc.thePlayer.swingItem();
            ClickState.markUse(tick);
        }
    }

    private boolean withinRange(MovingObjectPosition over) {
        double range = rangeSetting.getValue();
        Vec3 hit = over.hitVec;
        if (hit == null) {
            BlockPos pos = over.getBlockPos();
            hit = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        }
        return mc.thePlayer.getPositionEyes(1.0F).distanceTo(hit) <= range;
    }

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }
}
