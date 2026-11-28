package com.potassium.mixin;

import com.potassium.module.MotionBlur;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The four points in {@code EntityRenderer} OpenAdjust's fork calls the motion blur from:
 * before the frame, after the camera matrices, at the end of the world pass, and after the
 * world is on screen.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void potassium$motionBlurBeginFrame(float partialTicks, long nanoTime, CallbackInfo ci) {
        if (MotionBlur.INSTANCE != null) MotionBlur.INSTANCE.renderer.beginFrame();
    }

    @Inject(method = "updateCameraAndRender",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;renderWorld(FJ)V",
                    shift = At.Shift.AFTER))
    private void potassium$motionBlurPost(float partialTicks, long nanoTime, CallbackInfo ci) {
        if (MotionBlur.INSTANCE != null) MotionBlur.INSTANCE.renderer.renderPost();
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ActiveRenderInfo;updateRenderInfo(Lnet/minecraft/entity/EntityPlayer;Z)V",
                    shift = At.Shift.AFTER))
    private void potassium$motionBlurCaptureMatrices(int pass, float partialTicks,
                                                     long finishTimeNano, CallbackInfo ci) {
        if (MotionBlur.INSTANCE != null) MotionBlur.INSTANCE.renderer.captureMatrices(partialTicks);
    }

    @Inject(method = "renderWorld", at = @At("RETURN"))
    private void potassium$motionBlurMonkey(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (MotionBlur.INSTANCE != null) MotionBlur.INSTANCE.renderer.renderMonkey();
    }

    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void potassium$motionBlurReload(IResourceManager resourceManager, CallbackInfo ci) {
        if (MotionBlur.INSTANCE != null) MotionBlur.INSTANCE.renderer.destroy();
    }
}
