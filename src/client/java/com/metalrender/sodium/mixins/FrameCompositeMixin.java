package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks GameRenderer to:
 * 1. Switch to UI rendering phase after world rendering completes
 * 2. Composite and present the Metal frame after all rendering is done
 */
@Mixin(GameRenderer.class)
public class FrameCompositeMixin {

    /**
     * After renderWorld() finishes, switch Metal to UI phase.
     * All subsequent RenderLayer.draw() calls (HUD, screens, etc.)
     * will use an orthographic projection matrix.
     */
    @Inject(method = "renderWorld", at = @At("TAIL"))
    private void metalrender$onWorldRenderDone(net.minecraft.client.render.RenderTickCounter tickCounter, CallbackInfo ci) {
        if (MetalRenderClient.isEnabled()) {
            MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
            if (renderer != null && renderer.isInFrame()) {
                renderer.beginUIPhase();
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void metalrender$compositeOverlay(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (MetalRenderClient.isEnabled()) {
            MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
            if (renderer != null) {
                renderer.compositeOverlay();
            }
        }
    }
}
