package com.potassium.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

/**
 * Client-side hotbar switching, and the server-side slot it leaves behind.
 *
 * <p>Vanilla keeps the client's selected slot and the server's in step by sending a
 * {@code C09PacketHeldItemChange} whenever a hotbar key is pressed. Nothing in the client
 * notices a slot that is changed directly, so a module can put the player on a block for one
 * action and put the item they were holding back afterwards without the server ever hearing
 * about the switch: that is what makes a silent placement silent.</p>
 *
 * <p>{@link #enable(int)} tells the server to hold the block slot while the visible hotbar
 * stays where the player left it. {@link #swapClientToServer()} then puts the client on the
 * server's slot for the length of one click, because the client has to build the interaction
 * with the item it is about to place, and {@link #restoreClient()} puts the visible slot back.
 * Neither of those two sends a packet.</p>
 */
public final class SlotSpoofer {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** The slot the server holds while the visible slot stays on the client's own, or -1. */
    private static int silentSlot = -1;
    /** The client's slot before the spoof, restored when the spoof ends. */
    private static int clientSlot = -1;
    private static boolean swapped;

    private SlotSpoofer() {
    }

    /** True while the server is on a slot the player is not looking at. */
    public static boolean active() {
        return silentSlot >= 0;
    }

    public static int serverSlot() {
        return silentSlot;
    }

    /** Tells the server to hold {@code slot}, leaving the client's visible slot untouched. */
    public static void enable(int slot) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || slot < 0 || slot > 8) return;
        if (silentSlot >= 0) disable();
        silentSlot = slot;
        clientSlot = player.inventory.currentItem;
        sendSlot(slot);
    }

    /** Ends the spoof and gives the server the slot the player can see again. */
    public static void disable() {
        EntityPlayerSP player = mc.thePlayer;
        int restore = clientSlot;
        int held = silentSlot;
        silentSlot = -1;
        clientSlot = -1;
        swapped = false;
        if (player == null || restore < 0) return;
        player.inventory.currentItem = restore;
        if (held >= 0) sendSlot(restore);
    }

    /** Client-only: the player is put on the server's slot for one action, with no C09. */
    public static void swapClientToServer() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || silentSlot < 0) return;
        player.inventory.currentItem = silentSlot;
        swapped = true;
    }

    /** Client-only: puts the visible slot back after {@link #swapClientToServer()}. */
    public static void restoreClient() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || !swapped || clientSlot < 0) return;
        player.inventory.currentItem = clientSlot;
        swapped = false;
    }

    private static void sendSlot(int slot) {
        InventoryStateTracker.noteSlot(slot);
        if (mc.getNetHandler() == null) return;
        PacketBus.send(new C09PacketHeldItemChange(slot));
    }

    /**
     * Drops the spoof when something else already moved the server's slot, which is what a
     * hotbar key does: vanilla sends its own C09, so the block slot is gone from the server's
     * side and continuing to place against it would put the wrong item in the server's hand.
     * No packet is sent here, because the server is already on the player's slot.
     */
    public static void syncWithServer() {
        if (silentSlot < 0 || InventoryStateTracker.serverSlot() == silentSlot) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && swapped && clientSlot >= 0) player.inventory.currentItem = clientSlot;
        silentSlot = -1;
        clientSlot = -1;
        swapped = false;
    }
}
