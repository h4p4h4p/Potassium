package com.potassium.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Direct access to the two Minecraft fields the client used to reach with reflection.
 *
 * <p>{@code rightClickDelayTimer} is the cooldown Scaffold pins so vanilla cannot place a
 * second block beside its own click, and {@code timer} supplies the partial tick KillAura
 * samples targets with. A field accessor is compiled and remapped with the rest of the mod,
 * so there is no name lookup at runtime and nothing to break when a mapping changes.</p>
 *
 * <p>Call sites still keep their reflective fallback: if the mixin is not applied, the cast
 * fails and they use the old path instead of crashing.</p>
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Accessor("rightClickDelayTimer")
    int potassium$rightClickDelayTimer();

    @Accessor("rightClickDelayTimer")
    void potassium$rightClickDelayTimer(int value);

    @Accessor("timer")
    Timer potassium$timer();
}
