package com.metalrender.sodium.mixins.lwjgl;

import com.metalrender.MetalRenderClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block GL draw calls when Metal is active.
 * This prevents any GL geometry from being rendered — Metal handles everything.
 * GL texture operations (bind, upload, readback) are still allowed so we can
 * source texture data for Metal.
 */
@Pseudo
@Mixin(targets = {"org.lwjgl.opengl.GL11C"})
public class GL11CMixin {

  /**
   * Cancel glDrawElements when Metal is rendering in-world.
   * Blocks ALL GL draw calls — Metal handles both main framebuffer and offscreen (RTT).
   */
  private static long glBlockedOffscreenCount = 0;
  private static long glBlockedTotalCount = 0;

  @Inject(method = {"glDrawElements"}, at = { @At("HEAD") }, cancellable = true, remap = false, require = 0)
  private static void metalrender$onDrawElements(int mode, int count, int type,
                                                 long indicesOffset,
                                                 CallbackInfo ci) {
    // Block GL draws when Metal is active and we're in-world,
    // EXCEPT during offscreen atlas rendering (outputColorTextureOverride != null).
    // Offscreen draws target the GL atlas texture — we let GL populate it fully,
    // then snapshot the result to Metal for compositing. This is necessary because
    // MC 1.21's RenderDispatcher+RenderPass pipeline renders most items to the atlas
    // through a path that bypasses RenderLayer.draw(). Letting GL handle ALL atlas
    // draws ensures the complete atlas is available for Metal compositing.
    if (MetalRenderClient.isEnabled() && MetalRenderClient.getWorldRenderer() != null
        && net.minecraft.client.MinecraftClient.getInstance().world != null) {
      // Allow GL draws during offscreen rendering (outputColorTextureOverride != null).
      // MC renders items/player model via GL to an offscreen texture that gets
      // composited later. If we block these draws, the offscreen texture stays empty.
      if (com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride != null) {
        // RenderLayerMixin bound our offscreen FBO, but draw()'s render setup
        // re-bound FBO 0. Rebind right before the actual GL draw so items
        // render to the offscreen texture, not the screen.
        if (com.metalrender.render.TextureCacheManager.redirectFboForOffscreen
            && com.metalrender.render.TextureCacheManager.offscreenFboId > 0) {
          org.lwjgl.opengl.GL30.glBindFramebuffer(
              org.lwjgl.opengl.GL30.GL_FRAMEBUFFER,
              com.metalrender.render.TextureCacheManager.offscreenFboId);
        }
        return; // Let GL draw through for offscreen items
      }
      glBlockedTotalCount++;
      ci.cancel();
      return;
    }
  }
}
