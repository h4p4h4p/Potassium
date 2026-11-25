package com.potassium.module;

import com.potassium.settings.SelectSetting;
import com.potassium.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAppleGold;
import net.minecraft.item.ItemArmor;
import net.minecraft.init.Items;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemBucket;
import net.minecraft.item.ItemBucketMilk;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemExpBottle;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

/**
 * Shift-clicks the contents of an open chest into the player inventory.
 * One stack is moved per delay so the server sees a normal sequence of inventory clicks.
 */
public class ChestStealer extends Module {

    private final SliderSetting delaySetting = new SliderSetting("Delay", 50, 0, 500, 10);
    private final SelectSetting blacklistSetting = new SelectSetting("Blacklist", new String[]{
            "Armor", "Healing", "Potions", "Food", "Swords", "Pickaxes", "Shovels", "Axes", "Fishing Rods",
            "Buckets", "Bows", "Arrows", "Projectiles", "Blocks"
    });

    private final Minecraft mc = Minecraft.getMinecraft();
    private long lastClick;

    public ChestStealer() {
        super("ChestStealer", Category.PLAYER);
        settings.add(delaySetting);
        settings.add(blacklistSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isEnabled()) return;
        if (!(mc.currentScreen instanceof GuiChest) || mc.thePlayer == null
                || mc.playerController == null) {
            lastClick = 0L;
            return;
        }

        Container container = mc.thePlayer.openContainer;
        if (container == null) return;

        long now = System.currentTimeMillis();
        if (now - lastClick < (long) delaySetting.getValue()) return;

        Slot slot = findChestStack(container);
        if (slot == null) return;

        mc.playerController.windowClick(container.windowId, slot.slotNumber, 0, 1, mc.thePlayer);
        lastClick = now;
    }

    /** Chest slots belong to the chest inventory; the last 36 slots belong to the player. */
    private Slot findChestStack(Container container) {
        for (Slot slot : container.inventorySlots) {
            if (slot.inventory == mc.thePlayer.inventory || !slot.getHasStack()) continue;
            if (!isBlacklisted(slot.getStack())) return slot;
        }
        return null;
    }

    private boolean isBlacklisted(ItemStack stack) {
        if (stack == null) return false;
        Item item = stack.getItem();

        // Healing items are governed only by their own option. Otherwise Food would also
        // catch golden apples and Potions every healing potion, so unchecking Healing would
        // still leave them in the chest.
        if (isHealing(stack)) return blacklistSetting.isSelected("Healing");

        if (blacklistSetting.isSelected("Armor") && item instanceof ItemArmor) return true;
        if (blacklistSetting.isSelected("Potions") && item instanceof ItemPotion) return true;
        if (blacklistSetting.isSelected("Food") && item instanceof ItemFood) return true;
        if (blacklistSetting.isSelected("Swords") && item instanceof ItemSword) return true;
        if (blacklistSetting.isSelected("Pickaxes") && item instanceof ItemPickaxe) return true;
        if (blacklistSetting.isSelected("Shovels") && item instanceof ItemSpade) return true;
        if (blacklistSetting.isSelected("Axes") && item instanceof ItemAxe) return true;
        if (blacklistSetting.isSelected("Fishing Rods") && item instanceof ItemFishingRod) return true;
        if (blacklistSetting.isSelected("Buckets") && isBucket(item)) return true;
        if (blacklistSetting.isSelected("Bows") && item instanceof ItemBow) return true;
        if (blacklistSetting.isSelected("Arrows") && isArrow(item)) return true;
        if (blacklistSetting.isSelected("Projectiles") && isProjectile(item)) return true;
        return blacklistSetting.isSelected("Blocks") && item instanceof ItemBlock;
    }

    /** Empty, water, lava and milk buckets. */
    private boolean isBucket(Item item) {
        return item instanceof ItemBucket || item instanceof ItemBucketMilk;
    }

    /** 1.8.9 has no ItemArrow class; the arrow item is registered directly. */
    private boolean isArrow(Item item) {
        return item == Items.arrow;
    }

    /** Anything thrown or fired: arrows, snowballs, eggs, ender pearls and XP bottles. */
    private boolean isProjectile(Item item) {
        return isArrow(item) || item instanceof ItemSnowball || item instanceof ItemEgg
                || item instanceof ItemEnderPearl || item instanceof ItemExpBottle;
    }

    /** Golden apples and potions that restore health or apply Regeneration. */
    private boolean isHealing(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof ItemAppleGold) return true;
        if (!(item instanceof ItemPotion)) return false;

        List<PotionEffect> effects = ((ItemPotion) item).getEffects(stack);
        if (effects == null) return false;
        for (PotionEffect effect : effects) {
            int id = effect.getPotionID();
            if (id == Potion.heal.id || id == Potion.regeneration.id) return true;
        }
        return false;
    }

    @Override
    public void onEnable() {
        lastClick = 0L;
    }

    @Override
    public void onDisable() {
        lastClick = 0L;
    }
}
