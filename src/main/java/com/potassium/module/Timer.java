package com.potassium.module;

import com.potassium.settings.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Multiplies Minecraft's client timer, speeding up the game without changing movement or
 * sending any packets itself. The speed is applied at the end of the client tick, after the
 * modules that temporarily adjust the timer, so it is the value the next frame uses.
 */
public class Timer extends Module {

    private static final float VANILLA_SPEED = 1.0F;

    private final SliderSetting speedSetting = new SliderSetting("Speed", 1.5, 1.0, 10.0, 0.05);

    private final Minecraft mc = Minecraft.getMinecraft();
    private net.minecraft.util.Timer clientTimer;

    public Timer() {
        super("Timer", Category.MOVEMENT);
        settings.add(speedSetting);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled()) return;
        setTimerSpeed((float) speedSetting.getValue());
    }

    /** Minecraft.timer is private; accept both the MCP and SRG field names. */
    private net.minecraft.util.Timer getClientTimer() {
        if (clientTimer == null) {
            clientTimer = ReflectionHelper.getPrivateValue(Minecraft.class, mc,
                    "timer", "field_71428_T");
        }
        return clientTimer;
    }

    private void setTimerSpeed(float speed) {
        net.minecraft.util.Timer timer = getClientTimer();
        if (timer != null) timer.timerSpeed = speed;
    }

    @Override
    public String getTag() {
        double value = speedSetting.getValue();
        return (Math.round(value * 100.0) / 100.0) + "x";
    }

    @Override
    public void onEnable() {
        setTimerSpeed((float) speedSetting.getValue());
    }

    @Override
    public void onDisable() {
        setTimerSpeed(VANILLA_SPEED);
    }
}
