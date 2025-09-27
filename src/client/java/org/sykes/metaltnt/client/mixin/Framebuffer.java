package org.sykes.metaltnt.client.mixin;

import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.sykes.metaltnt.client.Metal.CVPixelBufferMount;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;

@Mixin(GL30.class)
public class Framebuffer {
    /**
     * @author MetalTNT
     * @reason Redirect framebuffer creation to use Metal texture
     */
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {

        System.out.println("glGenFramebuffers");
        // Get the original framebuffer ID
        int[] fbo = new int[1];
        GL30.glGenFramebuffers(fbo);
        int originalFBO = fbo[0];

        // Ensure the Metal bridge is mounted before requesting the texture
        try {
            if (!CVPixelBufferMount.isMounted()) {
                CVPixelBufferMount.mount();
            }
        } catch (RuntimeException ex) {
            System.err.println("CVPixelBufferMount.mount() failed: " + ex.getMessage());
        }

        // Get the OpenGL texture
        int metalTexture = CVPixelBufferMount.isMounted() ? CVPixelBufferMount.getOpenGLTexture() : 0;

        if (metalTexture != 0) {
            // Bind the framebuffer
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, originalFBO);

            // Attach our texture
            GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0,
                    GlConst.GL_TEXTURE_2D, metalTexture, 0);
        } else {
            System.out.println("metalTexture is 0");
        }

        return originalFBO;
    }
}