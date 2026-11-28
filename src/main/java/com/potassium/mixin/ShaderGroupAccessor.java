package com.potassium.mixin;

import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The two private fields the motion blur passes uniforms through. */
@Mixin(ShaderGroup.class)
public interface ShaderGroupAccessor {

    @Accessor("listShaders")
    List<Shader> getShaders();

    @Accessor("mainFramebuffer")
    Framebuffer getMainFramebuffer();
}
