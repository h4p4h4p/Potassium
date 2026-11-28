package com.potassium.module.render.motionblur;

import com.potassium.mixin.ShaderGroupAccessor;
import com.potassium.module.MotionBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

/**
 * OpenAdjust's {@code MotionBlurRenderer}: one frame of state shared by the three blurs.
 *
 * <p>The original is called from a patched {@code EntityRenderer}; this port is driven by
 * {@link com.potassium.mixin.MixinEntityRenderer} at the same points in the frame.</p>
 */
public final class MotionBlurRenderer {

    private final MotionBlur module;
    private final MonkeyBlurShader monkey = new MonkeyBlurShader();
    private final MoulberryBlur moulberry = new MoulberryBlur();

    private ShaderGroup phosphor;
    private Framebuffer lastFramebuffer;
    private World lastWorld;
    private Entity lastCamera;
    private MotionBlurMode lastMode;
    private float lastStrength;
    private long lastTime;
    private int lastWidth;
    private int lastHeight;
    private int lastPerspective;
    private boolean active;
    private boolean phosphorHistory;
    private boolean failed;
    private boolean matrixCaptured;

    public MotionBlurRenderer(MotionBlur module) {
        this.module = module;
    }

    public void beginFrame() {
        active = false;
        matrixCaptured = false;
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        if (!module.isEnabled() || mc.theWorld == null || camera == null || mc.skipRenderWorld
                || !OpenGlHelper.isFramebufferEnabled() || mc.gameSettings.anaglyph) {
            reset();
            return;
        }
        Framebuffer target = mc.getFramebuffer();
        if (target.framebufferWidth < 1 || target.framebufferHeight < 1) {
            reset();
            return;
        }
        long now = System.nanoTime();
        MotionBlurMode mode = mode();
        float strength = module.getStrength();
        if (target != lastFramebuffer || target.framebufferWidth != lastWidth
                || target.framebufferHeight != lastHeight || mc.theWorld != lastWorld
                || camera != lastCamera
                || mc.gameSettings.thirdPersonView != lastPerspective
                || mode != lastMode || strength != lastStrength
                || lastTime != 0L && now - lastTime >= 100000001L) {
            reset();
        }
        lastFramebuffer = target;
        lastWidth = target.framebufferWidth;
        lastHeight = target.framebufferHeight;
        lastWorld = mc.theWorld;
        lastCamera = camera;
        lastPerspective = mc.gameSettings.thirdPersonView;
        lastMode = mode;
        lastStrength = strength;
        lastTime = now;
        active = !failed && (mode == MotionBlurMode.MOULBERRY || OpenGlHelper.shadersSupported);
        monkey.valid = false;
        monkey.depthCapture.prepared = false;
    }

    public void captureMatrices(float partialTicks) {
        if (active && lastMode == MotionBlurMode.MONKEY) {
            monkey.updateMatrices(lastCamera, partialTicks);
            matrixCaptured = true;
        }
    }

    public void renderMonkey() {
        matrixCaptured = false;
        if (active && lastMode == MotionBlurMode.MONKEY) {
            try {
                monkey.render(lastFramebuffer, lastStrength);
            } catch (Exception error) {
                fail(error);
            }
        }
    }

    public void renderPost() {
        if (!active) return;
        try {
            if (lastMode == MotionBlurMode.MOULBERRY) {
                moulberry.render(lastFramebuffer, lastStrength);
            } else if (lastMode == MotionBlurMode.PHOSPHOR) {
                if (phosphor == null) {
                    phosphor = loadShader(lastFramebuffer, "phosphor_motion_blur");
                }
                float blend = phosphorHistory
                        ? lastStrength / 100.0F * 3.0F + 0.69999999F - 0.0099999998F : 0.0F;
                for (Shader shader : ((ShaderGroupAccessor) (Object) phosphor).getShaders()) {
                    shader.getShaderManager().getShaderUniformOrDefault("BlendFactor").set(blend);
                    shader.getShaderManager().getShaderUniformOrDefault("Mode").set(0.0F);
                }
                renderShader(phosphor);
                phosphorHistory = true;
            }
        } catch (Exception error) {
            fail(error);
        }
    }

    /** The resource reload and the module toggle both drop every GL object. */
    public void reset() {
        destroy();
        lastFramebuffer = null;
        lastWorld = null;
        lastCamera = null;
        lastTime = 0L;
        active = false;
        failed = false;
        matrixCaptured = false;
    }

    private void fail(Throwable error) {
        destroy();
        active = false;
        matrixCaptured = false;
        failed = true;
        Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(true);
        System.err.println("[Potassium] Could not load or render motion blur: " + error);
        error.printStackTrace();
    }

    public static void renderShader(ShaderGroup group) {
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glMatrixMode(5890);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            group.loadShaderGroup(0.0F);
        } finally {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glMatrixMode(5890);
            GL11.glPopMatrix();
            GL11.glMatrixMode(5888);
            for (int unit = 3; unit >= 1; unit--) {
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + unit);
                GlStateManager.bindTexture(0);
                GlStateManager.disableTexture2D();
            }
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1, 1, 1, 1);
            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.enableAlpha();
            ((ShaderGroupAccessor) (Object) group).getMainFramebuffer().bindFramebuffer(true);
        }
    }

    public static ShaderGroup loadShader(Framebuffer target, String name) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        ShaderGroup group = new ShaderGroup(mc.getTextureManager(), mc.getResourceManager(),
                target, new ResourceLocation("shaders/post/potassium_" + name + ".json"));
        try {
            group.createBindFramebuffers(target.framebufferWidth, target.framebufferHeight);
            return group;
        } catch (RuntimeException error) {
            group.deleteShaderGroup();
            throw error;
        }
    }

    public void destroy() {
        if (phosphor != null) phosphor.deleteShaderGroup();
        phosphor = null;
        monkey.delete();
        moulberry.delete();
        phosphorHistory = false;
    }

    private MotionBlurMode mode() {
        return MotionBlurMode.byName(module.mode.getValue());
    }
}
