package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.ItemRenderCache;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.sodium.mixins.accessor.HandledScreenAccessor;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.world.World;
import org.joml.Matrix3x2fc;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/**
 * Intercepts DrawContext GUI draw calls (textured quads, fills) and routes them
 * through Metal instead of the GL-based GuiRenderer/RenderPass pipeline.
 *
 * MC 1.21.11 uses a new retained-mode GUI rendering system:
 * DrawContext -> GuiRenderState -> GuiRenderer -> RenderPass -> GL
 * We intercept at the DrawContext level to capture quad geometry and textures,
 * build Metal draws, and cancel the GL path.
 */
@Mixin(DrawContext.class)
public class DrawContextMixin {

    @Shadow
    @Final
    private Matrix3x2fStack matrices;

    // Reference to shared GUI texture upload cache (cleared each frame by
    // TextureCacheManager).
    private static final Set<Integer> uploadedGuiTextures = com.metalrender.render.TextureCacheManager.uploadedGuiTextures;
    private static long guiDrawCount = 0;
    private static int totalFillCount = 0;
    private static long static_fullscreenSkipCount = 0;

    /**
     * Intercept the innermost drawTexturedQuad method.
     * All GUI texture draws (hotbar, buttons, icons, etc.) funnel through this.
     *
     * Actual parameter order (from bytecode tracing through Identifier overload):
     * ints: x1, y1, x2, y2
     * floats: u0, u1, v0, v1 (minU, maxU, minV, maxV)
     */
    private static long offscreenQuadCount = 0;
    private static long btnDiagCount = 0;

    @Inject(method = "drawTexturedQuad(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/mojang/blaze3d/textures/GpuTextureView;Lnet/minecraft/client/gl/GpuSampler;IIIIFFFFI)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptTexturedQuad(
            RenderPipeline pipeline, GpuTextureView textureView, GpuSampler sampler,
            int x1, int y1, int x2, int y2,
            float minU, float maxU, float minV, float maxV, int color,
            CallbackInfo ci) {
        // BUTTON DIAG: Log quads when a relevant screen is open (skip loading screens)
        if (btnDiagCount < 500) {
            net.minecraft.client.gui.screen.Screen screen = MinecraftClient.getInstance().currentScreen;
            if (screen != null && !screen.getClass().getSimpleName().equals("MessageScreen")) {
                String screenName = screen.getClass().getSimpleName();
                int texId = 0;
                if (textureView != null) {
                    GpuTexture gpuTex = textureView.texture();
                    if (gpuTex instanceof GlTexture glTex)
                        texId = glTex.getGlId();
                }
                boolean enabled = MetalRenderClient.isEnabled();
                MetalWorldRenderer ren = MetalRenderClient.getWorldRenderer();
                boolean inFrame = ren != null && ren.getHandle() != 0L && ren.isInFrame();
                btnDiagCount++;
                System.err.println("[BTN-DIAG-DCM] #" + btnDiagCount
                        + " screen=" + screenName
                        + " pos=(" + x1 + "," + y1 + ")-(" + x2 + "," + y2 + ")"
                        + " uv=(" + minU + "," + maxU + "," + minV + "," + maxV + ")"
                        + " tex=" + texId + " color=0x" + Integer.toHexString(color)
                        + " w=" + (x2 - x1) + " h=" + (y2 - y1)
                        + " enabled=" + enabled + " inFrame=" + inFrame);
            }
        }
        if (!MetalRenderClient.isEnabled())
            return;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer == null || renderer.getHandle() == 0L || !renderer.isInFrame())
            return;

        long handle = renderer.getHandle();

        // ---- Check for OFFSCREEN rendering (item sprites to atlas) ----
        // Route to Metal offscreen RTT — renders this item sprite to the atlas.
        com.mojang.blaze3d.textures.GpuTextureView offscreenOverride = com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride;
        if (offscreenOverride != null) {
            // Get source texture GL ID and upload to Metal
            int srcGlTexId = 0;
            if (textureView != null) {
                GpuTexture gpuTex = textureView.texture();
                if (gpuTex instanceof GlTexture glTex) {
                    srcGlTexId = glTex.getGlId();
                    if (srcGlTexId > 0) {
                        ensureGuiTextureUploaded(handle, srcGlTexId);
                    }
                }
            }
            // Start offscreen pass if not already active
            if (!MetalWorldRenderer.inOffscreenPass) {
                int offW = 512, offH = 512;
                int overrideGlId = 0;
                try {
                    GpuTexture offTex = offscreenOverride.texture();
                    if (offTex instanceof GlTexture glOff) {
                        overrideGlId = glOff.getGlId();
                        int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
                        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, overrideGlId);
                        offW = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                                org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
                        offH = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                                org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
                        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex);
                        if (offW <= 0 || offH <= 0) {
                            offW = 512;
                            offH = 512;
                        }
                    }
                } catch (Exception e) {
                    /* ignore */ }
                MetalWorldRenderer.currentOffscreenGlTexId = overrideGlId;
                MetalWorldRenderer.offscreenWidth = offW;
                MetalWorldRenderer.offscreenHeight = offH;
                MetalWorldRenderer.currentPassDrawCount = 0;
                NativeBridge.nBeginOffscreenPass(handle, offW, offH);
                MetalWorldRenderer.inOffscreenPass = true;
            }
            // Build quad vertices and render to Metal offscreen RT
            byte a2 = (byte) ((color >> 24) & 0xFF);
            byte r2 = (byte) ((color >> 16) & 0xFF);
            byte g2 = (byte) ((color >> 8) & 0xFF);
            byte b2 = (byte) (color & 0xFF);
            float m00_ = matrices.m00, m01_ = matrices.m01;
            float m10_ = matrices.m10, m11_ = matrices.m11;
            float m20_ = matrices.m20, m21_ = matrices.m21;
            float tlx_ = m00_ * x1 + m10_ * y1 + m20_, tly_ = m01_ * x1 + m11_ * y1 + m21_;
            float trx_ = m00_ * x2 + m10_ * y1 + m20_, try2 = m01_ * x2 + m11_ * y1 + m21_;
            float blx_ = m00_ * x1 + m10_ * y2 + m20_, bly_ = m01_ * x1 + m11_ * y2 + m21_;
            float brx_ = m00_ * x2 + m10_ * y2 + m20_, bry_ = m01_ * x2 + m11_ * y2 + m21_;
            byte[] offData = buildQuad(tlx_, tly_, trx_, try2, blx_, bly_, brx_, bry_,
                    minU, minV, maxU, maxV, r2, g2, b2, a2);
            int offW = MetalWorldRenderer.offscreenWidth;
            int offH = MetalWorldRenderer.offscreenHeight;
            if (offW <= 0)
                offW = 512;
            if (offH <= 0)
                offH = 512;
            Matrix4f ortho = new Matrix4f();
            ortho.setOrtho(0.0f, (float) offW, (float) offH, 0.0f, -2000.0f, 3000.0f, true);
            float[] offMatrix = new float[16];
            ortho.get(offMatrix);
            NativeBridge.nDrawOffscreen(handle, offData, 6, srcGlTexId, 1, offMatrix);
            MetalWorldRenderer.currentPassDrawCount++;
            MetalWorldRenderer.hasNewOffscreenDraw = true;
            offscreenQuadCount++;
            if (offscreenQuadCount <= 30 || offscreenQuadCount % 500 == 0) {
                System.err.println("[MetalRender] DrawContextMixin: OFFSCREEN quad #" + offscreenQuadCount
                        + " at (" + x1 + "," + y1 + ")-(" + x2 + "," + y2 + ")"
                        + " tex=" + srcGlTexId);
            }
            ci.cancel();
            return;
        }

        // ---- MAIN FRAMEBUFFER: normal GUI draw path ----

        // Skip fullscreen textured quads (MC's world framebuffer composite draws).
        // MC composites the GL-rendered world as a fullscreen blit in the GUI layer.
        // Since we render terrain directly to Metal, these would paint the (black) GL
        // framebuffer over our terrain. Detect by checking if quad covers entire scaled
        // window.
        {
            net.minecraft.client.util.Window window = MinecraftClient.getInstance().getWindow();
            int scaledW = window.getScaledWidth();
            int scaledH = window.getScaledHeight();
            if (x1 == 0 && y1 == 0 && x2 >= scaledW && y2 >= scaledH) {
                static_fullscreenSkipCount++;
                if (static_fullscreenSkipCount <= 5 || static_fullscreenSkipCount % 5000 == 0) {
                    System.err.println("[MetalRender] DrawContextMixin: SKIPPED fullscreen blit #"
                            + static_fullscreenSkipCount + " (" + x2 + "x" + y2
                            + ") color=0x" + Integer.toHexString(color));
                }
                ci.cancel();
                return;
            }
        }

        // Get texture GL ID and upload to Metal if needed
        int glTexId = 0;
        if (textureView != null) {
            GpuTexture gpuTex = textureView.texture();
            if (gpuTex instanceof GlTexture glTex) {
                glTexId = glTex.getGlId();
                if (glTexId > 0) {
                    ensureGuiTextureUploaded(handle, glTexId);
                }
            }
        }

        // Unpack ARGB color to RGBA bytes
        byte a = (byte) ((color >> 24) & 0xFF);
        byte r = (byte) ((color >> 16) & 0xFF);
        byte g = (byte) ((color >> 8) & 0xFF);
        byte b = (byte) (color & 0xFF);

        // Apply 2D transform from matrices stack to all 4 corners
        float m00 = matrices.m00, m01 = matrices.m01;
        float m10 = matrices.m10, m11 = matrices.m11;
        float m20 = matrices.m20, m21 = matrices.m21;

        // Transform corners: (x1,y1)=TL, (x2,y1)=TR, (x1,y2)=BL, (x2,y2)=BR
        float tlx = m00 * x1 + m10 * y1 + m20, tly = m01 * x1 + m11 * y1 + m21;
        float trx = m00 * x2 + m10 * y1 + m20, try_ = m01 * x2 + m11 * y1 + m21;
        float blx = m00 * x1 + m10 * y2 + m20, bly = m01 * x1 + m11 * y2 + m21;
        float brx = m00 * x2 + m10 * y2 + m20, bry = m01 * x2 + m11 * y2 + m21;

        // Build 2-triangle quad (6 vertices x 32 bytes = 192 bytes)
        // UV order from MC: minU, maxU, minV, maxV
        byte[] vertData = buildQuad(
                tlx, tly, trx, try_, blx, bly, brx, bry,
                minU, minV, maxU, maxV, r, g, b, a);

        // Queue Metal draw (blendMode=2 = UI, no depth test)
        float[] orthoMatrix = renderer.getEffectiveMatrix();
        NativeBridge.nQueueGenericDraw(handle, vertData, 6, glTexId, 2, orthoMatrix);

        guiDrawCount++;
        if (guiDrawCount % 10000 == 1) {
            System.err.println("[MetalRender] DrawContextMixin: queued " + guiDrawCount
                    + " GUI draws, textures cached=" + uploadedGuiTextures.size());
        }

        ci.cancel();
    }

    /**
     * Intercept the innermost fill() method for solid color rectangles and
     * gradients.
     */
    @Inject(method = "fill(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/texture/TextureSetup;IIIIILjava/lang/Integer;)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptFill(
            RenderPipeline pipeline, TextureSetup textureSetup,
            int x1, int y1, int x2, int y2, int color, Integer colorEnd,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer == null || renderer.getHandle() == 0L || !renderer.isInFrame())
            return;

        long handle = renderer.getHandle();

        // ---- Check for OFFSCREEN fill (atlas background clear) ----
        // Route fill to Metal offscreen RTT as a solid color quad.
        com.mojang.blaze3d.textures.GpuTextureView offscreenOverride = com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride;
        if (offscreenOverride != null) {
            if (!MetalWorldRenderer.inOffscreenPass) {
                // Offscreen pass not started yet — skip this fill, it'll be handled
                // when the pass begins on the next draw call
                ci.cancel();
                return;
            }
            byte a2 = (byte) ((color >> 24) & 0xFF);
            byte r2 = (byte) ((color >> 16) & 0xFF);
            byte g2 = (byte) ((color >> 8) & 0xFF);
            byte b2 = (byte) (color & 0xFF);
            float m00_ = matrices.m00, m01_ = matrices.m01;
            float m10_ = matrices.m10, m11_ = matrices.m11;
            float m20_ = matrices.m20, m21_ = matrices.m21;
            float tlx_ = m00_ * x1 + m10_ * y1 + m20_, tly_ = m01_ * x1 + m11_ * y1 + m21_;
            float trx_ = m00_ * x2 + m10_ * y1 + m20_, try2 = m01_ * x2 + m11_ * y1 + m21_;
            float blx_ = m00_ * x1 + m10_ * y2 + m20_, bly_ = m01_ * x1 + m11_ * y2 + m21_;
            float brx_ = m00_ * x2 + m10_ * y2 + m20_, bry_ = m01_ * x2 + m11_ * y2 + m21_;
            byte[] fillData = buildQuad(tlx_, tly_, trx_, try2, blx_, bly_, brx_, bry_,
                    0, 0, 1, 1, r2, g2, b2, a2);
            int offW = MetalWorldRenderer.offscreenWidth;
            int offH = MetalWorldRenderer.offscreenHeight;
            if (offW <= 0)
                offW = 512;
            if (offH <= 0)
                offH = 512;
            Matrix4f ortho = new Matrix4f();
            ortho.setOrtho(0.0f, (float) offW, (float) offH, 0.0f, -2000.0f, 3000.0f, true);
            float[] offMatrix = new float[16];
            ortho.get(offMatrix);
            NativeBridge.nDrawOffscreen(handle, fillData, 6, 0, 1, offMatrix);
            MetalWorldRenderer.currentPassDrawCount++;
            MetalWorldRenderer.hasNewOffscreenDraw = true;
            ci.cancel();
            return;
        }

        // Diagnostic: log first fills to identify the "black void" source
        totalFillCount++;
        if (totalFillCount <= 100) {
            System.err.println("[MetalRender] Fill#" + totalFillCount
                    + " coords=(" + x1 + "," + y1 + ")-(" + x2 + "," + y2 + ")"
                    + " color=0x" + Integer.toHexString(color)
                    + (colorEnd != null ? " end=0x" + Integer.toHexString(colorEnd) : ""));
        }

        // Unpack ARGB color (top/start color)
        byte a = (byte) ((color >> 24) & 0xFF);
        byte r = (byte) ((color >> 16) & 0xFF);
        byte g = (byte) ((color >> 8) & 0xFF);
        byte b = (byte) (color & 0xFF);

        // Apply 2D transform
        float m00 = matrices.m00, m01 = matrices.m01;
        float m10 = matrices.m10, m11 = matrices.m11;
        float m20 = matrices.m20, m21 = matrices.m21;

        float tlx = m00 * x1 + m10 * y1 + m20, tly = m01 * x1 + m11 * y1 + m21;
        float trx = m00 * x2 + m10 * y1 + m20, try_ = m01 * x2 + m11 * y1 + m21;
        float blx = m00 * x1 + m10 * y2 + m20, bly = m01 * x1 + m11 * y2 + m21;
        float brx = m00 * x2 + m10 * y2 + m20, bry = m01 * x2 + m11 * y2 + m21;

        byte[] vertData;
        if (colorEnd != null) {
            // Gradient fill: top vertices use color, bottom vertices use colorEnd
            byte a2 = (byte) ((colorEnd >> 24) & 0xFF);
            byte r2 = (byte) ((colorEnd >> 16) & 0xFF);
            byte g2 = (byte) ((colorEnd >> 8) & 0xFF);
            byte b2 = (byte) (colorEnd & 0xFF);
            vertData = buildGradientQuad(
                    tlx, tly, trx, try_, blx, bly, brx, bry,
                    0, 0, 1, 1,
                    r, g, b, a, // top color
                    r2, g2, b2, a2); // bottom color
        } else {
            // Solid fill: use white fallback texture (ID=0), vertex colors define
            // appearance
            vertData = buildQuad(
                    tlx, tly, trx, try_, blx, bly, brx, bry,
                    0, 0, 1, 1, r, g, b, a);
        }

        float[] orthoMatrix = renderer.getEffectiveMatrix();
        NativeBridge.nQueueGenericDraw(handle, vertData, 6, 0, 2, orthoMatrix);

        ci.cancel();
    }

    /**
     * Intercept text rendering: route through VCP → RenderLayer.draw →
     * RenderLayerMixin → Metal.
     * This catches ALL text (drawTextWithShadow, drawCenteredText, drawWrappedText,
     * etc.)
     * since they all funnel to this method.
     */
    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptDrawText(
            TextRenderer textRenderer, OrderedText text, int x, int y, int color, boolean shadow,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer == null || renderer.getHandle() == 0L || !renderer.isInFrame())
            return;

        try {
            // Convert DrawContext's 2D matrix stack to a 4x4 matrix for TextRenderer
            // (same approach as GlyphGuiElementRenderState: new Matrix4f().mul(pose))
            Matrix4f matrix4f = new Matrix4f().mul((Matrix3x2fc) matrices);

            // Use entity VCP (empty during UI phase — entity rendering already flushed it)
            VertexConsumerProvider.Immediate vcp = MinecraftClient.getInstance()
                    .getBufferBuilders().getEntityVertexConsumers();

            // Mark as text overlay BEFORE draw so any auto-flushed layers
            // during textRenderer.draw() also get blendMode=3.
            // The VCP may auto-flush when switching between shadow/main RenderLayers.
            MetalWorldRenderer.isTextOverlay = true;

            // Render text through legacy VCP path:
            // TextRenderer.draw → glyph vertices in VCP → VCP.draw() →
            // RenderLayer.draw(BuiltBuffer) → RenderLayerMixin → Metal
            textRenderer.draw(text, (float) x, (float) y, color, shadow, matrix4f, vcp,
                    TextRenderer.TextLayerType.SEE_THROUGH, 0, 15728880);

            // Flush VCP immediately — triggers RenderLayer.draw() for each text RenderLayer
            vcp.draw();
            MetalWorldRenderer.isTextOverlay = false;

            static_textDrawCount++;
            if (static_textDrawCount <= 5 || static_textDrawCount % 5000 == 0) {
                System.err.println("[MetalRender] DrawContextMixin: text draw #" + static_textDrawCount
                        + " at (" + x + "," + y + ") color=0x" + Integer.toHexString(color)
                        + " shadow=" + shadow);
            }
        } catch (Throwable t) {
            // On error, let original path handle it
            if (static_textDrawCount <= 5) {
                System.err.println("[MetalRender] Text intercept error: " + t.getMessage());
                t.printStackTrace(System.err);
            }
            return;
        }

        // Cancel original (prevents text from going to GuiRenderState → GL)
        ci.cancel();
    }

    private static long static_textDrawCount = 0;

    /**
     * Build a 2-triangle quad from 4 transformed corners.
     * Layout: 6 vertices x 32 bytes (float3 pos + ubyte4 color + float2 uv + float2
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

    /**
     * Build a gradient quad: top vertices use topColor, bottom vertices use
     * bottomColor.
     * Used for fillGradient calls which have different start/end colors.
     */
    private static byte[] buildGradientQuad(
            float tlx, float tly, float trx, float try_,
            float blx, float bly, float brx, float bry,
            float u0, float v0, float u1, float v1,
            byte r1, byte g1, byte b1, byte a1,
            byte r2, byte g2, byte b2, byte a2) {

        ByteBuffer buf = ByteBuffer.allocate(6 * 32).order(ByteOrder.LITTLE_ENDIAN);
        // Triangle 1: TL(top), BL(bottom), BR(bottom)
        writeVertex(buf, tlx, tly, 0, r1, g1, b1, a1, u0, v0);
        writeVertex(buf, blx, bly, 0, r2, g2, b2, a2, u0, v1);
        writeVertex(buf, brx, bry, 0, r2, g2, b2, a2, u1, v1);
        // Triangle 2: TL(top), BR(bottom), TR(top)
        writeVertex(buf, tlx, tly, 0, r1, g1, b1, a1, u0, v0);
        writeVertex(buf, brx, bry, 0, r2, g2, b2, a2, u1, v1);
        writeVertex(buf, trx, try_, 0, r1, g1, b1, a1, u1, v0);
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

    /**
     * Read GL texture pixels and upload to Metal.
     * Uses FBO+glReadPixels instead of glGetTexImage because macOS returns zeros
     * from glGetTexImage for immutable textures (created via glTexStorage2D).
     */
    private static void ensureGuiTextureUploaded(long handle, int glTexId) {
        if (glTexId <= 0 || uploadedGuiTextures.contains(glTexId))
            return;

        try {
            int prevTex = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);

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

                // Use FBO+glReadPixels (works on macOS for immutable textures)
                int prevReadFbo = org.lwjgl.opengl.GL11.glGetInteger(
                        org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
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
                    org.lwjgl.opengl.GL11.glFinish();
                    org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h,
                            org.lwjgl.opengl.GL11.GL_RGBA,
                            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixBuf);
                    pixels = new byte[bufSize];
                    pixBuf.rewind();
                    pixBuf.get(pixels);
                } else {
                    // FBO incomplete — fall back to glGetTexImage as last resort
                    pixBuf.clear();
                    org.lwjgl.opengl.GL11.glGetTexImage(
                            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                            org.lwjgl.opengl.GL11.GL_RGBA,
                            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixBuf);
                    pixels = new byte[bufSize];
                    pixBuf.rewind();
                    pixBuf.get(pixels);
                }

                // Clean up temp FBO
                org.lwjgl.opengl.GL30.glBindFramebuffer(
                        org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
                org.lwjgl.opengl.GL30.glDeleteFramebuffers(tempFbo);
                org.lwjgl.system.MemoryUtil.memFree(pixBuf);

                if (pixels != null) {
                    int result = NativeBridge.nUploadGenericTexture(handle, glTexId, w, h, pixels);
                    if (result >= 0) {
                        uploadedGuiTextures.add(glTexId);
                        System.err.println("[MetalRender] DrawContextMixin: uploaded GUI texture glId="
                                + glTexId + " (" + w + "x" + h + ") via FBO+glReadPixels");
                    }
                }
            }

            org.lwjgl.opengl.GL11.glBindTexture(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex);
        } catch (Exception e) {
            System.err.println("[MetalRender] DrawContextMixin: failed to upload tex "
                    + glTexId + ": " + e);
        }
    }

    // ====================================================================
    // Item identity tracking for the storage bucket cache
    // ====================================================================

    /**
     * Track which slots are being rendered this frame. DrawContext.drawItem() is
     * called
     * for each inventory slot. We find the matching slot by screen position and
     * mark it
     * as "rendered this frame" so the cache knows the atlas has fresh data for it.
     */
    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"))
    private void metalrender$trackItemStart(LivingEntity entity, World world,
            ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        if (stack != null && !stack.isEmpty()) {
            try {
                net.minecraft.client.gui.screen.Screen screen = MinecraftClient.getInstance().currentScreen;
                if (screen instanceof HandledScreenAccessor acc) {
                    net.minecraft.screen.ScreenHandler handler = acc.metalrender$getHandler();
                    int guiX = acc.metalrender$getX();
                    int guiY = acc.metalrender$getY();
                    for (net.minecraft.screen.slot.Slot slot : handler.slots) {
                        if (guiX + slot.x == x && guiY + slot.y == y) {
                            ItemRenderCache.markSlotRendered(slot.id);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                /* ignore */ }
        }
    }
}
