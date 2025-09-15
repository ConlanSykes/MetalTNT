package org.sykes.metaltnt.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At;
import org.sykes.metaltnt.client.Metal.CVPixelBufferMount;
import org.spongepowered.asm.mixin.Unique;
/**
 * Mixin to intercept RenderSystem calls and redirect to Metal rendering
 * This is where we hook into Minecraft's rendering pipeline, and redirect it to our Metal rendering system.
 */
@Mixin(GameRenderer.class)
public class RenderSystemMixin
{

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger(RenderSystemMixin.class);
    //define Metal as not Called

    @Unique
    private static boolean isMetalCalled = false;


    /**
     * Redirect Clear method at Runtime at Invoke to call the Mount method and Frame rendering
     */

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"
            )
    )
    private void redirectClear(int mask, boolean getError) {
        if(!isMetalCalled) {
            isMetalCalled = true;

            //Try and mount CVPixelBuffer or return Exception
            try {
                CVPixelBufferMount.mount();
                LOGGER.info("✅ CVPixelBufferMount mounted successfully");
            } catch (Exception e) {
                LOGGER.error("❌ Failed to mount CVPixelBufferMount: {}", e.getMessage());
                throw new RuntimeException("CVPixelBufferMount mounting failed", e);

            }


        }
    }
}
