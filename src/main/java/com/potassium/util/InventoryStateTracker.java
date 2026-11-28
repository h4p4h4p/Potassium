package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The hotbar slot the server believes the player is on.
 *
 * <p>The client's own slot can be moved without a packet (see {@link SlotSpoofer}), so it is
 * no longer a reliable answer to "what am I holding" for anything the server has to agree
 * with. This reads every outgoing held-item change off the packet bus and keeps the server's
 * view, which is what placement and blocking checks should use while a spoof is active.</p>
 */
public final class InventoryStateTracker {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static volatile int serverSlot;
    private static EntityPlayerSP trackedPlayer;
    private static boolean registered;

    private InventoryStateTracker() {
    }

    /** Subscribes to outgoing slot changes. Call once, from init. */
    public static void init() {
        if (registered) return;
        registered = true;
        EntityPlayerSP player = mc.thePlayer;
        if (player != null) serverSlot = player.inventory.currentItem;
        PacketBus.subscribe("InventoryStateTracker", PacketBus.Direction.OUTBOUND, 0,
                InventoryStateTracker::onPacket);
        MinecraftForge.EVENT_BUS.register(new Watcher());
    }

    /**
     * A new player instance is a new connection or a new dimension, and both start with the
     * server's held slot matching the fresh inventory's slot zero.
     */
    private static final class Watcher {

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            EntityPlayerSP player = mc.thePlayer;
            if (player == trackedPlayer) return;
            trackedPlayer = player;
            if (player != null) serverSlot = player.inventory.currentItem;
        }
    }

    private static void onPacket(PacketBus.Event event) {
        if (!(event.packet() instanceof C09PacketHeldItemChange)) return;
        int slot = ((C09PacketHeldItemChange) event.packet()).getSlotId();
        if (slot >= 0 && slot < 9) serverSlot = slot;
    }

    public static int serverSlot() {
        return serverSlot;
    }

    /** Notes a slot change that was just queued, without waiting for the packet to be seen. */
    public static void noteSlot(int slot) {
        if (slot >= 0 && slot < 9) serverSlot = slot;
    }

    /** The slot the client is displaying, which can differ while a spoof is active. */
    public static ItemStack clientStack() {
        EntityPlayerSP player = mc.thePlayer;
        return player == null ? null : player.getHeldItem();
    }

    /** The item in the slot the server holds, or null when there is none. */
    public static ItemStack heldStack() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return null;
        int slot = serverSlot;
        if (slot < 0 || slot >= 9) return null;
        return player.inventory.mainInventory[slot];
    }
}
