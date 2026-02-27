package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels GL sky rendering when Metal is active.
 * Metal handles the sky via its clear color (sky blue).
 * Without this, the GL sky would fill the framebuffer with opaque pixels,
 * preventing the transparent alpha compositing from working correctly.
 */
@Mixin(WorldRenderer.class)
public class SkyRenderMixin {

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void metalrender$cancelSky(CallbackInfo ci) {
        if (MetalRenderClient.isEnabled() && MetalRenderClient.getWorldRenderer() != null) {
            ci.cancel();
        }
    }
}
