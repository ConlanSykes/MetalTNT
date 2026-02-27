package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.ItemRenderCache;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.sodium.mixins.accessor.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.gui.render.state.TextGuiElementRenderState;
import net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/**
 * Intercepts GuiRenderState.addText() to capture text that bypasses
 * DrawContext.drawText().
 *
 * In MC 1.21.11, menu button text goes through:
 * ButtonWidget.drawIcon() → drawLabel(DrawnTextConsumer) →
 * TextConsumerImpl.text() → GuiRenderState.addText()
 *
 * This completely bypasses DrawContext.drawText(), which our DrawContextMixin
 * hooks.
 * By intercepting at the GuiRenderState level, we catch ALL text regardless of
 * source.
 *
 * HUD text that goes through DrawContext.drawText() is already cancelled by
 * DrawContextMixin before it reaches addText(), so there's no
 * double-processing.
 */
@Mixin(GuiRenderState.class)
public class GuiRenderStateMixin {

    private static long menuTextDrawCount = 0;
    private static long addSimpleDiagCount = 0;

    /**
     * DIAGNOSTIC: Intercept addSimpleElement to see which elements reach
     * GuiRenderState.
     * This catches elements that bypass or fall through DrawContextMixin.
     */
    @Inject(method = "addSimpleElement", at = @At("HEAD"))
    private void metalrender$diagAddSimpleElement(SimpleGuiElementRenderState state, CallbackInfo ci) {
        if (addSimpleDiagCount < 300) {
            net.minecraft.client.gui.screen.Screen screen = MinecraftClient.getInstance().currentScreen;
            if (screen != null && !screen.getClass().getSimpleName().equals("MessageScreen")) {
                String screenName = screen.getClass().getSimpleName();
                String type = state.getClass().getSimpleName();
                String details = "";
                if (state instanceof TexturedQuadGuiElementRenderState tq) {
                    int texId = 0;
                    TextureSetup ts = tq.textureSetup();
                    if (ts != null) {
                        GpuTextureView tv = ts.texure0();
                        if (tv != null) {
                            GpuTexture gt = tv.texture();
                            if (gt instanceof GlTexture glt)
                                texId = glt.getGlId();
                        }
                    }
                    details = " pos=(" + tq.x1() + "," + tq.y1() + ")-(" + tq.x2() + "," + tq.y2() + ")"
                            + " uv=(" + tq.u1() + "," + tq.u2() + "," + tq.v1() + "," + tq.v2() + ")"
                            + " tex=" + texId + " w=" + (tq.x2() - tq.x1()) + " h=" + (tq.y2() - tq.y1());
                }
                addSimpleDiagCount++;
                System.err.println("[BTN-DIAG-ASE] addSimpleElement #" + addSimpleDiagCount
                        + " screen=" + screenName + " type=" + type + details);
            }
        }
    }

    @Inject(method = "addText", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptAddText(TextGuiElementRenderState state, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer == null || renderer.getHandle() == 0L || !renderer.isInFrame())
            return;

        try {
            // Extract text rendering parameters from the render state
            TextRenderer textRenderer = state.textRenderer;
            net.minecraft.text.OrderedText orderedText = state.orderedText;
            Matrix3x2fc matrix = state.matrix;
            int x = state.x;
            int y = state.y;
            int color = state.color;
            boolean shadow = state.shadow;

            // Convert 2D pose matrix to 4x4 matrix for TextRenderer
            // (same approach as GlyphGuiElementRenderState and DrawContextMixin)
            Matrix4f matrix4f = new Matrix4f().mul(matrix);

            // Use entity VCP — same approach as DrawContextMixin text intercept
            VertexConsumerProvider.Immediate vcp = MinecraftClient.getInstance()
                    .getBufferBuilders().getEntityVertexConsumers();

            // Mark as text overlay BEFORE draw so any auto-flushed layers
            // during textRenderer.draw() also get blendMode=3.
            MetalWorldRenderer.isTextOverlay = true;

            // Render text through VCP → RenderLayer.draw → RenderLayerMixin → Metal
            textRenderer.draw(orderedText, (float) x, (float) y, color, shadow, matrix4f, vcp,
                    TextRenderer.TextLayerType.SEE_THROUGH, 0, 15728880);

            // Flush VCP immediately — triggers RenderLayer.draw() for each text RenderLayer
            vcp.draw();
            MetalWorldRenderer.isTextOverlay = false;

            menuTextDrawCount++;
            if (menuTextDrawCount <= 10 || menuTextDrawCount % 5000 == 0) {
                System.err.println("[MetalRender] GuiRenderStateMixin: menu text #" + menuTextDrawCount
                        + " at (" + x + "," + y + ") color=0x" + Integer.toHexString(color)
                        + " shadow=" + shadow);
            }
        } catch (Throwable t) {
            // On error, let original path handle it
            if (menuTextDrawCount <= 5) {
                System.err.println("[MetalRender] GuiRenderStateMixin text error: " + t.getMessage());
                t.printStackTrace(System.err);
            }
            return; // Don't cancel — let vanilla handle it
        }

        // Cancel original — prevents text from going to GuiRenderer → GL
        ci.cancel();
    }

    // ====================================================================
    // Item atlas and entity composite quad intercept
    // ====================================================================

    // Track per-frame counts for diagnostic logging
    private static long frameQuadCount = 0;
    private static long frameQuadBranchOffscreen = 0;
    private static long frameQuadBranchSnapshot = 0;
    private static long frameQuadBranchFallback = 0;
    private static long frameQuadBranchGlReadback = 0;
    private static long lastFrameNumber = -1;

    // Track GL textures that need per-frame re-upload (item atlas, entity
    // offscreen)
    private static final Set<Integer> volatileTextures = new HashSet<>();
    // Per-frame dedup: track the offscreen draw version at which we last uploaded
    // each texture
    private static final java.util.Map<Integer, Integer> lastUploadedVersion = new java.util.HashMap<>();
    private static long simpleElementCount = 0;
    // Per-frame counter for unique volatile snapshot texture IDs (range 300000+)
    private static int nextVolatileSnapshotId = 300000;
    // Per-frame map: GL texture ID → snapshot texture ID that was already uploaded
    // this frame
    // Avoids re-reading the same GL texture multiple times per frame
    private static final java.util.Map<Integer, Integer> frameSnapshotMap = new java.util.HashMap<>();
    private static long simpleElementBtnDiag = 0;

    /**
     * Intercepts addSimpleElementToCurrentLayer(TexturedQuadGuiElementRenderState)
     * to catch item atlas quads and entity composite quads.
     *
     * In MC 1.21.11, items and entities are rendered to offscreen GL textures
     * (item atlas, entity buffers), then composited as textured quads via this
     * method. These bypass DrawContext entirely, going direct to GL rendering.
     *
     * By intercepting here, we:
     * 1. Read the GL texture (item atlas / entity buffer) and upload to Metal
     * 2. Build a textured quad with the correct screen coords and UVs
     * 3. Queue the Metal draw
     * 4. Cancel the GL path
     */
    @Inject(method = "addSimpleElementToCurrentLayer", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptSimpleElement(TexturedQuadGuiElementRenderState state, CallbackInfo ci) {
        // BUTTON DIAG: Log simple elements when any screen is open
        if (simpleElementBtnDiag < 200) {
            net.minecraft.client.gui.screen.Screen screen = MinecraftClient.getInstance().currentScreen;
            if (screen != null && !screen.getClass().getSimpleName().equals("MessageScreen")) {
                String screenName = screen.getClass().getSimpleName();
                int diagTexId = 0;
                TextureSetup diagTs = state.textureSetup();
                if (diagTs != null) {
                    GpuTextureView tv = diagTs.texure0();
                    if (tv != null) {
                        GpuTexture gt = tv.texture();
                        if (gt instanceof GlTexture glt)
                            diagTexId = glt.getGlId();
                    }
                }
                simpleElementBtnDiag++;
                System.err.println("[BTN-DIAG-GRS] addSimpleElementToCurrentLayer #" + simpleElementBtnDiag
                        + " screen=" + screenName
                        + " pos=(" + state.x1() + "," + state.y1() + ")-(" + state.x2() + "," + state.y2() + ")"
                        + " uv=(" + state.u1() + "," + state.u2() + "," + state.v1() + "," + state.v2() + ")"
                        + " tex=" + diagTexId + " color=0x" + Integer.toHexString(state.color())
                        + " w=" + (state.x2() - state.x1()) + " h=" + (state.y2() - state.y1()));
            }
        }
        if (!MetalRenderClient.isEnabled())
            return;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer == null || renderer.getHandle() == 0L || !renderer.isInFrame())
            return;

        try {
            long handle = renderer.getHandle();

            // Extract quad geometry
            int x1 = state.x1();
            int y1 = state.y1();
            int x2 = state.x2();
            int y2 = state.y2();
            float u1 = state.u1();
            float u2 = state.u2();
            float v1 = state.v1();
            float v2 = state.v2();
            int color = state.color();
            org.joml.Matrix3x2f pose = state.pose();

            // Get GL texture ID from textureSetup
            int glTexId = 0;
            TextureSetup textureSetup = state.textureSetup();
            if (textureSetup != null) {
                GpuTextureView texView = textureSetup.texure0();
                if (texView != null) {
                    GpuTexture gpuTex = texView.texture();
                    if (gpuTex instanceof GlTexture glTex) {
                        glTexId = glTex.getGlId();
                    }
                }
            }

            // Upload texture to Metal — these are volatile (content changes per frame)
            if (glTexId > 0) {
                // Per-frame diagnostic tracking: log which branch is taken
                long currentFrame = MetalWorldRenderer.frameNumber;
                if (currentFrame != lastFrameNumber) {
                    if (lastFrameNumber >= 0 && (frameQuadCount > 0)) {
                        System.err.println("[MetalRender] FRAME-DIAG: quads=" + frameQuadCount
                                + " offscreen=" + frameQuadBranchOffscreen
                                + " snapshot=" + frameQuadBranchSnapshot
                                + " fallback=" + frameQuadBranchFallback
                                + " glReadback=" + frameQuadBranchGlReadback);
                    }
                    lastFrameNumber = currentFrame;
                    frameQuadCount = 0;
                    frameQuadBranchOffscreen = 0;
                    frameQuadBranchSnapshot = 0;
                    frameQuadBranchFallback = 0;
                    frameQuadBranchGlReadback = 0;
                    // Clear per-frame upload version tracking every frame
                    // so volatile textures get re-uploaded when content changes
                    lastUploadedVersion.clear();
                    // Reset per-frame volatile snapshot counter and map
                    nextVolatileSnapshotId = 300000;
                    frameSnapshotMap.clear();
                }
                frameQuadCount++;

                // MC renders items into an atlas texture via GL, then composites each item
                // as a textured quad. GL handles the atlas rendering directly — we just
                // need to know the atlas GL texture ID so we can upload it to Metal.
                // The GL atlas is uploaded to Metal in compositeOverlay() after all items
                // are rendered. The compositing quads reference the GL texture ID directly.
                if (MetalWorldRenderer.inOffscreenPass) {
                    frameQuadBranchOffscreen++;
                    // GL atlas is being rendered. Record the atlas GL tex ID.
                    if (MetalWorldRenderer.currentOffscreenGlTexId <= 0) {
                        MetalWorldRenderer.currentOffscreenGlTexId = glTexId;
                    }
                    // glTexId stays as the atlas GL ID — will be uploaded to Metal before draw
                    if (simpleElementCount <= 10) {
                        System.err.println("[MetalRender] GuiRenderState: queuing composite for glTex="
                                + glTexId + " (GL atlas rendering in progress)");
                    }
                } else if (glTexId == MetalWorldRenderer.lastAtlasGlId) {
                    frameQuadBranchSnapshot++;
                    // This is the atlas from a previous pass — already uploaded to Metal
                } else {
                    // Volatile texture — needs GL readback to Metal.
                    // Check if we're inside prepareItemElements() (atlas being actively
                    // populated one item at a time). If so, DEFER the readback to
                    // compositeOverlay() when ALL items have been rendered.
                    com.mojang.blaze3d.textures.GpuTextureView overrideTex = com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride;
                    if (overrideTex != null) {
                        // Item atlas compositing — defer readback to end of frame
                        MetalWorldRenderer.pendingVolatileReads.add(glTexId);
                        // glTexId stays as raw GL ID; will be uploaded before nDrawOverlay()
                    } else {
                        // Regular GUI texture (not item atlas) — immediate readback
                        Integer existingSnapshot = frameSnapshotMap.get(glTexId);
                        if (existingSnapshot != null) {
                            glTexId = existingSnapshot;
                        } else {
                            int snapshotId = nextVolatileSnapshotId++;
                            uploadVolatileTexture(handle, glTexId, snapshotId);
                            frameSnapshotMap.put(glTexId, snapshotId);
                            glTexId = snapshotId;
                        }
                    }
                    frameQuadBranchGlReadback++;
                }
            }

            // Unpack ARGB color to RGBA bytes
            byte a = (byte) ((color >> 24) & 0xFF);
            byte r = (byte) ((color >> 16) & 0xFF);
            byte g = (byte) ((color >> 8) & 0xFF);
            byte b = (byte) (color & 0xFF);

            // Apply 2D transform from pose matrix
            float m00 = pose.m00, m01 = pose.m01;
            float m10 = pose.m10, m11 = pose.m11;
            float m20 = pose.m20, m21 = pose.m21;

            float tlx = m00 * x1 + m10 * y1 + m20, tly = m01 * x1 + m11 * y1 + m21;
            float trx = m00 * x2 + m10 * y1 + m20, try_ = m01 * x2 + m11 * y1 + m21;
            float blx = m00 * x1 + m10 * y2 + m20, bly = m01 * x1 + m11 * y2 + m21;
            float brx = m00 * x2 + m10 * y2 + m20, bry = m01 * x2 + m11 * y2 + m21;

            // V-flip for Metal offscreen RTT textures.
            // Metal renders top-down (row 0 = top), but MC's UV coords assume GL
            // convention.
            // For atlas textures rendered by Metal RTT, we need to flip V.
            boolean isAtlasTex = (glTexId == MetalWorldRenderer.lastAtlasGlId
                    || glTexId == MetalWorldRenderer.currentOffscreenGlTexId);

            // --- ITEM CACHE DISABLED ---
            // The item cache produced stale/black textures because:
            // 1. hasNewOffscreenDraw boolean was consumed by first cache miss only
            // 2. Once cached with bad data, all subsequent frames drew from bad cache
            // Items now always draw from the live atlas composite (below).

            float finalV1, finalV2;
            if (isAtlasTex) {
                // Atlas/offscreen texture rendered by Metal RTT — flip V
                finalV1 = 1.0f - v1;
                finalV2 = 1.0f - v2;
            } else {
                finalV1 = v1;
                finalV2 = v2;
            }

            // Build 2-triangle quad (6 vertices × 32 bytes = 192 bytes)
            byte[] vertData = buildQuad(
                    tlx, tly, trx, try_, blx, bly, brx, bry,
                    u1, finalV1, u2, finalV2, r, g, b, a);

            // Queue Metal draw (blendMode=2 = UI, no depth test)
            float[] orthoMatrix = renderer.getEffectiveMatrix();
            NativeBridge.nQueueGenericDraw(handle, vertData, 6, glTexId, 2, orthoMatrix);

            simpleElementCount++;
            if (simpleElementCount <= 10 || simpleElementCount % 5000 == 0) {
                System.err.println("[MetalRender] GuiRenderStateMixin: simple element #" + simpleElementCount
                        + " at (" + x1 + "," + y1 + ")-(" + x2 + "," + y2 + ")"
                        + " texId=" + glTexId
                        + " uv=(" + u1 + "," + v1 + ")-(" + u2 + "," + v2 + ")"
                        + " color=0x" + Integer.toHexString(color));
            }
        } catch (Throwable t) {
            if (simpleElementCount <= 5) {
                System.err.println("[MetalRender] GuiRenderStateMixin simple element error: " + t.getMessage());
                t.printStackTrace(System.err);
            }
            return; // Don't cancel — let vanilla handle it
        }

        ci.cancel();
    }

    /**
     * Look up the inventory slot at a given screen position by checking the current
     * HandledScreen's slots. Returns the Slot object, or null if not found.
     */
    private static long slotLookupCount = 0;

    private static Slot metalrender$lookupSlotAtPosition(float screenX, float screenY) {
        try {
            Screen screen = MinecraftClient.getInstance().currentScreen;
            if (!(screen instanceof HandledScreenAccessor handledAccess))
                return null;

            ScreenHandler handler = handledAccess.metalrender$getHandler();
            int guiX = handledAccess.metalrender$getX();
            int guiY = handledAccess.metalrender$getY();

            for (Slot slot : handler.slots) {
                int slotScreenX = guiX + slot.x;
                int slotScreenY = guiY + slot.y;
                // Match with tolerance — transformed screen coords might differ by 1-2 pixels
                if (Math.abs(screenX - slotScreenX) <= 2 && Math.abs(screenY - slotScreenY) <= 2) {
                    slotLookupCount++;
                    if (slotLookupCount <= 20 || slotLookupCount % 5000 == 0) {
                        ItemStack stack = slot.getStack();
                        String itemId = (stack != null && !stack.isEmpty())
                                ? Registries.ITEM.getId(stack.getItem()).toString()
                                : "<empty>";
                        System.err.println("[MetalRender] Slot lookup #" + slotLookupCount
                                + ": (" + screenX + "," + screenY + ") → slot " + slot.id
                                + " (" + slotScreenX + "," + slotScreenY + ") = '" + itemId + "'");
                    }
                    return slot;
                }
            }
        } catch (Exception e) {
            if (slotLookupCount <= 5) {
                System.err.println("[MetalRender] Slot lookup error: " + e.getMessage());
            }
        }
        return null; // no slot at this position
    }

    /**
     * Schedule a GPU blit from the atlas snapshot to a per-slot cache texture.
     * Converts MC UV coordinates (GL convention) to Metal atlas pixel coordinates.
     */
    private static void metalrender$scheduleBlit(int cacheTexId, int slotId, String itemKey,
            float u1, float v1, float u2, float v2) {
        int atlasW = MetalWorldRenderer.offscreenWidth;
        int atlasH = MetalWorldRenderer.offscreenHeight;
        if (atlasW <= 0 || atlasH <= 0)
            return;

        // Convert MC UV (GL convention) to Metal atlas pixel coords
        float metalV1 = 1.0f - v1;
        float metalV2 = 1.0f - v2;
        float topV = Math.min(metalV1, metalV2);
        float botV = Math.max(metalV1, metalV2);
        int srcX = Math.max(0, (int) (u1 * atlasW));
        int srcY = Math.max(0, (int) (topV * atlasH));
        int srcW = Math.max(1, (int) ((u2 - u1) * atlasW));
        int srcH = Math.max(1, (int) ((botV - topV) * atlasH));

        // Use currentOffscreenGlTexId as the source — this is the atlas being built
        // this frame. compositeOverlay will snapshot it under this ID before processing
        // blits.
        int atlasId = MetalWorldRenderer.currentOffscreenGlTexId;
        if (atlasId <= 0)
            atlasId = MetalWorldRenderer.lastAtlasGlId;
        ItemRenderCache.addPendingBlit(new ItemRenderCache.PendingBlit(
                slotId, itemKey, cacheTexId, atlasId,
                srcX, srcY, srcW, srcH));
    }

    /**
     * Upload a volatile GL texture (item atlas, entity buffer) to Metal.
     * These change every frame, so we always re-read from GL.
     *
     * Uses FBO + glReadPixels instead of glGetTexImage because macOS's
     * deprecated OpenGL returns all-zeros from glGetTexImage on immutable
     * textures (created via glTexStorage2D) and FBO color attachments.
     * FBO-based readback is reliable on all platforms.
     */
    /**
     * Upload a volatile GL texture to Metal under a unique snapshot ID.
     * 
     * @param handle        Metal context handle
     * @param glTexId       GL texture to read pixels from
     * @param snapshotTexId unique Metal texture ID to store the snapshot under
     */
    private static void uploadVolatileTexture(long handle, int glTexId, int snapshotTexId) {
        if (glTexId <= 0)
            return;

        try {
            // Save current state
            int prevTex = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            int prevReadFbo = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);

            // Get texture dimensions
            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glTexId);
            int w = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
            int h = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);

            if (w > 0 && h > 0 && w <= 8192 && h <= 8192) {
                int bufSize = w * h * 4;
                ByteBuffer pixBuf = org.lwjgl.system.MemoryUtil.memAlloc(bufSize);
                byte[] pixels = null;
                String readMethod = "none";

                // ---- PRIMARY: FBO + glReadPixels ----
                // Create a temporary FBO, attach the texture, and read via glReadPixels.
                // This works on macOS even for immutable/FBO-attached textures.
                int tempFbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
                org.lwjgl.opengl.GL30.glBindFramebuffer(
                        org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, tempFbo);
                org.lwjgl.opengl.GL30.glFramebufferTexture2D(
                        org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,
                        org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glTexId, 0);

                int fbStatus = org.lwjgl.opengl.GL30.glCheckFramebufferStatus(
                        org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER);

                if (fbStatus == org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE) {
                    // CRITICAL: glFinish() ensures all pending GL draw commands
                    // (item/entity rendering to this texture) have completed before
                    // we read the pixels. Without this, glReadPixels may return all
                    // zeros because the GPU hasn't finished rendering yet.
                    org.lwjgl.opengl.GL11.glFinish();

                    org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h,
                            org.lwjgl.opengl.GL11.GL_RGBA,
                            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixBuf);
                    pixels = new byte[bufSize];
                    pixBuf.rewind();
                    pixBuf.get(pixels);
                    // NO V-flip needed: glReadPixels returns bottom-row-first, and
                    // MC's UV coords are in GL convention (v=1=top). When Metal receives
                    // the un-flipped data, GL's top (row H-1 in buffer) maps to Metal's
                    // v≈1.0, matching MC's UV references. The conventions align naturally.
                    readMethod = "FBO+glReadPixels";
                } else {
                    // FBO incomplete — fall back to glGetTexImage
                    pixBuf.clear();
                    org.lwjgl.opengl.GL11.glGetTexImage(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                            org.lwjgl.opengl.GL11.GL_RGBA,
                            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixBuf);
                    pixels = new byte[bufSize];
                    pixBuf.rewind();
                    pixBuf.get(pixels);
                    readMethod = "glGetTexImage(fallback,fbStatus=" + fbStatus + ")";
                }

                // Clean up temp FBO
                org.lwjgl.opengl.GL30.glBindFramebuffer(
                        org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
                org.lwjgl.opengl.GL30.glDeleteFramebuffers(tempFbo);
                org.lwjgl.system.MemoryUtil.memFree(pixBuf);

                // Diagnostic: scan FULL texture for non-zero pixels and sample
                // meaningful locations. Items are in GL's top rows (= last rows in buffer).
                int totalNonZero = 0;
                for (int i = 0; i < pixels.length; i++) {
                    if (pixels[i] != 0)
                        totalNonZero++;
                }
                // Sample from last rows of buffer (= GL top = where items are rendered)
                int sampleR = 0, sampleG = 0, sampleB = 0, sampleA = 0;
                // Sample at ~12.5% from the top in GL (= 87.5% into the buffer)
                int glTopRowIdx = (int) (h * 0.875) * w * 4 + (w / 8) * 4; // ~row 448 of 512, column 64
                if (glTopRowIdx + 3 < pixels.length) {
                    sampleR = pixels[glTopRowIdx] & 0xFF;
                    sampleG = pixels[glTopRowIdx + 1] & 0xFF;
                    sampleB = pixels[glTopRowIdx + 2] & 0xFF;
                    sampleA = pixels[glTopRowIdx + 3] & 0xFF;
                }

                // Always upload — the texture may have content even if the first
                // few rows sampled by the diagnostic are empty (e.g. transparent
                // border region of an item atlas).
                // Upload under the unique snapshot ID so each draw command references
                // its own Metal texture with the correct content.
                NativeBridge.nUploadGenericTexture(handle, snapshotTexId, w, h, pixels);

                if (!volatileTextures.contains(glTexId)) {
                    volatileTextures.add(glTexId);
                    System.err.println("[MetalRender] GuiRenderStateMixin: uploaded volatile texture glId="
                            + glTexId + " (" + w + "x" + h + ")"
                            + " method=" + readMethod
                            + " totalNonZero=" + totalNonZero + "/" + pixels.length
                            + " glTopSample=(" + sampleR + "," + sampleG + "," + sampleB + "," + sampleA + ")");
                }
            }

            // Restore previous state
            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex);
        } catch (Exception e) {
            System.err.println("[MetalRender] GuiRenderStateMixin: failed to upload volatile tex "
                    + glTexId + ": " + e);
        }
    }

    /**
     * Build a 2-triangle quad from 4 transformed corners.
     * Layout: 6 vertices × 32 bytes (float3 pos + ubyte4 color + float2 uv + float2
     * light)
     */
    private static byte[] buildQuad(
            float tlx, float tly, float trx, float try_,
            float blx, float bly, float brx, float bry,
            float u0, float v0, float u1, float v1,
            byte r, byte g, byte b, byte a) {

        ByteBuffer buf = ByteBuffer.allocate(6 * 32).order(ByteOrder.LITTLE_ENDIAN);
        // Triangle 1: TL, BL, BR
        writeVertex(buf, tlx, tly, 0, r, g, b, a, u0, v0);
        writeVertex(buf, blx, bly, 0, r, g, b, a, u0, v1);
        writeVertex(buf, brx, bry, 0, r, g, b, a, u1, v1);
        // Triangle 2: TL, BR, TR
        writeVertex(buf, tlx, tly, 0, r, g, b, a, u0, v0);
        writeVertex(buf, brx, bry, 0, r, g, b, a, u1, v1);
        writeVertex(buf, trx, try_, 0, r, g, b, a, u1, v0);
        return buf.array();
    }

    private static void writeVertex(ByteBuffer buf, float x, float y, float z,
            byte r, byte g, byte b, byte a,
            float u, float v) {
        buf.putFloat(x);
        buf.putFloat(y);
        buf.putFloat(z);
        buf.put(r);
        buf.put(g);
        buf.put(b);
        buf.put(a);
        buf.putFloat(u);
        buf.putFloat(v);
        // Light: UI draws don't need per-vertex lighting (blockLight=0, skyLight=0)
        buf.putFloat(0.0f);
        buf.putFloat(0.0f);
    }
}
