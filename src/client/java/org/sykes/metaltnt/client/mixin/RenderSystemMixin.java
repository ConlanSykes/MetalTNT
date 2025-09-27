package org.sykes.metaltnt.client.mixin;

import net.minecraft.client.render.GameRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.At;
import org.sykes.metaltnt.client.Metal.CVPixelBufferMount;

import com.mojang.blaze3d.platform.GlStateManager;

import org.spongepowered.asm.mixin.Unique;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Mixin to intercept RenderSystem calls and redirect to Metal rendering
 * This is where we hook into Minecraft's rendering pipeline, and redirect it to
 * our Metal rendering system.
 */
@Mixin(GameRenderer.class)
public class RenderSystemMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger(RenderSystemMixin.class);
    // define Metal as not Called

    @Unique
    private static boolean isMetalCalled = false;

    /**
     * Redirect Clear method at Runtime at Invoke to call the Mount method and Frame
     * rendering
     */

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void redirectClear(int mask, boolean getError) {

        if (!isMetalCalled) {
            isMetalCalled = true;

            // Try and mount CVPixelBuffer or return Exce ption
            try {
                CVPixelBufferMount.mount();
                LOGGER.info("✅ CVPixelBufferMount mounted successfully");
            } catch (Exception e) {
                LOGGER.error("❌ Failed to mount CVPixelBufferMount: {}", e.getMessage());
                throw new RuntimeException("CVPixelBufferMount mounting failed", e);
            }
        }

        /*
         * What RenderSystem.clear() Controls
         * The clear() method takes two parameters:
         * 
         * mask - What buffers to clear (bit flags)
         * 
         * getError - Whether to check for OpenGL errors
         * The mask Parameter (Bit Flags)
         * The mask is a combination of OpenGL constants that tell the GPU what to
         * clear:
         * GL_COLOR_BUFFER_BIT (0x00004000)
         * Clears the color buffer (the actual pixels you see)
         * Sets the background color (usually black or sky color)
         * Most important - this is what makes the screen "blank" before drawing
         * 
         * GL_DEPTH_BUFFER_BIT (0x00000100)
         * Clears the depth buffer (Z-buffer)
         * Resets depth testing for the new frame
         * Prevents objects from being drawn behind others incorrectly
         * 
         * GL_STENCIL_BUFFER_BIT (0x00000400)
         * Clears the stencil buffer (used for advanced rendering effects)
         * Less common in basic rendering
         */

        // TODO: Phase 2 - Replace with Metal rendering
        // When ready, replace this line with:
        // CVPixelBufferMount.clearMetal(mask, getError);

        // For now, use original OpenGL clear to maintain normal rendering
        com.mojang.blaze3d.systems.RenderSystem.clear(mask, getError);
        CVPixelBufferMount.renderFrame();

    }
}
