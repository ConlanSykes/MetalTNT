package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.MetalWorldRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.texture.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class GameRendererMixin {

    private static long offscreenClearCount = 0;
    private static long totalClearCount = 0;

    @Inject(method = "clear", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$onClear(int mask, boolean getError, CallbackInfo ci) {
        totalClearCount++;
        // When Metal is active in-game, handle ALL clears:
        // - Main framebuffer: cancel (Metal handles sky/clear)
        // - Offscreen targets: clear the persistent Metal RT
        if (!MetalRenderClient.isEnabled() || MetalRenderClient.getWorldRenderer() == null)
            return;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (net.minecraft.client.MinecraftClient.getInstance().world == null)
            return;

        GpuTextureView override = RenderSystem.outputColorTextureOverride;

        // Log ALL clears for diagnostics (first 50, then every 500th)
        if (totalClearCount <= 50 || totalClearCount % 500 == 0) {
            System.err.println("[MetalRender] GameRendererMixin: clear #" + totalClearCount
                    + " mask=0x" + Integer.toHexString(mask)
                    + " override=" + (override != null ? "YES" : "NO")
                    + " inFrame=" + renderer.isInFrame()
                    + " handle=" + renderer.getHandle());
        }

        if (override != null && renderer.isInFrame() && renderer.getHandle() != 0L) {
            // STORAGE BUCKET MODE: Do NOT clear the Metal offscreen RT on every frame.
            // The persistent RT acts as a "storage bucket" — items accumulate and are
            // overwritten only when new items render at the same position. This prevents
            // items from disappearing on tab switch due to MC's incremental rendering
            // (MC re-renders only animated items on subsequent frames, not all items).
            //
            // The shader's discard_fragment is disabled for offscreen draws (flag bit 2),
            // ensuring new items FULLY overwrite old content at the same cell.
            offscreenClearCount++;
            if (offscreenClearCount <= 20 || offscreenClearCount % 500 == 0) {
                int overrideGlId = 0;
                try {
                    GpuTexture gpuTex = override.texture();
                    if (gpuTex instanceof GlTexture glTex) {
                        overrideGlId = glTex.getGlId();
                    }
                } catch (Exception e) {
                    /* ignore */ }
                System.err.println("[MetalRender] GameRendererMixin: SKIPPED clear (storage bucket mode) atlas glId="
                        + overrideGlId + " (clear #" + offscreenClearCount + ")");
            }
            ci.cancel();
        } else if (override == null && renderer.isInFrame()) {
            // Main framebuffer clear: block it — Metal handles sky rendering
            // Only cancel when we're actually in a Metal frame; otherwise let GL clear
            // normally
            ci.cancel();
        }
        // If override set but Metal not in frame, let GL handle it
    }
}
