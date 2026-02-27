package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.render.atlas.CapturedAtlas;
import com.metalrender.render.atlas.CapturedAtlasRepository;
import com.metalrender.render.atlas.SpriteAtlasCapture;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteAtlasTexture.class)
public abstract class SpriteAtlasTextureMixin {
  private static final Identifier BLOCKS_ATLAS_ID = Identifier.of("minecraft",
      "textures/atlas/blocks.png");

  @Shadow
  public abstract Identifier getId();

  @Inject(method = "create", at = @At("TAIL"))
  private void metalrender$captureAtlas(SpriteLoader.StitchResult stitchResult,
      CallbackInfo ci) {
    Identifier atlasId = this.getId();
    if (!BLOCKS_ATLAS_ID.equals(atlasId)) {
      return;
    }

    // At create() time, the GL texture is NOT yet uploaded (glId = 0).
    // We capture via manual blit as a FALLBACK (stored in repository).
    // The primary path is GL readback at render time (in MetalWorldRenderer.uploadAtlas()).
    SpriteAtlasTexture self = (SpriteAtlasTexture) (Object) this;
    java.util.Optional<CapturedAtlas> capturedOpt = SpriteAtlasCapture.capture(self);
    capturedOpt.ifPresent(atlas -> {
      CapturedAtlasRepository.store(atlasId, atlas);
      MetalLogger.info("[AtlasCapture] Manual blit fallback stored ({}x{})", atlas.width(), atlas.height());
    });

    // Signal the render thread to do GL readback (preferred path)
    MetalLogger.info("[AtlasCapture] Atlas create() — signalling render-time GL readback");
    triggerAtlasUpload(atlasId);
  }

  private static void triggerAtlasUpload(Identifier atlasId) {
    if (atlasId == null || !atlasId.equals(BLOCKS_ATLAS_ID)) {
      return;
    }

    MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
    if (renderer == null || !renderer.isReady()) {
      return;
    }

    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null) {
      return;
    }

    client.execute(renderer::forceAtlasReupload);
  }
}
