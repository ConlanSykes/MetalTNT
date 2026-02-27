package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.sodium.mixins.accessor.RenderLayerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Intercepts ALL non-terrain draw calls at the RenderLayer.draw() level.
 * Converts vertex data to a unified 32-byte format (float3 pos + ubyte4 color + float2 uv + float2 light)
 * and queues Metal draw commands instead of letting GL handle the rendering.
 *
 * This covers entities, UI, particles, weather, block entities — everything
 * that goes through MC's VertexConsumerProvider → BufferBuilder → RenderLayer pipeline.
 */
@Mixin(RenderLayer.class)
public class RenderLayerMixin {

    private static long queuedCount = 0;
    private static long skippedCount = 0;

    // Reference to shared texture upload cache (cleared each frame by TextureCacheManager).
    // Using the shared set from TextureCacheManager so MetalWorldRenderer can clear it.
    private static final Set<Integer> uploadedTextures = com.metalrender.render.TextureCacheManager.uploadedTextures;

    /**
     * Detect if this RenderLayer is an entity shadow layer.
     * Shadow layers use multiply blending to darken the ground under entities.
     */
    private boolean isShadowLayer() {
        try {
            String name = this.toString();
            return name != null && name.contains("shadow");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the GL texture ID for this RenderLayer by accessing the RenderSetup's texture info.
     * Returns 0 if no texture could be resolved.
     */
    private int getGlTextureId() {
        try {
            RenderSetup setup = ((RenderLayerAccessor) this).metalrender$getRenderSetup();
            if (setup == null) return 0;

            Map<String, RenderSetup.Texture> resolved = setup.resolveTextures();
            if (resolved == null || resolved.isEmpty()) return 0;

            // Try "Sampler0" first (main texture), fall back to first entry
            RenderSetup.Texture tex = resolved.get("Sampler0");
            if (tex == null) {
                tex = resolved.values().iterator().next();
            }

            GpuTextureView view = tex.textureView();
            if (view == null) return 0;

            GpuTexture gpuTex = view.texture();
            if (gpuTex instanceof GlTexture glTex) {
                return glTex.getGlId();
            }
        } catch (Exception e) {
            // Silently fall back to atlas
        }
        return 0;
    }

    /**
     * Read the GL texture pixels and upload to Metal if not already cached.
     */
    private void ensureTextureUploaded(long handle, int glTexId) {
        if (glTexId <= 0 || uploadedTextures.contains(glTexId)) return;

        try {
            // Save current GL texture binding
            int prevTex = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);

            // Bind the texture we want to read
            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glTexId);

            // Get dimensions
            int w = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
            int h = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);

            if (w > 0 && h > 0 && w <= 8192 && h <= 8192) {
                int bufSize = w * h * 4;
                ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(bufSize);

                // Read as RGBA (matches Metal's RGBA8Unorm in nUploadGenericTexture)
                org.lwjgl.opengl.GL11.glGetTexImage(
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                        org.lwjgl.opengl.GL11.GL_RGBA,
                        org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, buf);

                byte[] pixels = new byte[bufSize];
                buf.rewind();
                buf.get(pixels);
                org.lwjgl.system.MemoryUtil.memFree(buf);

                // Upload to Metal
                int result = NativeBridge.nUploadGenericTexture(handle, glTexId, w, h, pixels);
                if (result >= 0) {
                    uploadedTextures.add(glTexId);
                    if (uploadedTextures.size() <= 30) {
                        System.err.println("[MetalRender] Uploaded entity texture glId=" + glTexId
                                + " (" + w + "x" + h + "), total cached: " + uploadedTextures.size());
                    }
                }
            }

            // Restore previous GL texture binding
            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex);
        } catch (Exception e) {
            if (uploadedTextures.size() < 5) {
                System.err.println("[MetalRender] Failed to upload texture glId=" + glTexId + ": " + e.getMessage());
            }
        }
    }

    private static long offscreenPassCount = 0;

    // FBO fields no longer used — MC's createRenderPass handles FBO lifecycle.
    // Kept as unused for potential future debugging needs.
    // private static int offscreenFbo = 0;
    // private static int offscreenDepthRbo = 0;
    // private static boolean offscreenFboBound = false;
    // private static int prevDrawFbo = 0;
    // private static final int[] prevViewport = new int[4];
    // private static int lastDepthW = 0, lastDepthH = 0;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptDraw(BuiltBuffer builtBuffer, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled()) return;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer == null || renderer.getHandle() == 0L) return;

        // Detect offscreen rendering (item models rendered to small buffer)
        com.mojang.blaze3d.textures.GpuTextureView offscreenOverride =
                com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride;
        boolean isOffscreen = (offscreenOverride != null);

        // ---- OFFSCREEN: Let GL handle it natively ----
        // MC renders items/player model to offscreen GL textures, then composites
        // them as textured quads via GuiRenderStateMixin.
        // We MUST return IMMEDIATELY — before touching the BuiltBuffer or any GL
        // state — so GL's original draw() gets a pristine environment.
        // If we read the buffer or modify GL texture bindings first, we corrupt
        // the state GL needs to render items correctly.
        if (isOffscreen) {
            // MC's createRenderPass() handles FBO creation/binding internally.
            // We just track the override texture ID for deferred readback.
            // CRITICAL: Do NOT modify any GL state here — our previous FBO setup
            // was corrupting MC's item rendering, causing alpha-only data (black items)
            // when the atlas grew from 512x512 to 1024x1024.
            try {
                int overrideTexId = -1;
                GpuTexture gpuTex = offscreenOverride.texture();
                if (gpuTex instanceof GlTexture glTex) {
                    overrideTexId = glTex.getGlId();
                }

                if (overrideTexId > 0) {
                    // Bump draw version for this texture so GuiRenderStateMixin
                    // knows to re-upload it when the next compositing quad arrives.
                    com.metalrender.render.TextureCacheManager.offscreenDrawVersion.merge(
                            overrideTexId, 1, Integer::sum);

                    offscreenPassCount++;
                    if (offscreenPassCount <= 5 || offscreenPassCount % 500 == 0) {
                        System.err.println("[MetalRender] OFFSCREEN-PASS #" + offscreenPassCount
                                + " tex=" + overrideTexId + " (GL handles FBO)");
                    }
                }
            } catch (Exception e) {
                if (offscreenPassCount <= 5) {
                    System.err.println("[MetalRender] OFFSCREEN-PASS error: " + e);
                    e.printStackTrace(System.err);
                }
            }
            return; // Let MC's createRenderPass handle FBO + draw
        }

        // CRITICAL: If offscreen draws arrive when we're NOT in-frame, we MUST still
        // handle them. Otherwise items rendered to the atlas before renderFrame() are lost.
        if (!renderer.isInFrame()) {
            // Not in frame — skip non-offscreen draws (title screen etc.)
            return;
        }

        try {
            long handle = renderer.getHandle();
            BuiltBuffer.DrawParameters params = builtBuffer.getDrawParameters();
            VertexFormat format = params.format();
            int vertexCount = params.vertexCount();

            if (vertexCount <= 0) {
                ci.cancel();
                return;
            }

            ByteBuffer srcBuffer = builtBuffer.getBuffer();
            if (srcBuffer == null || !srcBuffer.hasRemaining()) {
                ci.cancel();
                return;
            }

            int srcVertexSize = format.getVertexSize();
            if (srcVertexSize <= 0) {
                ci.cancel();
                return;
            }

            // Convert to unified 32-byte format: float3 pos (12) + ubyte4 color (4) + float2 uv (8) + float2 light (8)
            byte[] unifiedData = convertToUnifiedFormat(srcBuffer, format, vertexCount, params.mode());
            if (unifiedData == null || unifiedData.length == 0) {
                ci.cancel();
                return;
            }

            int unifiedVertexCount = unifiedData.length / 32;

            // Get the GL texture ID for this render layer and ensure it's uploaded to Metal
            int glTexId = getGlTextureId();
            if (glTexId > 0) {
                // Font atlas textures are dynamic — glyphs are lazily rasterized.
                // When processing text draws, force re-upload to capture any new glyphs.
                if (MetalWorldRenderer.isTextOverlay && uploadedTextures.contains(glTexId)) {
                    uploadedTextures.remove(glTexId);
                }
                ensureTextureUploaded(handle, glTexId);
            }

            // ---- MAIN FRAMEBUFFER: normal deferred draw path ----
            // NOTE: Don't end offscreen pass here (transition handler removed).
            // The offscreen pass stays open while items are being interleaved with
            // compositing quads. compositeOverlay() is the ONLY place that ends it.

            // Get the effective projection matrix for this draw.
            // For WORLD phase (0): frameProjection already includes projection * viewRotation,
            //   so we do NOT apply RenderSystem.getModelViewMatrix() (would double-apply camera).
            // For UI phase (1): orthoProjectionMatrix is pure orthographic.
            //   MC's RenderLayer.draw() applies RenderSystem.getModelViewMatrix() via shader
            //   uniforms. For inventory entities (player model, item entities),
            //   drawEntity() sets up modelView with positioning/scaling.
            //   We MUST capture and apply it or inventory entities render with wrong transform.
            float[] drawMatrix;
            int phase = renderer.getRenderPhase();
            {
                float[] baseProjMatrix = renderer.getEffectiveMatrix();
                if (phase == 1) {
                    // UI phase: combine ortho projection with MC's modelView matrix
                    org.joml.Matrix4f modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix();
                    if (modelView != null && !modelView.equals(new org.joml.Matrix4f())) {
                        // modelView is non-identity — apply it
                        org.joml.Matrix4f baseProj = new org.joml.Matrix4f();
                        baseProj.set(baseProjMatrix);
                        org.joml.Matrix4f combined = new org.joml.Matrix4f();
                        baseProj.mul(modelView, combined);
                        drawMatrix = new float[16];
                        combined.get(drawMatrix);
                    } else {
                        drawMatrix = baseProjMatrix;
                    }
                } else {
                    // World phase: frameProjection already includes viewRotation
                    drawMatrix = baseProjMatrix;
                }
            }

            // blendMode: 1=alpha (3D with depth), 2=UI (alpha, no depth test),
            //            3=text overlay (drawn after UI), 4=shadow (multiply blend, depth bias)
            int blendMode;
            if (phase == 0) {
                if (isShadowLayer()) {
                    blendMode = 4; // Entity shadow — multiply blend with depth bias
                } else {
                    blendMode = 1;
                }
            } else if (MetalWorldRenderer.isTextOverlay) {
                blendMode = 3; // text overlay — sorted to draw AFTER items/sprites
            } else {
                blendMode = 2;
            }

            // Queue the draw command with Metal using the actual texture ID and matrix
            NativeBridge.nQueueGenericDraw(handle, unifiedData, unifiedVertexCount,
                    glTexId, blendMode, drawMatrix);

            queuedCount++;
            // Detailed diagnostic: log every draw for first 200 draws per frame
            // to trace texture and phase assignments
            if (queuedCount <= 200 || (queuedCount % 5000) == 1) {
                String layerName = "?";
                try { layerName = ((RenderLayer)(Object)this).toString(); } catch (Exception ignored) {}
                System.err.println("[MetalRender] DRAW-DIAG #" + queuedCount
                        + " layer=" + layerName
                        + " tex=" + glTexId
                        + " phase=" + phase
                        + " blend=" + blendMode
                        + " offscreen=" + isOffscreen
                        + " verts=" + unifiedVertexCount
                        + " isText=" + MetalWorldRenderer.isTextOverlay);
            }

            // Cancel the GL draw — Metal handles it now
            ci.cancel();

        } catch (Throwable t) {
            // On error, let GL handle it
            skippedCount++;
            if ((skippedCount % 1000) == 1) {
                System.err.println("[MetalRender] RenderLayerMixin: error intercepting draw: " + t.getMessage());
                t.printStackTrace(System.err);
            }
        }
    }

    /**
     * Convert MC vertex data from any supported format to our unified 32-byte format.
     * Handles QUADS→TRIANGLES conversion.
     *
     * Unified format per vertex (32 bytes):
     *   float3 position  (12 bytes) at offset 0
     *   ubyte4 color     (4 bytes)  at offset 12
     *   float2 uv        (8 bytes)  at offset 16
     *   float2 light     (8 bytes)  at offset 24  (blockLight, skyLight in 0-1)
     */
    private static byte[] convertToUnifiedFormat(ByteBuffer src, VertexFormat format,
                                                  int vertexCount, VertexFormat.DrawMode mode) {
        int srcVertexSize = format.getVertexSize();
        int srcPos = src.position();

        // Determine element offsets within the source vertex
        int posOffset = -1, colorOffset = -1, uvOffset = -1, lightOffset = -1;
        int elementsMask = format.getElementsMask();
        int[] offsets = format.getOffsetsByElement();

        // POSITION element (id=0): float3
        if (VertexFormatElement.POSITION != null) {
            int elemOffset = format.getOffset(VertexFormatElement.POSITION);
            if (elemOffset >= 0) posOffset = elemOffset;
        }

        // COLOR element (id=1): ubyte4
        if (VertexFormatElement.COLOR != null) {
            try {
                int elemOffset = format.getOffset(VertexFormatElement.COLOR);
                if (elemOffset >= 0) colorOffset = elemOffset;
            } catch (Exception e) { /* element not in format */ }
        }

        // UV0 element (id=2): float2
        if (VertexFormatElement.UV0 != null) {
            try {
                int elemOffset = format.getOffset(VertexFormatElement.UV0);
                if (elemOffset >= 0) uvOffset = elemOffset;
            } catch (Exception e) { /* element not in format */ }
        }

        // UV2 / LIGHT element: short2 packed light (blockLight, skyLight)
        // Minecraft encodes per-vertex lighting here as (lightLevel << 4) in range 0-240
        if (VertexFormatElement.UV2 != null) {
            try {
                int elemOffset = format.getOffset(VertexFormatElement.UV2);
                if (elemOffset >= 0) lightOffset = elemOffset;
            } catch (Exception e) { /* element not in format */ }
        }

        if (posOffset < 0) {
            // Can't render without position
            return null;
        }

        // Handle QUADS → TRIANGLES conversion
        // Quads: 4 vertices → 2 triangles = 6 vertices
        int outputVertexCount;
        boolean isQuads = (mode == VertexFormat.DrawMode.QUADS);
        if (isQuads) {
            int quadCount = vertexCount / 4;
            outputVertexCount = quadCount * 6;
        } else {
            outputVertexCount = vertexCount;
        }

        byte[] output = new byte[outputVertexCount * 32];
        ByteBuffer outBuf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);

        if (isQuads) {
            // Convert quads to triangles: 0,1,2 + 0,2,3
            int quadCount = vertexCount / 4;
            for (int q = 0; q < quadCount; q++) {
                int[] indices = {0, 1, 2, 0, 2, 3};
                for (int idx : indices) {
                    int vertIdx = q * 4 + idx;
                    writeUnifiedVertex(src, srcPos, srcVertexSize, vertIdx,
                            posOffset, colorOffset, uvOffset, lightOffset, outBuf);
                }
            }
        } else {
            // Triangles, triangle strip, etc. — copy directly
            for (int v = 0; v < vertexCount; v++) {
                writeUnifiedVertex(src, srcPos, srcVertexSize, v,
                        posOffset, colorOffset, uvOffset, lightOffset, outBuf);
            }
        }

        return output;
    }

    private static void writeUnifiedVertex(ByteBuffer src, int srcBasePos, int srcVertexSize,
                                            int vertIndex, int posOffset, int colorOffset,
                                            int uvOffset, int lightOffset, ByteBuffer out) {
        int vertBase = srcBasePos + vertIndex * srcVertexSize;

        // Position: float3
        if (posOffset >= 0 && vertBase + posOffset + 12 <= src.limit()) {
            out.putFloat(src.getFloat(vertBase + posOffset));
            out.putFloat(src.getFloat(vertBase + posOffset + 4));
            out.putFloat(src.getFloat(vertBase + posOffset + 8));
        } else {
            out.putFloat(0f);
            out.putFloat(0f);
            out.putFloat(0f);
        }

        // Color: ubyte4 RGBA
        if (colorOffset >= 0 && vertBase + colorOffset + 4 <= src.limit()) {
            out.put(src.get(vertBase + colorOffset));     // R
            out.put(src.get(vertBase + colorOffset + 1)); // G
            out.put(src.get(vertBase + colorOffset + 2)); // B
            out.put(src.get(vertBase + colorOffset + 3)); // A
        } else {
            // Default: white opaque
            out.put((byte) 255);
            out.put((byte) 255);
            out.put((byte) 255);
            out.put((byte) 255);
        }

        // UV: float2
        if (uvOffset >= 0 && vertBase + uvOffset + 8 <= src.limit()) {
            out.putFloat(src.getFloat(vertBase + uvOffset));
            out.putFloat(src.getFloat(vertBase + uvOffset + 4));
        } else {
            out.putFloat(0f);
            out.putFloat(0f);
        }

        // Light: float2 (blockLight, skyLight) normalized 0-1
        // MC's UV2/LIGHT stores short2 with values in range 0-240 (lightLevel << 4)
        if (lightOffset >= 0 && vertBase + lightOffset + 4 <= src.limit()) {
            short rawBlock = src.getShort(vertBase + lightOffset);
            short rawSky = src.getShort(vertBase + lightOffset + 2);
            // Normalize: 240 = max light (level 15 << 4), map to 0-1
            float blockLight = Math.min(1.0f, Math.max(0.0f, (rawBlock & 0xFFFF) / 240.0f));
            float skyLight = Math.min(1.0f, Math.max(0.0f, (rawSky & 0xFFFF) / 240.0f));
            out.putFloat(blockLight);
            out.putFloat(skyLight);
        } else {
            // No light data — default to full brightness (outdoor sunlight)
            out.putFloat(0.0f);
            out.putFloat(1.0f);
        }
    }

    /**
     * After the original draw() completes for offscreen renders.
     * Since we no longer bind our own FBO, no restoration is needed.
     */
    @Inject(method = "draw", at = @At("RETURN"))
    private void metalrender$afterDraw(BuiltBuffer builtBuffer, CallbackInfo ci) {
        // No-op: MC's createRenderPass handles its own FBO lifecycle.
        // Previously, we bound our own FBO and needed to restore prev state here.
    }

    /**
     * Get an orthographic projection matrix for offscreen item atlas rendering.
     * MC renders items to an atlas texture using an ortho projection that maps
     * atlas pixel coordinates to NDC.
     * zZeroToOne=true for Metal's [0,1] depth range.
     */
    private static float[] getMetalDepthProjection() {
        int offW = MetalWorldRenderer.offscreenWidth;
        int offH = MetalWorldRenderer.offscreenHeight;
        if (offW <= 0) offW = 64;
        if (offH <= 0) offH = 64;
        // Match MC's GUI-like orthographic projection for the item atlas:
        // left=0, right=atlasW, top=0, bottom=atlasH, near=-2000, far=3000
        // Y is top→bottom (standard MC screen coords), zZeroToOne for Metal
        Matrix4f ortho = new Matrix4f();
        ortho.setOrtho(0.0f, (float)offW, (float)offH, 0.0f, -2000.0f, 3000.0f, true);
        float[] result = new float[16];
        ortho.get(result);
        return result;
    }
}
