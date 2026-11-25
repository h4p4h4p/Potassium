package com.potassium.module;

import com.potassium.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Equips the strongest armor piece for each slot and drops armor that is worse than
 * or an exact duplicate of what is equipped. A worse equipped piece is dropped before
 * the better piece is shift-clicked into the empty armor slot, then remaining inferior
 * and duplicate pieces are dropped.
 */
public class AutoArmor extends Module {

    private final SliderSetting delaySetting = new SliderSetting("Delay", 100, 0, 1000, 10);

    private final Minecraft mc = Minecraft.getMinecraft();
    private long lastAction;

    public AutoArmor() {
        super("AutoArmor", Category.PLAYER);
        settings.add(delaySetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) return;
        // Work in the background or with the inventory open, but stay out of other screens.
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiInventory)) return;

        Container container = mc.thePlayer.openContainer;
        if (container == null) return;

        long now = System.currentTimeMillis();
        if (now - lastAction < (long) delaySetting.getValue()) return;

        Upgrade upgrade = findUpgrade(container);
        if (upgrade != null) {
            if (upgrade.armorSlot.getHasStack()) {
                drop(container, upgrade.armorSlot);
            } else {
                equip(container, upgrade.candidateSlot);
            }
            lastAction = now;
            return;
        }

        Slot trash = findTrash(container);
        if (trash != null) {
            drop(container, trash);
            lastAction = now;
        }
    }

    /** Best inventory armor of each type that beats the currently equipped piece. */
    private Upgrade findUpgrade(Container container) {
        for (int type = 0; type < 4; type++) {
            Slot armorSlot = findArmorSlot(container, type);
            if (armorSlot == null) continue;

            int bestScore = score(armorSlot.getStack());
            Slot bestSlot = null;
            for (Slot slot : container.inventorySlots) {
                if (!isMainInventorySlot(slot)) continue;

                ItemStack stack = slot.getStack();
                if (armorType(stack) != type) continue;

                int candidateScore = score(stack);
                if (candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestSlot = slot;
                }
            }
            if (bestSlot != null) return new Upgrade(armorSlot, bestSlot);
        }
        return null;
    }

    /**
     * An armor piece in the player inventory that is worse than the equipped one, or an
     * exact duplicate of it. Equal-score pieces with different enchantments are kept.
     */
    private Slot findTrash(Container container) {
        for (int type = 0; type < 4; type++) {
            Slot armorSlot = findArmorSlot(container, type);
            if (armorSlot == null || !armorSlot.getHasStack()) continue;

            ItemStack equipped = armorSlot.getStack();
            int equippedScore = score(equipped);
            for (Slot slot : container.inventorySlots) {
                if (!isMainInventorySlot(slot)) continue;

                ItemStack stack = slot.getStack();
                if (armorType(stack) != type) continue;

                int candidateScore = score(stack);
                if (candidateScore < equippedScore
                        || (candidateScore == equippedScore && isDuplicate(equipped, stack))) {
                    return slot;
                }
            }
        }
        return null;
    }

    /** Same armor item and NBT (enchantments/name); durability is handled by score. */
    private boolean isDuplicate(ItemStack first, ItemStack second) {
        return first.getItem() == second.getItem()
                && ItemStack.areItemStackTagsEqual(first, second);
    }

    /**
     * Armor inventory is indexed 36 (boots) through 39 (helmet), while
     * ItemArmor.armorType is 0 (helmet) through 3 (boots).
     */
    private Slot findArmorSlot(Container container, int type) {
        int inventoryIndex = 39 - type;
        for (Slot slot : container.inventorySlots) {
            if (slot.inventory == mc.thePlayer.inventory
                    && slot.getSlotIndex() == inventoryIndex) {
                return slot;
            }
        }
        return null;
    }

    private boolean isMainInventorySlot(Slot slot) {
        if (slot.inventory != mc.thePlayer.inventory) return false;
        int index = slot.getSlotIndex();
        return index >= 0 && index < 36;
    }

    private int armorType(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) return -1;
        return ((ItemArmor) stack.getItem()).armorType;
    }

    /** Armor points first, then Protection levels, with remaining durability as a tiebreaker. */
    private int score(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) return -1;

        ItemArmor armor = (ItemArmor) stack.getItem();
        int score = armor.damageReduceAmount * 100;
        score += EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, stack) * 100;

        int maxDamage = stack.getMaxDamage();
        if (maxDamage > 0) {
            int remaining = Math.max(0, maxDamage - stack.getItemDamage());
            score += Math.min(99, remaining * 99 / maxDamage);
        }
        return score;
    }

    private void equip(Container container, Slot slot) {
        mc.playerController.windowClick(container.windowId, slot.slotNumber, 0, 1, mc.thePlayer);
    }

    /** Click mode 4 with button 1 throws the whole stack away. */
    private void drop(Container container, Slot slot) {
        mc.playerController.windowClick(container.windowId, slot.slotNumber, 1, 4, mc.thePlayer);
    }

    @Override
    public void onEnable() {
        lastAction = 0L;
    }

    @Override
    public void onDisable() {
        lastAction = 0L;
    }

    private static class Upgrade {
        private final Slot armorSlot;
        private final Slot candidateSlot;

        private Upgrade(Slot armorSlot, Slot candidateSlot) {
            this.armorSlot = armorSlot;
            this.candidateSlot = candidateSlot;
        }
    }
}
