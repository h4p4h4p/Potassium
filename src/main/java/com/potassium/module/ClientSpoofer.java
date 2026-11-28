package com.potassium.module;

import com.potassium.settings.ModeSetting;
import com.potassium.util.PacketBus;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/**
 * Replaces the MC|Brand custom payload the client sends in NetHandlerPlayClient.handleJoinGame.
 *
 * The packet is written on the outgoing channel, so the real FML brand never reaches the server.
 * The handler is installed on ClientConnectedToServerEvent, which FML fires when the handshake
 * finishes and before the join packet arrives. Enabling the module mid-game sends a fresh brand
 * payload so plugins that read the latest value pick it up without a reconnect.
 */
public class ClientSpoofer extends Module {

    public static final String MODE_VANILLA = "Vanilla";
    public static final String MODE_BADLION = "Badlion";
    public static final String MODE_LUNAR = "Lunar";
    public static final String MODE_GEYSER = "Geyser";
    public static final String MODE_CHEATBREAKER = "CheatBreaker";

    private static final String BRAND_CHANNEL = "MC|Brand";

    private final ModeSetting modeSetting = new ModeSetting("Mode",
            new String[]{MODE_VANILLA, MODE_BADLION, MODE_LUNAR, MODE_GEYSER, MODE_CHEATBREAKER}, 0);

    private final Minecraft mc = Minecraft.getMinecraft();

    // Shared with the packet bus listener, which runs on the channel thread.
    private volatile String lastBrand;
    private volatile boolean spoofedThisConnection;

    public ClientSpoofer() {
        super("ClientSpoofer", Category.EXPLOITS);
        settings.add(modeSetting);
        MinecraftForge.EVENT_BUS.register(this);
        PacketBus.subscribe("ClientSpoofer", PacketBus.Direction.OUTBOUND, 0, this::onPacket);
    }

    private String mode() {
        String mode = modeSetting.getValue();
        return mode == null ? MODE_VANILLA : mode;
    }

    private String brand() {
        String mode = mode();
        if (MODE_BADLION.equals(mode)) return "badlion";
        if (MODE_LUNAR.equals(mode)) return "lunarclient:v2.21.33-2612";
        if (MODE_GEYSER.equals(mode)) return "geyser";
        if (MODE_CHEATBREAKER.equals(mode)) return "CB";
        return "vanilla";
    }

    @Override
    public String getTag() {
        return mode();
    }

    /** Fired on the channel thread once the FML handshake is done and before S01PacketJoinGame. */
    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        spoofedThisConnection = false;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        String brand = brand();
        if (!spoofedThisConnection || !brand.equals(lastBrand)) {
            sendBrand(brand);
        }
    }

    private void sendBrand(String brand) {
        if (mc.getNetHandler() == null) return;
        mc.getNetHandler().addToSendQueue(brandPacket(brand));
        lastBrand = brand;
        spoofedThisConnection = true;
    }

    private C17PacketCustomPayload brandPacket(String brand) {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        buffer.writeString(brand);
        return new C17PacketCustomPayload(BRAND_CHANNEL, buffer);
    }

    /** Replaces the client's own brand payload before it reaches the server. */
    private void onPacket(PacketBus.Event event) {
        if (!isEnabled() || event.direction() != PacketBus.Direction.OUTBOUND) return;
        if (!(event.packet() instanceof C17PacketCustomPayload)) return;
        C17PacketCustomPayload packet = (C17PacketCustomPayload) event.packet();
        if (!BRAND_CHANNEL.equals(packet.getChannelName())) return;

        String brand = brand();
        event.replace(brandPacket(brand));
        lastBrand = brand;
        spoofedThisConnection = true;
    }

    @Override
    public void onEnable() {
        lastBrand = null;
        spoofedThisConnection = false;
    }

    @Override
    public void onDisable() {
        lastBrand = null;
        spoofedThisConnection = false;
    }
}
