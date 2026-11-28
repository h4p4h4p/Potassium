package com.potassium.module.render.motionblur;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;

/**
 * The extra colour attachment the monkey shader samples as {@code EntityMaskSampler}.
 *
 * <p>OpenAdjust fills it while a chams-protected entity is drawn; Potassium has no chams, so
 * the attachment stays clear and the mask simply never protects anything, which is what the
 * shader already does when the mask is empty.</p>
 */
final class DepthCapture {

    private static final int GL_FRAMEBUFFER = 36160;
    private static final int GL_COLOR_ATTACHMENT1 = 36065;

    Framebuffer mask;
    private Framebuffer target;
    boolean prepared;

    void prepare(Framebuffer framebuffer) {
        if (prepared || framebuffer == null || framebuffer.framebufferWidth < 1
                || framebuffer.framebufferHeight < 1) return;
        boolean depth = GL11.glIsEnabled(2929);
        boolean depthWrite = GL11.glGetBoolean(2930);
        int activeTexture = GL11.glGetInteger(34016);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int texture = GL11.glGetInteger(32873);
        try {
            if (mask == null) {
                mask = new Framebuffer(framebuffer.framebufferWidth, framebuffer.framebufferHeight, false);
                mask.setFramebufferColor(0, 0, 0, 0);
                target = framebuffer;
                framebuffer.bindFramebuffer(false);
                OpenGlHelper.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1,
                        3553, mask.framebufferTexture, 0);
                mask.checkFramebufferComplete();
            }
            GlStateManager.colorMask(true, true, true, true);
            mask.framebufferClear();
            prepared = true;
        } finally {
            GlStateManager.bindTexture(texture);
            GlStateManager.setActiveTexture(activeTexture);
            GlStateManager.depthMask(depthWrite);
            if (depth) GlStateManager.enableDepth();
            else GlStateManager.disableDepth();
            framebuffer.bindFramebuffer(false);
        }
    }

    void delete() {
        if (target != null && target.framebufferObject >= 0) {
            target.bindFramebuffer(false);
            OpenGlHelper.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, 3553, 0, 0);
        }
        if (mask != null) mask.deleteFramebuffer();
        mask = null;
        target = null;
        prepared = false;
    }
}
