package com.potassium.module.render.motionblur;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderManager;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** OpenAdjust's {@code MonkeyBlurShader}: reprojects each pixel with last frame's matrices. */
final class MonkeyBlurShader {

    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private final float[] projection = identity();
    private final float[] modelView = identity();
    private final float[] previousProjection = identity();
    private final float[] previousModelView = identity();
    private final float[] projectionInverse = identity();
    private final float[] modelViewInverse = identity();

    private ShaderGroup shaders;
    final DepthCapture depthCapture = new DepthCapture();
    private int depthTexture = -1;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private double previousX;
    private double previousY;
    private double previousZ;
    private boolean initialized;
    boolean valid;

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = 1.0F;
        m[5] = 1.0F;
        m[10] = 1.0F;
        m[15] = 1.0F;
        return m;
    }

    private void copyDepth(Framebuffer source, int texture) {
        int active = GL11.glGetInteger(34016);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int binding = GL11.glGetInteger(32873);
        GlStateManager.bindTexture(texture);
        GL11.glCopyTexSubImage2D(3553, 0, 0, 0, 0, 0,
                source.framebufferWidth, source.framebufferHeight);
        GlStateManager.bindTexture(binding);
        GlStateManager.setActiveTexture(active);
    }

    void render(Framebuffer target, float strength) throws Exception {
        if (!valid || !target.useDepth) return;
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        ensureDepthTexture(target);
        depthCapture.prepare(target);
        if (shaders == null) {
            shaders = MotionBlurRenderer.loadShader(target, "monkey_blur");
            Shader first = MotionBlurRenderer.shaders(shaders).get(0);
            first.addAuxFramebuffer("depthtex", depthTexture,
                    target.framebufferWidth, target.framebufferHeight);
            first.addAuxFramebuffer("EntityMaskSampler", depthCapture.mask,
                    target.framebufferWidth, target.framebufferHeight);
        }
        target.bindFramebuffer(false);
        copyDepth(target, depthTexture);
        ShaderManager shader = MotionBlurRenderer.shaders(shaders).get(0).getShaderManager();
        shader.getShaderUniformOrDefault("strength").set(strength);
        shader.getShaderUniformOrDefault("cameraPosition")
                .set((float) cameraX, (float) cameraY, (float) cameraZ);
        shader.getShaderUniformOrDefault("previousCameraPosition")
                .set((float) previousX, (float) previousY, (float) previousZ);
        setMatrix(shader, "projectionInverse", projectionInverse);
        setMatrix(shader, "modelViewInverse", modelViewInverse);
        setMatrix(shader, "previousProjection", previousProjection);
        setMatrix(shader, "previousModelView", previousModelView);
        MotionBlurRenderer.renderShader(shaders);
    }

    void updateMatrices(Entity camera, float partialTicks) {
        System.arraycopy(projection, 0, previousProjection, 0, 16);
        System.arraycopy(modelView, 0, previousModelView, 0, 16);
        previousX = cameraX;
        previousY = cameraY;
        previousZ = cameraZ;
        cameraX = camera.prevPosX + (camera.posX - camera.prevPosX) * partialTicks;
        cameraY = camera.prevPosY + (camera.posY - camera.prevPosY) * partialTicks;
        cameraZ = camera.prevPosZ + (camera.posZ - camera.prevPosZ) * partialTicks;

        readMatrix(2983, projection);
        readMatrix(2982, modelView);

        if (!initialized || Math.abs(cameraX - previousX) > 16.0D
                || Math.abs(cameraY - previousY) > 16.0D
                || Math.abs(cameraZ - previousZ) > 16.0D) {
            System.arraycopy(projection, 0, previousProjection, 0, 16);
            System.arraycopy(modelView, 0, previousModelView, 0, 16);
            previousX = cameraX;
            previousY = cameraY;
            previousZ = cameraZ;
        }
        System.arraycopy(MatrixMath.invert(projection), 0, projectionInverse, 0, 16);
        System.arraycopy(MatrixMath.invert(modelView), 0, modelViewInverse, 0, 16);
        initialized = true;
        valid = true;
    }

    private void readMatrix(int pname, float[] out) {
        matrixBuffer.clear();
        GL11.glGetFloat(pname, matrixBuffer);
        matrixBuffer.rewind();
        matrixBuffer.get(out);
    }

    private void setMatrix(ShaderManager shader, String name, float[] m) {
        shader.getShaderUniformOrDefault(name).set(
                m[0], m[1], m[2], m[3],
                m[4], m[5], m[6], m[7],
                m[8], m[9], m[10], m[11],
                m[12], m[13], m[14], m[15]);
    }

    private void ensureDepthTexture(Framebuffer target) {
        int active = GL11.glGetInteger(34016);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int binding = GL11.glGetInteger(32873);
        if (depthTexture == -1) depthTexture = allocateDepthTexture(target);
        GlStateManager.bindTexture(binding);
        GlStateManager.setActiveTexture(active);
    }

    void delete() {
        if (shaders != null) shaders.deleteShaderGroup();
        shaders = null;
        if (depthTexture != -1) TextureUtil.deleteTexture(depthTexture);
        depthTexture = -1;
        depthCapture.delete();
        initialized = false;
        valid = false;
    }

    private int allocateDepthTexture(Framebuffer framebuffer) {
        int texture = TextureUtil.glGenTextures();
        GlStateManager.bindTexture(texture);
        GL11.glTexParameteri(3553, 10241, 9728);
        GL11.glTexParameteri(3553, 10240, 9728);
        GL11.glTexParameteri(3553, 10242, 33071);
        GL11.glTexParameteri(3553, 10243, 33071);
        GL11.glTexImage2D(3553, 0, 33190, framebuffer.framebufferWidth,
                framebuffer.framebufferHeight, 0, 6402, 5126, (ByteBuffer) null);
        return texture;
    }
}
