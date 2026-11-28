package com.potassium.mixin;

import net.minecraft.network.play.server.S14PacketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The entity id of a relative-move packet.
 *
 * <p>The class keeps it private and offers no getter, so Backtrack had to reflect on it for
 * every movement packet it saw, on the Netty thread. The accessor makes that a plain field
 * read; the reflective lookup stays as a fallback in case the mixin is not applied.</p>
 */
@Mixin(S14PacketEntity.class)
public interface S14PacketEntityAccessor {

    @Accessor("entityId")
    int potassium$entityId();
}
