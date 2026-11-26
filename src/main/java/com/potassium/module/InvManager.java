package com.potassium.module;

import com.potassium.settings.BooleanSetting;
import com.potassium.settings.SliderSetting;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Small inventory helper: picks the fastest hotbar tool while mining, fills empty hotbar
 * slots from the main inventory when the inventory is open, and drops common junk.
 *
 * <p>The refill uses a hotbar-swap click (mode 2), so the source stack and the hotbar slot
 * trade places in one window click and no carried stack can be left on the cursor.</p>
 */
public class InvManager extends Module {

    private final SliderSetting delaySetting = new SliderSetting("Delay", 100, 0, 1000, 10);
    private final BooleanSetting autoToolSetting = new BooleanSetting("Auto Tool", true);
    private final BooleanSetting refillSetting = new BooleanSetting("Refill Hotbar", true);
    private final BooleanSetting dropJunkSetting = new BooleanSetting("Drop Junk", false);

    private final Minecraft mc = Minecraft.getMinecraft();
    private long lastAction;
    private int restoreSlot = -1;
    private int lastMineTick;

    public InvManager() {
        super("InvManager", Category.PLAYER);
        settings.add(delaySetting);
        settings.add(autoToolSetting);
        settings.add(refillSetting);
        settings.add(dropJunkSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            restoreTool();
            return;
        }

        int tick = mc.thePlayer.ticksExisted;
        if (autoToolSetting.getValue()) tickAutoTool(tick);
        else restoreTool();

        if (mc.currentScreen instanceof GuiInventory) tickInventory();
    }

    /** Switches to the strongest hotbar tool for the block being mined, then puts it back. */
    private void tickAutoTool(int tick) {
        MovingObjectPosition over = mc.objectMouseOver;
        boolean mining = mc.currentScreen == null
                && mc.gameSettings.keyBindAttack.isKeyDown()
                && over != null
                && over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;

        if (!mining) {
            if (restoreSlot >= 0 && tick - lastMineTick > 5) restoreTool();
            return;
        }

        Block block = mc.theWorld.getBlockState(over.getBlockPos()).getBlock();
        int best = bestToolSlot(block);
        if (best < 0) {
            lastMineTick = tick;
            return;
        }

        ItemStack held = mc.thePlayer.getHeldItem();
        float heldStrength = held == null ? 1.0F : held.getStrVsBlock(block);
        ItemStack bestStack = mc.thePlayer.inventory.getStackInSlot(best);
        if (bestStack == null) return;

        if (bestStack.getStrVsBlock(block) > heldStrength) {
            if (restoreSlot < 0) restoreSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = best;
        }
        lastMineTick = tick;
    }

    private int bestToolSlot(Block block) {
        int best = -1;
        float bestStrength = 1.0F;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null) continue;
            float strength = stack.getStrVsBlock(block);
            if (strength > bestStrength) {
                bestStrength = strength;
                best = i;
            }
        }
        return best;
    }

    private void restoreTool() {
        if (restoreSlot < 0) return;
        if (mc.thePlayer != null) mc.thePlayer.inventory.currentItem = restoreSlot;
        restoreSlot = -1;
    }

    private void tickInventory() {
        long now = System.currentTimeMillis();
        if (now - lastAction < (long) delaySetting.getValue()) return;

        Container container = mc.thePlayer.openContainer;
        if (container == null) return;

        if (refillSetting.getValue() && refillHotbar(container)) {
            lastAction = now;
            return;
        }
        if (dropJunkSetting.getValue() && dropJunk(container)) {
            lastAction = now;
        }
    }

    /** Moves a block, food or potion stack into the first empty hotbar slot. */
    private boolean refillHotbar(Container container) {
        for (int hotbar = 0; hotbar < 9; hotbar++) {
            if (mc.thePlayer.inventory.getStackInSlot(hotbar) != null) continue;

            int source = findRefillSource();
            if (source < 0) return false;

            int slotNumber = playerSlot(container, source);
            if (slotNumber < 0) return false;

            // Mode 2 swaps the clicked inventory slot with the hotbar button's slot.
            mc.playerController.windowClick(container.windowId, slotNumber, hotbar, 2, mc.thePlayer);
            return true;
        }
        return false;
    }

    private int findRefillSource() {
        int food = -1;
        int potion = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null) continue;

            if (stack.getItem() instanceof ItemBlock) return i;
            if (food < 0 && stack.getItem() instanceof ItemFood) food = i;
            if (potion < 0 && stack.getItem() instanceof ItemPotion) potion = i;
        }
        return food >= 0 ? food : potion;
    }

    private int playerSlot(Container container, int inventoryIndex) {
        for (Slot slot : container.inventorySlots) {
            if (slot.inventory == mc.thePlayer.inventory
                    && slot.getSlotIndex() == inventoryIndex) {
                return slot.slotNumber;
            }
        }
        return -1;
    }

    private boolean dropJunk(Container container) {
        for (Slot slot : container.inventorySlots) {
            if (slot.inventory != mc.thePlayer.inventory || !slot.getHasStack()) continue;
            if (!isJunk(slot.getStack())) continue;

            // Mode 4 with button 1 throws the whole stack away.
            mc.playerController.windowClick(container.windowId, slot.slotNumber, 1, 4, mc.thePlayer);
            return true;
        }
        return false;
    }

    private boolean isJunk(ItemStack stack) {
        if (stack == null) return false;
        return stack.getItem() == Items.rotten_flesh
                || stack.getItem() == Items.spider_eye
                || stack.getItem() == Items.poisonous_potato
                || stack.getItem() == Items.bowl;
    }

    @Override
    public void onEnable() {
        lastAction = 0L;
        lastMineTick = 0;
        restoreSlot = -1;
    }

    @Override
    public void onDisable() {
        restoreTool();
        lastAction = 0L;
    }
}
