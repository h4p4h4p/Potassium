package com.potassium.module.scaffold;

import com.potassium.module.Scaffold;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;

import org.lwjgl.input.Keyboard;

import java.util.Random;

/**
 * OpenAdjust's {@code SafeWalkHelper}: while the predictor says the next block is close, the
 * sneak key is pressed so the walk slows over the edge, and released a few ticks after the
 * space under the feet is filled again.
 */
public final class SafeWalk {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Random RANDOM = new Random();

    private final Scaffold module;

    int unsneakTicks;
    boolean sneaking;

    public SafeWalk(Scaffold module) {
        this.module = module;
    }

    public void update(Integer ground) {
        if (ground == null) {
            stopSneak();
            return;
        }
        ScaffoldSettings settings = module.getOptions();
        int delay = (int) Math.round(settings.getEarlySneak());
        if (delay >= 1) delay = jitter(delay);
        if (!settings.isSafeWalk() || !settings.isSafeWalkSneak()) {
            stopSneak();
            return;
        }
        if (ground + 1 > delay) {
            stopSneak();
            return;
        }
        BlockPos below = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 2.0D, mc.thePlayer.posZ);
        Block block = mc.theWorld.getBlockState(below).getBlock();
        if (block.getMaterial().isReplaceable()) {
            startSneak();
        }
    }

    /** Presses the sneak key and arms the release countdown. */
    public void startSneak() {
        if (unsneakTicks == 0) {
            int delay = (int) Math.round(module.getOptions().getUnsneakDelay());
            unsneakTicks = delay < 1 ? 0 : jitter(delay);
        }
        int key = mc.gameSettings.keyBindSneak.getKeyCode();
        if (key > 0 && key < 256) KeyBinding.setKeyBindState(key, true);
        sneaking = true;
    }

    /** Counts the release delay down and gives the sneak key back when it is over. */
    private void stopSneak() {
        if (!sneaking) return;
        if (unsneakTicks != 0) {
            unsneakTicks--;
            return;
        }
        int key = mc.gameSettings.keyBindSneak.getKeyCode();
        if (key > 0 && key < 256) KeyBinding.setKeyBindState(key, Keyboard.isKeyDown(key));
        sneaking = false;
    }

    public void reset() {
        unsneakTicks = 0;
        stopSneak();
        sneaking = false;
        unsneakTicks = 0;
    }

    private static int jitter(int delay) {
        if (RANDOM.nextBoolean()) return delay + (RANDOM.nextBoolean() ? 1 : 0);
        return delay;
    }
}
