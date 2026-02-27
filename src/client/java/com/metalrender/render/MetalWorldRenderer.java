package com.metalrender.render;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.PerformanceController;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.PersistentBufferArena;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.EnvironmentAttributeInterpolator;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.MoonPhase;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class MetalWorldRenderer {
  private long handle;
  private volatile boolean ready;
  private final RenderOptimizer renderOptimizer = RenderOptimizer.getInstance();
  private final PersistentBufferArena persistentArena = new PersistentBufferArena();
  private final float[] frameViewProjection = new float[16];
  private final float[] frameProjection = new float[16];
  private MeshShaderBackend cachedMeshBackend;
  private PipelineCache pipelineCache;
  private int lastWidth = 16;
  private int lastHeight = 16;
  private float lastScale = 1.0F;
  private boolean sodiumlessMode = false;
  private volatile boolean atlasNeedsReupload = false;
  private boolean firstFrame = true;
  private int diagCounter = 0;

  // Sky rendering state
  private final float[] skyColor = new float[] { 0.392f, 0.584f, 0.929f, 1.0f };
  private float sunAngle = 0.0f;
  private float moonAngle = (float) Math.PI;
  private int moonPhaseIndex = 0;
  private float starBrightness = 0.0f;
  private float rainGradient = 1.0f;
  private boolean skyEnabled = true;
  private boolean sunMoonTexturesUploaded = false;
  private int celestialTextureRetries = 0;
  private static final int MAX_CELESTIAL_RETRIES = 600; // ~10 seconds at 60fps
  private static final int SUN_TEXTURE_ID = 9000;
  private static final int MOON_TEXTURE_ID = 9001;
  private static final int STAR_TEXTURE_ID = 8999;
  private static final int STAR_COUNT = 1500;
  private float[] starX, starY, starZ;
  private boolean starsGenerated = false;

  // Performance tracking for F3 overlay
  private long frameStartNanos = 0;
  private long lastFrameTimeNanos = 0;
  private final long[] frameTimes = new long[120]; // rolling 120 frames
  private int frameTimeIndex = 0;
  private int totalFramesDone = 0;

  public MetalWorldRenderer() {
    MetalLogger.info(
        "MetalWorldRenderer created (initializing native backend)");

    try {
      if (NativeBridge.isLibLoaded() && NativeBridge.nIsAvailable()) {
        // Log native version stamp for verification
        String nativeVer = NativeBridge.nGetNativeVersion();
        MetalLogger.info("Native library version: {}", nativeVer);
        System.err.println("[MetalRender] NATIVE VERSION FROM JAVA: " + nativeVer);

        this.handle = NativeBridge.nInit(16, 16, 1.0F);
        this.ready = this.handle != 0L;
        MetalLogger.info("Native backend {} (device='{}')",
            this.ready ? "ready" : "failed",
            NativeBridge.nGetDeviceName(this.handle));
        if (this.ready) {
          if (!this.persistentArena.initialize(this.handle)) {
            MetalLogger.warn("Persistent buffer arena failed to initialize; "
                + "falling back to transient uploads");
          }
          // When the arena wraps around, all chunk mesh offsets become stale
          this.persistentArena.setOnWrap(() -> {
            MeshShaderBackend b = this.cachedMeshBackend;
            if (b == null)
              b = MetalRenderClient.getMeshBackend();
            if (b != null) {
              b.destroy(); // clears all chunk meshes so they get re-uploaded
              MetalLogger.warn("[MetalRender] Persistent buffer wrapped — cleared chunk mesh cache");
            }
          });
          this.pipelineCache = PipelineCache.create(this.handle);
          if (this.pipelineCache != null) {
            this.pipelineCache.prewarm();
          }
        }
      } else {
        this.ready = false;
        MetalLogger.warn("Native library not loaded or unavailable");
      }
    } catch (Throwable var2) {
      this.ready = false;
      MetalLogger.error("Failed to initialize native backend: " + var2);
      var2.printStackTrace();
    }
  }

  private volatile boolean inRenderFrame = false;

  // Render phase: 0=WORLD (3D perspective), 1=UI (orthographic)
  private volatile int renderPhase = 0;
  private final float[] orthoProjectionMatrix = new float[16];

  // Flag: true when rendering text overlay (stack counts, labels).
  // Used by RenderLayerMixin to assign blendMode=3 so text sorts after items.
  public static volatile boolean isTextOverlay = false;

  // Metal RTT offscreen state: true while rendering item models to offscreen
  // Metal texture
  public static volatile boolean inOffscreenPass = false;
  // Auto-incrementing snapshot texture ID for Metal RTT (reset each frame)
  public static volatile int nextSnapshotTexId = 200000;
  // GL texture ID of the current offscreen target (item atlas)
  public static volatile int currentOffscreenGlTexId = 0;
  // Maps GL offscreen atlas texture ID → Metal snapshot texture ID
  // So all compositing quads from the same atlas use the same snapshot
  public static final java.util.concurrent.ConcurrentHashMap<Integer, Integer> offscreenSnapshotMap = new java.util.concurrent.ConcurrentHashMap<>();
  // Width/height of the current offscreen render target (for projection)
  public static volatile int offscreenWidth = 64;
  public static volatile int offscreenHeight = 64;
  // Number of draws submitted to the current offscreen pass (0 = empty/spurious)
  public static volatile int currentPassDrawCount = 0;
  // Flag: set to true after each offscreen draw, consumed by compositing quad
  // handler.
  // This allows us to detect the interleaved pattern: render item → compositing
  // quad.
  // Only schedule blits when a fresh offscreen draw preceded the compositing
  // quad.
  public static volatile boolean hasNewOffscreenDraw = false;
  // Debug counters for tracking flag set/check/consume
  public static volatile int flagSetCount = 0;
  public static volatile int flagCheckCount = 0;
  public static volatile int flagTrueCount = 0;
  // Last successfully snapshotted atlas GL texture ID (persists across frames)
  // Used as a fallback when MC recreates the atlas with a new GL ID
  public static volatile int lastAtlasGlId = 0;
  // Monotonically increasing frame counter for version tracking
  public static volatile long frameNumber = 0;
  // GL texture IDs whose readback is deferred to compositeOverlay().
  // Item atlas textures are populated one item at a time — reading during
  // addSimpleElementToCurrentLayer would capture an incomplete atlas.
  // Instead we record the ID and read at end-of-frame when all items are done.
  public static final java.util.HashSet<Integer> pendingVolatileReads = new java.util.HashSet<>();

  /**
   * Clear all texture upload caches at the start of each frame.
   * This ensures textures with recycled GL IDs get properly re-uploaded
   * with current content rather than showing stale data.
   *
   * Called from renderFrame() at frame start.
   */
  private static void clearFrameTextureCaches() {
    com.metalrender.render.TextureCacheManager.clearAll();
  }

  public boolean isReady() {
    return this.ready;
  }

  public long getHandle() {
    return this.handle;
  }

  /** True between renderFrame() start and compositeOverlay() end */
  public boolean isInFrame() {
    return this.inRenderFrame;
  }

  /** Current render phase: 0=WORLD, 1=UI */
  public int getRenderPhase() {
    return this.renderPhase;
  }

  /**
   * Switch to UI rendering phase. Computes orthographic projection
   * matching MC's GUI coordinate system (0,0 top-left → scaledWidth,scaledHeight
   * bottom-right).
   */
  public void beginUIPhase() {
    this.renderPhase = 1;
    MinecraftClient mc = MinecraftClient.getInstance();
    int scaledWidth = mc.getWindow().getScaledWidth();
    int scaledHeight = mc.getWindow().getScaledHeight();
    // Build ortho matrix for UI: left=0, right=scaledW, top=0, bottom=scaledH
    // near=-1000, far=1000: GUI vertices use z=0, so the range must include it.
    // zZeroToOne=true for Metal's [0,1] depth range.
    // z=0 → NDC z = (0-(-1000))/(1000-(-1000)) = 0.5 (safely inside [0,1])
    Matrix4f ortho = new Matrix4f().setOrtho(
        0.0f, (float) scaledWidth, (float) scaledHeight, 0.0f, -1000.0f, 1000.0f, true);
    ortho.get(this.orthoProjectionMatrix);
  }

  /**
   * Get the effective projection matrix for the current render phase.
   * WORLD phase returns entityProjection (perspective * viewRotation),
   * UI phase returns orthographic projection.
   */
  public float[] getEffectiveMatrix() {
    return this.renderPhase == 0 ? this.frameProjection : this.orthoProjectionMatrix;
  }

  /**
   * Called at the end of each frame (after all entity/UI rendering has been
   * intercepted by RenderLayerMixin) to flush queued Metal draw commands,
   * render entities/UI on top of terrain, and present the frame.
   */
  public void compositeOverlay() {
    if (this.ready && this.handle != 0L) {
      try {
        // End the offscreen pass tracking.
        // GL has been handling atlas rendering directly (not Metal RTT).
        // We need to read the GL atlas texture and upload it to Metal for compositing.
        if (MetalWorldRenderer.inOffscreenPass) {
          int atlasGlId = MetalWorldRenderer.currentOffscreenGlTexId;
          int drawCount = MetalWorldRenderer.currentPassDrawCount;
          if (drawCount > 0 && atlasGlId > 0) {
            // End Metal offscreen pass and snapshot the RT under the atlas GL ID
            NativeBridge.nEndOffscreenPass(this.handle, atlasGlId);
            MetalWorldRenderer.lastAtlasGlId = atlasGlId;
            System.err.println("[MetalRender] compositeOverlay: ended offscreen pass, snapshot glId="
                + atlasGlId + " (" + drawCount + " Metal RTT draws)");
          }
          MetalWorldRenderer.inOffscreenPass = false;
        }

        // Process pending item cache blits (extract per-slot tiles from atlas snapshot)
        java.util.List<com.metalrender.render.ItemRenderCache.PendingBlit> blits = com.metalrender.render.ItemRenderCache
            .getPendingBlits();
        if (!blits.isEmpty()) {
          int blitCount = 0;
          for (com.metalrender.render.ItemRenderCache.PendingBlit blit : blits) {
            int result = NativeBridge.nBlitToItemCache(this.handle,
                blit.atlasTexId, blit.cacheTexId,
                blit.srcX, blit.srcY, blit.srcW, blit.srcH);
            blitCount++;
            if (blitCount <= 10 || blitCount % 100 == 0) {
              System.err.println("[MetalRender] ItemCache blit #" + blitCount
                  + ": slot " + blit.slotId + " '" + blit.itemKey
                  + "' → texId=" + blit.cacheTexId
                  + " from atlas=" + blit.atlasTexId
                  + " region=(" + blit.srcX + "," + blit.srcY
                  + " " + blit.srcW + "x" + blit.srcH + ")"
                  + " result=" + result);
            }
          }
          com.metalrender.render.ItemRenderCache.clearPendingBlits();
          System.err.println("[MetalRender] compositeOverlay: processed " + blitCount
              + " item cache blits, total cached=" + com.metalrender.render.ItemRenderCache.getCacheSize());
        }

        // Clear per-frame slot rendering tracking
        com.metalrender.render.ItemRenderCache.clearFrameTracking();

        // ---- DEFERRED VOLATILE READBACK ----
        // Item atlas textures are populated one item at a time during
        // prepareItemElements(). Reading the atlas during
        // addSimpleElementToCurrentLayer() would capture an incomplete atlas
        // (only the items rendered so far). By deferring to here, ALL items
        // have been rendered to the atlas.
        if (!pendingVolatileReads.isEmpty()) {
          for (int glTexId : pendingVolatileReads) {
            flushVolatileTexture(this.handle, glTexId);
          }
          pendingVolatileReads.clear();
        }

        NativeBridge.nDrawOverlay(this.handle, 0);
      } catch (Throwable t) {
        MetalLogger.error("flushGenericDraws failed", t);
      } finally {
        this.inRenderFrame = false;
      }
    }
  }

  /**
   * Read a GL atlas texture and upload it to Metal for compositing.
   * GL renders items to the atlas via MC's native pipeline. We snapshot the
   * result and upload it to Metal as a shared texture for GUI compositing.
   */
  private static long glAtlasUploadCount = 0;

  private void uploadGlAtlasToMetal(long handle, int glTexId) {
    try {
      // Save current GL state
      int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
      org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glTexId);

      int w = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
          org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
      int h = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
          org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);

      if (w > 0 && h > 0 && w <= 4096 && h <= 4096) {
        int bufSize = w * h * 4;
        java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(bufSize);

        // Read as RGBA
        org.lwjgl.opengl.GL11.glGetTexImage(
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
            org.lwjgl.opengl.GL11.GL_RGBA,
            org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, buf);

        byte[] pixels = new byte[bufSize];
        buf.rewind();
        buf.get(pixels);
        org.lwjgl.system.MemoryUtil.memFree(buf);

        // Upload to Metal — this replaces any existing texture under this GL ID
        NativeBridge.nUploadGenericTexture(handle, glTexId, w, h, pixels);

        glAtlasUploadCount++;
        if (glAtlasUploadCount <= 10 || glAtlasUploadCount % 500 == 0) {
          System.err.println("[MetalRender] uploadGlAtlasToMetal: glId=" + glTexId
              + " (" + w + "x" + h + ") upload #" + glAtlasUploadCount);
        }
      }

      // Restore GL state
      org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex);
    } catch (Throwable t) {
      if (glAtlasUploadCount <= 5) {
        System.err.println("[MetalRender] uploadGlAtlasToMetal error: " + t.getMessage());
      }
    }
  }

  /**
   * Deferred volatile texture readback: read a GL texture via FBO + glReadPixels
   * and upload to Metal under the same GL texture ID.
   *
   * Called from compositeOverlay() AFTER all items have been rendered to the
   * atlas, ensuring we capture the complete atlas content.
   */
  private static long volatileFlushCount = 0;

  private void flushVolatileTexture(long handle, int glTexId) {
    if (glTexId <= 0) return;
    try {
      int prevTex = org.lwjgl.opengl.GL11.glGetInteger(
          org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
      int prevReadFbo = org.lwjgl.opengl.GL11.glGetInteger(
          org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);

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
        java.nio.ByteBuffer pixBuf = org.lwjgl.system.MemoryUtil.memAlloc(bufSize);

        // FBO + glReadPixels — reliable on macOS for immutable/FBO-attached textures
        int tempFbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
        org.lwjgl.opengl.GL30.glBindFramebuffer(
            org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, tempFbo);
        org.lwjgl.opengl.GL30.glFramebufferTexture2D(
            org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,
            org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glTexId, 0);

        int fbStatus = org.lwjgl.opengl.GL30.glCheckFramebufferStatus(
            org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER);

        byte[] pixels = null;
        if (fbStatus == org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE) {
          // Ensure all GL rendering to this texture has completed
          org.lwjgl.opengl.GL11.glFinish();
          org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h,
              org.lwjgl.opengl.GL11.GL_RGBA,
              org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixBuf);
          pixels = new byte[bufSize];
          pixBuf.rewind();
          pixBuf.get(pixels);
        } else {
          // Fallback: glGetTexImage
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
          NativeBridge.nUploadGenericTexture(handle, glTexId, w, h, pixels);

          volatileFlushCount++;
          if (volatileFlushCount <= 20 || volatileFlushCount % 500 == 0) {
            // Diagnostic: per-channel non-zero counts
            int totalNonZero = 0;
            int nonZeroR = 0, nonZeroG = 0, nonZeroB = 0, nonZeroA = 0;
            // Find first non-transparent pixel for sampling
            int sampleR = -1, sampleG = -1, sampleB = -1, sampleA = -1;
            for (int i = 0; i < pixels.length; i += 4) {
              int r = pixels[i] & 0xFF, g = pixels[i+1] & 0xFF, b = pixels[i+2] & 0xFF, a = pixels[i+3] & 0xFF;
              if (r != 0) nonZeroR++;
              if (g != 0) nonZeroG++;
              if (b != 0) nonZeroB++;
              if (a != 0) nonZeroA++;
              if (r != 0 || g != 0 || b != 0 || a != 0) totalNonZero++;
              if (sampleR == -1 && a > 0) { sampleR = r; sampleG = g; sampleB = b; sampleA = a; }
            }
            System.err.println("[MetalRender] flushVolatileTexture: glId=" + glTexId
                + " (" + w + "x" + h + ")"
                + " nonZeroPx=" + totalNonZero + "/" + (pixels.length / 4)
                + " R=" + nonZeroR + " G=" + nonZeroG + " B=" + nonZeroB + " A=" + nonZeroA
                + " sample=(" + sampleR + "," + sampleG + "," + sampleB + "," + sampleA + ")"
                + " flush#" + volatileFlushCount);
          }
        }
      }

      org.lwjgl.opengl.GL11.glBindTexture(
          org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex);
    } catch (Throwable t) {
      if (volatileFlushCount <= 5) {
        System.err.println("[MetalRender] flushVolatileTexture error: " + t.getMessage());
        t.printStackTrace(System.err);
      }
    }
  }

  public void renderFrame(Object viewport, Object matrices, double x, double y,
      double z) {
    if (this.ready && this.handle != 0L) {
      try {
        this.inRenderFrame = true;
        this.renderPhase = 0; // Start in WORLD phase
        MetalWorldRenderer.nextSnapshotTexId = 200000; // Reset RTT snapshot IDs each frame
        // Don't clear offscreenSnapshotMap — it persists across frames for cached
        // atlases
        MetalWorldRenderer.currentOffscreenGlTexId = 0;
        MetalWorldRenderer.currentPassDrawCount = 0;
        MetalWorldRenderer.hasNewOffscreenDraw = false;
        MetalWorldRenderer.pendingVolatileReads.clear();
        MetalWorldRenderer.frameNumber++;

        // CRITICAL: Clear texture upload caches each frame.
        // GL texture IDs can be recycled when MC destroys and recreates textures.
        // Without clearing, stale Metal textures from old GL IDs persist,
        // causing wrong textures (e.g. block atlas on player model).
        clearFrameTextureCaches();
        // CRITICAL: Ensure CAMetalLayer is attached to window before rendering
        // Without this, all rendering happens but is never presented to screen
        MetalSurfaceManager.ensureSurface(this.handle);
        MetalSurfaceManager.pollAttachment();

        // FEATURE_009: Upload atlas texture if flagged for reupload
        // On first frame, always force an atlas upload attempt
        if (this.firstFrame) {
          this.firstFrame = false;
          this.atlasNeedsReupload = true;
          MetalLogger.info("[MetalRender] First frame — forcing atlas upload check");
        }
        this.uploadAtlas();

        // FEATURE_005: Check if test triangle mode is enabled
        boolean testTriangleMode = System.getenv("TEST_TRIANGLE") != null;

        MinecraftClient mc = MinecraftClient.getInstance();
        Camera camera = mc.gameRenderer.getCamera();
        int framebufferWidth = Math.max(1, mc.getWindow().getFramebufferWidth());
        int framebufferHeight = Math.max(1, mc.getWindow().getFramebufferHeight());
        if (framebufferWidth != this.lastWidth ||
            framebufferHeight != this.lastHeight) {
          NativeBridge.nResize(this.handle, framebufferWidth, framebufferHeight,
              this.lastScale);
          this.lastWidth = framebufferWidth;
          this.lastHeight = framebufferHeight;
        }

        // Use zZeroToOne=true for Metal's [0,1] depth range (vs OpenGL's [-1,+1])
        Matrix4f projection = new Matrix4f().setPerspective(
            this.getFovRadians(mc),
            (float) framebufferWidth / (float) framebufferHeight, 0.05F, 4096.0F, true);
        Matrix4f view = this.buildViewMatrix(camera);

        // Build entity projection matrix:
        // Entity vertex positions from MC's MatrixStack are in camera-relative space
        // but NOT rotated by the camera view. We need proj * viewRotation.
        // Extract rotation from view by zeroing out the translation column.
        Matrix4f viewRotation = new Matrix4f(view);
        viewRotation.m30(0.0f); // zero translation X
        viewRotation.m31(0.0f); // zero translation Y
        viewRotation.m32(0.0f); // zero translation Z
        // entityProj = projection * viewRotation
        Matrix4f entityProj = new Matrix4f();
        projection.mul(viewRotation, entityProj);
        entityProj.get(this.frameProjection);
        NativeBridge.nSetProjectionMatrix(this.handle, this.frameProjection);

        Matrix4f viewProjMatrix = new Matrix4f();
        projection.mul(view, viewProjMatrix);
        this.renderOptimizer.updateFrame(this.handle, camera, viewProjMatrix,
            framebufferWidth, framebufferHeight);

        float scale = MetalRenderConfig.resolutionScale();
        if (Math.abs(scale - this.lastScale) > 0.001F) {
          NativeBridge.nResize(this.handle, this.lastWidth, this.lastHeight,
              scale);
          this.lastScale = scale;
        }

        viewProjMatrix.get(this.frameViewProjection);
        NativeBridge.nSetTemporalJitter(this.handle, 0.0f, 0.0f, 1.0f);
        if (this.pipelineCache != null) {
          this.pipelineCache.prewarm();
        }

        // NOTE: Do NOT call persistentArena.reset() here — chunk mesh data
        // persists across frames in the GPU buffer. Resetting every frame would
        // overwrite previously-uploaded chunk vertices that DrawCommands still
        // reference.

        // FEATURE_005: Test triangle rendering
        if (testTriangleMode) {
          MetalLogger.info("[FEATURE_005] Test Triangle Mode ENABLED - Rendering validation triangle");

          // Execute complete pipeline for test triangle validation
          NativeBridge.nBeginFrame(this.handle, this.frameViewProjection, null,
              0.0F, 1.0F);
          NativeBridge.nClearIndirectCommands(this.handle);
          NativeBridge.nClearGenericDraws(this.handle);

          // nDrawTerrain() will detect TEST_TRIANGLE env var and render test geometry
          NativeBridge.nDrawTerrain(this.handle, 0); // Pass 0: Opaque

          // nExecuteIndirect() will execute the test triangle command and present frame
          int commandsExecuted = NativeBridge.nExecuteIndirect(this.handle, 0);

          MetalLogger.info("[FEATURE_005] Test triangle rendered: {} commands executed", commandsExecuted);
        } else {
          // Compute sky color from MC world state
          this.frameStartNanos = System.nanoTime();
          float tickDelta = 0.0f;
          try {
            tickDelta = mc.getRenderTickCounter().getTickProgress(true);
          } catch (Throwable ignored) {
          }
          this.computeSkyState(mc, camera, tickDelta);

          // Compute sky brightness using Minecraft's celestial angle formula.
          // This matches the wiki's daylight cycle: internal sky light 15 during day,
          // decreasing to minimum 4 at night (skyDarkness=11).
          float skyBrightness = this.computeSkyBrightness(mc, tickDelta);
          NativeBridge.nSetSkyBrightness(this.handle, skyBrightness);

          // Normal terrain rendering - pass computed sky color
          NativeBridge.nBeginFrame(this.handle, this.frameViewProjection, this.skyColor,
              0.0F, 1.0F);
          NativeBridge.nClearIndirectCommands(this.handle);
          NativeBridge.nClearGenericDraws(this.handle);

          // Render celestial bodies (sun, moon) as generic draws in world phase
          if (this.skyEnabled) {
            this.renderCelestialBodies(camera);
          }

          MeshShaderBackend backend = this.meshBackend();
          int queued = 0;
          if (backend != null) {
            queued = backend.emitDraws(this.handle, this.renderOptimizer, camera);
          }

          // Diagnostic logging (throttled to ~1 per second at 60fps)
          if (this.diagCounter++ % 60 == 0) {
            int meshCount = (backend != null) ? backend.chunkMeshCount() : -1;
            RenderOptimizer.PerformanceStats diagStats = this.renderOptimizer.getFrameStats();
            net.minecraft.util.math.Vec3d camPos = camera.getCameraPos();
            System.err.println("[MetalRender] [DIAG] chunkMeshes=" + meshCount + " queued=" + queued
                + " frustumCulled=" + diagStats.frustumCulled
                + " camera=(" + String.format("%.1f,%.1f,%.1f", camPos.x, camPos.y, camPos.z) + ")"
                + " arena=" + (this.persistentArena.cursor() / 1024 / 1024) + "MB/"
                + (this.persistentArena.capacity() / 1024 / 1024) + "MB"
                + " freed=" + this.persistentArena.totalFreed() + " freeBlocks="
                + this.persistentArena.freeBlockCount());
            // Log first 4 matrix values for verification
            System.err.println("[MetalRender] [DIAG-VP] m[0..3]="
                + String.format("%.4f,%.4f,%.4f,%.4f",
                    this.frameViewProjection[0], this.frameViewProjection[1],
                    this.frameViewProjection[2], this.frameViewProjection[3])
                + " m[12..15]=" + String.format("%.4f,%.4f,%.4f,%.4f",
                    this.frameViewProjection[12], this.frameViewProjection[13],
                    this.frameViewProjection[14], this.frameViewProjection[15]));
          }

          RenderOptimizer.PerformanceStats stats = this.renderOptimizer.getFrameStats();
          int drawn = Math.max(0, stats.totalChunks - stats.frustumCulled -
              stats.occlusionCulled);
          PerformanceController.accumulateChunkStats(stats.totalChunks, drawn,
              stats.frustumCulled,
              stats.occlusionCulled);

          // FEATURE_008: Bind terrain resources (pipeline, vertex buffer, uniforms)
          // nDrawTerrain sets up the render encoder state for indirect draws
          NativeBridge.nDrawTerrain(this.handle, 0); // Pass 0: Opaque terrain

          // FEATURE_008 FIX: Pass passIndex (0=opaque), NOT command count
          int executed = NativeBridge.nExecuteIndirect(this.handle, 0);

          // Log draw execution results (throttled)
          if (this.diagCounter % 60 == 1) {
            System.err.println("[MetalRender] [DRAW] queued=" + queued + " executed=" + executed);
          }

          this.renderOptimizer.finalizeFrame();

          // Track frame timing for F3 overlay
          long elapsed = System.nanoTime() - this.frameStartNanos;
          this.lastFrameTimeNanos = elapsed;
          this.frameTimes[this.frameTimeIndex % this.frameTimes.length] = elapsed;
          this.frameTimeIndex++;
          this.totalFramesDone++;
        }
      } catch (Throwable var15) {
        MetalLogger.error("renderFrame failed", var15);
      }
    }
  }

  public void uploadBuildResult(Object result) {
    if (!this.ready || this.handle == 0L || result == null) {
      return;
    }

    if (!(result instanceof ChunkBuildOutput output)) {
      return;
    }

    MeshShaderBackend backend = this.meshBackend();
    if (backend != null) {
      backend.uploadBuildOutput(this.handle, this.persistentArena, output);
    }
  }

  private MeshShaderBackend meshBackend() {
    MeshShaderBackend backend = this.cachedMeshBackend;
    if (backend == null) {
      backend = MetalRenderClient.getMeshBackend();
      if (backend != null) {
        this.cachedMeshBackend = backend;
      }
    }
    return backend;
  }

  private Matrix4f buildViewMatrix(Camera camera) {
    net.minecraft.util.math.Vec3d camPos = camera.getCameraPos();
    float px = (float) camPos.x;
    float py = (float) camPos.y;
    float pz = (float) camPos.z;

    // Minecraft camera conventions:
    // yaw=0 → south (+Z), yaw=90 → west (-X)
    // pitch=0 → horizontal, pitch>0 → looking down
    float yawRad = (float) Math.toRadians(camera.getYaw());
    float pitchRad = (float) Math.toRadians(camera.getPitch());
    float cosPitch = (float) Math.cos(pitchRad);
    float sinPitch = (float) Math.sin(pitchRad);
    float cosYaw = (float) Math.cos(yawRad);
    float sinYaw = (float) Math.sin(yawRad);

    // Look direction from Minecraft yaw/pitch
    float lookX = -sinYaw * cosPitch;
    float lookY = -sinPitch;
    float lookZ = cosYaw * cosPitch;

    // When looking nearly straight up or down, the look direction is
    // nearly parallel to the default up vector (0,1,0), which makes
    // lookAt() degenerate (cross product → zero). Use a tilted up
    // vector in that case to avoid NaN in the view matrix.
    float upX = 0.0f, upY = 1.0f, upZ = 0.0f;
    if (Math.abs(cosPitch) < 0.001f) {
      // Looking straight up or down — use the forward direction as up hint
      upX = sinYaw;
      upY = 0.0f;
      upZ = -cosYaw;
    }

    Matrix4f view = new Matrix4f();
    view.lookAt(
        px, py, pz, // eye
        px + lookX, py + lookY, pz + lookZ, // center
        upX, upY, upZ // up
    );
    return view;
  }

  private float getFovRadians(MinecraftClient client) {
    double fov = 70.0D;
    try {
      fov = client.options.getFov().getValue();
    } catch (Throwable ignored) {
    }
    return (float) Math.toRadians(fov);
  }

  // =========================================================================
  // SKY RENDERING: Dynamic sky color + sun/moon cycle
  // =========================================================================

  /**
   * Compute sky brightness using Minecraft's celestial angle formula.
   * Matches the daylight cycle described at
   * https://minecraft.wiki/w/Daylight_cycle:
   * - Daytime (ticks 1000-12000): sky light 15, skyBrightness ~1.0
   * - Sunset (ticks 12000-13000): sky light decreases
   * - Night (ticks 13000-23000): sky light minimum 4, skyBrightness ~0.267
   * - Sunrise (ticks 23000-24000): sky light increases
   *
   * The celestial angle determines a 'darkening factor' f (0=day, 1=night).
   * skyDarkness = f * 11 (integer 0-11), then skyBrightness = (15 - skyDarkness)
   * / 15.
   * Rain/thunder further dims the sky.
   */
  private float computeSkyBrightness(MinecraftClient mc, float tickDelta) {
    ClientWorld world = mc.world;
    if (world == null)
      return 1.0f;

    try {
      // Get time of day with tick delta for smooth interpolation
      long timeOfDay = world.getTimeOfDay();
      float time = (float) ((timeOfDay % 24000L) + tickDelta);

      // MC's celestial angle formula (from Dimension/World class):
      // d = fract(time/24000 - 0.25)
      // e = 0.5 - cos(d * PI) / 2
      // celestialAngle = (d * 2 + e) / 3
      double d = (time / 24000.0) - 0.25;
      d = d - Math.floor(d); // fractional part
      double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
      float celestialAngle = (float) ((d * 2.0 + e) / 3.0);

      // Sky darkness factor: 0.0 = full day, 1.0 = full night
      float f = 1.0f - ((float) Math.cos(celestialAngle * Math.PI * 2.0f) * 2.0f + 0.2f);
      f = Math.max(0.0f, Math.min(1.0f, f));

      // Sky darkness: 0-11 (MC uses integer, we keep float for smoother transitions)
      float skyDarkness = f * 11.0f;

      // skyBrightness = fraction of sky light still active
      // At noon: skyDarkness=0 -> skyBrightness=1.0
      // At midnight: skyDarkness=11 -> skyBrightness=4/15=0.267
      float skyBrightness = (15.0f - skyDarkness) / 15.0f;

      // Apply rain dimming: rain reduces sky light by ~3 levels, thunder by ~5
      float rainGrad = world.getRainGradient(tickDelta);
      float thunderGrad = world.getThunderGradient(tickDelta);
      if (rainGrad > 0.0f) {
        // Rain reduces effective sky light by up to 3
        float rainReduction = rainGrad * 3.0f / 15.0f;
        skyBrightness = Math.max(0.1f, skyBrightness - rainReduction);
      }
      if (thunderGrad > 0.0f) {
        // Thunder reduces by additional 2 (total ~5 with rain)
        float thunderReduction = thunderGrad * 2.0f / 15.0f;
        skyBrightness = Math.max(0.1f, skyBrightness - thunderReduction);
      }

      // Clamp: never fully black (MC always has some ambient light in Overworld)
      skyBrightness = Math.max(0.1f, Math.min(1.0f, skyBrightness));

      return skyBrightness;
    } catch (Throwable t) {
      // Fallback: derive from sky color luminance
      float lum = 0.299f * this.skyColor[0] + 0.587f * this.skyColor[1]
          + 0.114f * this.skyColor[2];
      return Math.max(0.15f, Math.min(1.0f, lum * 1.8f));
    }
  }

  /**
   * Compute sky color and celestial body angles from MC world state.
   * Uses EnvironmentAttributes via Camera's interpolator for smooth transitions.
   */
  private void computeSkyState(MinecraftClient mc, Camera camera, float tickDelta) {
    ClientWorld world = mc.world;
    if (world == null) {
      // No world — use default sky blue
      this.skyColor[0] = 0.392f;
      this.skyColor[1] = 0.584f;
      this.skyColor[2] = 0.929f;
      this.skyColor[3] = 1.0f;
      this.skyEnabled = false;
      return;
    }

    // Check dimension type for sky rendering
    try {
      DimensionType dimType = world.getDimension();
      DimensionType.Skybox skybox = dimType.skybox();
      if (skybox == DimensionType.Skybox.NONE) {
        // Nether — dark reddish sky, no sun/moon
        this.skyColor[0] = 0.2f;
        this.skyColor[1] = 0.03f;
        this.skyColor[2] = 0.03f;
        this.skyColor[3] = 1.0f;
        this.skyEnabled = false;
        return;
      } else if (skybox == DimensionType.Skybox.END) {
        // End — dark purplish sky
        this.skyColor[0] = 0.0f;
        this.skyColor[1] = 0.0f;
        this.skyColor[2] = 0.0f;
        this.skyColor[3] = 1.0f;
        this.skyEnabled = false;
        return;
      }
      // OVERWORLD — compute dynamic sky
      this.skyEnabled = true;
    } catch (Throwable t) {
      // Fallback to sky blue
      this.skyColor[0] = 0.392f;
      this.skyColor[1] = 0.584f;
      this.skyColor[2] = 0.929f;
      this.skyColor[3] = 1.0f;
      this.skyEnabled = true;
      return;
    }

    // Get sky state from EnvironmentAttributes interpolator
    try {
      EnvironmentAttributeInterpolator interp = camera.getEnvironmentAttributeInterpolator();

      // Sky color (packed ARGB integer)
      int skyColorPacked = (Integer) interp.get(EnvironmentAttributes.SKY_COLOR_VISUAL, tickDelta);
      float a = ((skyColorPacked >> 24) & 0xFF) / 255.0f;
      float r = ((skyColorPacked >> 16) & 0xFF) / 255.0f;
      float g = ((skyColorPacked >> 8) & 0xFF) / 255.0f;
      float b = (skyColorPacked & 0xFF) / 255.0f;
      this.skyColor[0] = r;
      this.skyColor[1] = g;
      this.skyColor[2] = b;
      this.skyColor[3] = a > 0.0f ? a : 1.0f;

      // Sun/moon angles (degrees from EnvironmentAttributes, convert to radians)
      float sunDeg = (Float) interp.get(EnvironmentAttributes.SUN_ANGLE_VISUAL, tickDelta);
      float moonDeg = (Float) interp.get(EnvironmentAttributes.MOON_ANGLE_VISUAL, tickDelta);
      this.sunAngle = sunDeg * 0.017453292f; // degrees to radians
      this.moonAngle = moonDeg * 0.017453292f;

      // Star brightness and rain gradient
      this.starBrightness = (Float) interp.get(EnvironmentAttributes.STAR_BRIGHTNESS_VISUAL, tickDelta);
      this.rainGradient = 1.0f - world.getRainGradient(tickDelta);

      // Moon phase
      MoonPhase phase = (MoonPhase) interp.get(EnvironmentAttributes.MOON_PHASE_VISUAL, tickDelta);
      this.moonPhaseIndex = phase.getIndex();

      // Apply rain dimming to sky color
      if (this.rainGradient < 1.0f) {
        float dim = 0.5f + 0.5f * this.rainGradient;
        this.skyColor[0] *= dim;
        this.skyColor[1] *= dim;
        this.skyColor[2] *= dim;
      }

      // Sunset/sunrise tinting: blend sky toward warm orange/red near the horizon
      // sunAngle in degrees: ~0/360 = sunrise, ~90 = noon, ~180 = sunset, ~270 =
      // midnight
      float sunNorm = sunDeg / 360.0f; // 0-1 over full day
      // Detect sunrise (sunNorm ~0.0 or ~1.0) and sunset (sunNorm ~0.5)
      // Sunrise window: sunNorm in [0.94, 1.0] or [0.0, 0.06]
      // Sunset window: sunNorm in [0.44, 0.56]
      float sunsetStrength = 0.0f;
      if (sunNorm > 0.44f && sunNorm < 0.56f) {
        // Sunset: peak at 0.5
        sunsetStrength = 1.0f - Math.abs(sunNorm - 0.5f) * (1.0f / 0.06f);
        sunsetStrength = Math.max(0.0f, Math.min(1.0f, sunsetStrength));
      } else if (sunNorm < 0.06f || sunNorm > 0.94f) {
        // Sunrise: peak at 0.0/1.0
        float dist = sunNorm < 0.5f ? sunNorm : (1.0f - sunNorm);
        sunsetStrength = 1.0f - dist * (1.0f / 0.06f);
        sunsetStrength = Math.max(0.0f, Math.min(1.0f, sunsetStrength));
      }
      if (sunsetStrength > 0.01f) {
        // Blend toward warm sunset color (orange-red)
        float blend = sunsetStrength * 0.6f * this.rainGradient;
        this.skyColor[0] = this.skyColor[0] * (1.0f - blend) + 0.9f * blend; // more red
        this.skyColor[1] = this.skyColor[1] * (1.0f - blend) + 0.4f * blend; // some orange
        this.skyColor[2] = this.skyColor[2] * (1.0f - blend) + 0.15f * blend; // less blue
      }

      if (this.diagCounter % 300 == 0) {
        MetalLogger.info("[SKY] color=({},{},{}) sunAngle={} moonAngle={} moonPhase={} rain={}",
            String.format("%.3f", r), String.format("%.3f", g), String.format("%.3f", b),
            String.format("%.1f", sunDeg), String.format("%.1f", moonDeg),
            this.moonPhaseIndex, String.format("%.2f", this.rainGradient));
      }
    } catch (Throwable t) {
      // Fallback: compute basic sky color from time of day
      try {
        long timeOfDay = world.getTimeOfDay();
        float dayFraction = (float) (timeOfDay % 24000L) / 24000.0f;
        this.sunAngle = dayFraction * (float) Math.PI * 2.0f;
        this.moonAngle = this.sunAngle + (float) Math.PI;

        // Simple day/night color interpolation
        // Day: 0-12000 ticks, sunset: 12000-13000, night: 13000-23000, sunrise:
        // 23000-24000
        float brightness;
        if (dayFraction < 0.25f) {
          brightness = 1.0f; // Day
        } else if (dayFraction < 0.30f) {
          brightness = 1.0f - (dayFraction - 0.25f) * 20.0f; // Sunset
        } else if (dayFraction < 0.75f) {
          brightness = 0.0f; // Night
        } else if (dayFraction < 0.80f) {
          brightness = (dayFraction - 0.75f) * 20.0f; // Sunrise
        } else {
          brightness = 1.0f; // Day
        }

        // Interpolate between night sky (dark blue) and day sky (sky blue)
        this.skyColor[0] = 0.01f + brightness * 0.38f;
        this.skyColor[1] = 0.01f + brightness * 0.57f;
        this.skyColor[2] = 0.05f + brightness * 0.88f;
        this.skyColor[3] = 1.0f;
      } catch (Throwable t2) {
        // Ultimate fallback
        this.skyColor[0] = 0.392f;
        this.skyColor[1] = 0.584f;
        this.skyColor[2] = 0.929f;
        this.skyColor[3] = 1.0f;
      }
    }
  }

  /**
   * Upload sun and moon textures from MC's resource pack to Metal.
   * Uses multiple strategies to find textures: Fabric ModContainer, classloader,
   * resource manager.
   * Retries on failure until MAX_CELESTIAL_RETRIES is reached, then falls back to
   * procedural.
   */
  private void uploadCelestialTextures() {
    if (this.sunMoonTexturesUploaded || this.handle == 0L)
      return;

    try {
      // Load MC's actual sun texture using multiple strategies
      boolean sunLoaded = false;
      try {
        InputStream sunStream = findMCResource("assets/minecraft/textures/environment/sun.png");
        if (sunStream != null) {
          NativeImage sunImage = NativeImage.read(sunStream);
          sunStream.close();
          int sunW = sunImage.getWidth();
          int sunH = sunImage.getHeight();

          // Diagnostic: sample a few pixels to understand getColorArgb format
          // Center pixel should be bright (sun), corner should be transparent
          int centerVal = sunImage.getColorArgb(sunW / 2, sunH / 2);
          int cornerVal = sunImage.getColorArgb(0, 0);
          MetalLogger.info("[SKY] Sun center pixel raw=0x{} corner pixel raw=0x{}",
              Integer.toHexString(centerVal), Integer.toHexString(cornerVal));
          MetalLogger.info("[SKY] Sun center: A={} R={} G={} B={} (assuming ARGB)",
              (centerVal >> 24) & 0xFF, (centerVal >> 16) & 0xFF,
              (centerVal >> 8) & 0xFF, centerVal & 0xFF);
          MetalLogger.info("[SKY] Sun corner: A={} R={} G={} B={} (assuming ARGB)",
              (cornerVal >> 24) & 0xFF, (cornerVal >> 16) & 0xFF,
              (cornerVal >> 8) & 0xFF, cornerVal & 0xFF);

          byte[] sunPixels = new byte[sunW * sunH * 4];
          for (int py = 0; py < sunH; py++) {
            for (int px = 0; px < sunW; px++) {
              int argb = sunImage.getColorArgb(px, py);
              int idx = (py * sunW + px) * 4;
              // getColorArgb returns ARGB: bits 24-31=A, 16-23=R, 8-15=G, 0-7=B
              // Metal texture is RGBA8Unorm: byte 0=R, 1=G, 2=B, 3=A
              int a = (argb >> 24) & 0xFF;
              int r = (argb >> 16) & 0xFF;
              int g = (argb >> 8) & 0xFF;
              int b = argb & 0xFF;
              sunPixels[idx] = (byte) r;
              sunPixels[idx + 1] = (byte) g;
              sunPixels[idx + 2] = (byte) b;
              sunPixels[idx + 3] = (byte) a;
            }
          }
          NativeBridge.nUploadGenericTexture(this.handle, SUN_TEXTURE_ID, sunW, sunH, sunPixels);
          sunImage.close();
          sunLoaded = true;
          MetalLogger.info("[SKY] Loaded MC sun texture {}x{}", sunW, sunH);
        }
      } catch (Throwable t) {
        MetalLogger.error("[SKY] Failed to load sun: {} - {}", t.getClass().getName(), t.getMessage());
      }

      // Load MC's actual moon phases texture (4x2 grid)
      boolean moonLoaded = false;
      try {
        InputStream moonStream = findMCResource("assets/minecraft/textures/environment/moon_phases.png");
        if (moonStream != null) {
          NativeImage moonImage = NativeImage.read(moonStream);
          moonStream.close();
          int fullW = moonImage.getWidth();
          int fullH = moonImage.getHeight();
          int phaseW = fullW / 4;
          int phaseH = fullH / 2;
          for (int phase = 0; phase < 8; phase++) {
            int col = phase % 4;
            int row = phase / 4;
            int startX = col * phaseW;
            int startY = row * phaseH;
            byte[] phasePixels = new byte[phaseW * phaseH * 4];
            for (int py = 0; py < phaseH; py++) {
              for (int px = 0; px < phaseW; px++) {
                int argb = moonImage.getColorArgb(startX + px, startY + py);
                int idx = (py * phaseW + px) * 4;
                phasePixels[idx] = (byte) ((argb >> 16) & 0xFF);
                phasePixels[idx + 1] = (byte) ((argb >> 8) & 0xFF);
                phasePixels[idx + 2] = (byte) (argb & 0xFF);
                phasePixels[idx + 3] = (byte) ((argb >> 24) & 0xFF);
              }
            }
            NativeBridge.nUploadGenericTexture(this.handle, MOON_TEXTURE_ID + phase, phaseW, phaseH, phasePixels);
          }
          moonImage.close();
          moonLoaded = true;
          MetalLogger.info("[SKY] Loaded MC moon phases {}x{} (phase={}x{})", fullW, fullH, phaseW, phaseH);
        }
      } catch (Throwable t) {
        MetalLogger.error("[SKY] Failed to load moon: {} - {}", t.getClass().getName(), t.getMessage());
      }

      // If textures not loaded, retry next frame (resources may not be ready yet)
      if (!sunLoaded || !moonLoaded) {
        this.celestialTextureRetries++;
        if (this.celestialTextureRetries < MAX_CELESTIAL_RETRIES) {
          if (this.celestialTextureRetries % 60 == 0) {
            MetalLogger.info("[SKY] Texture retry {}/{}", this.celestialTextureRetries, MAX_CELESTIAL_RETRIES);
          }
          return; // Don't set flag, retry next frame
        }
        MetalLogger.warn("[SKY] Max retries reached, using procedural fallbacks");
      }

      // Procedural sun fallback
      if (!sunLoaded) {
        int sz = 64;
        byte[] px = new byte[sz * sz * 4];
        float c = sz / 2.0f, r = sz / 2.0f - 2.0f;
        for (int py = 0; py < sz; py++) {
          for (int ppx = 0; ppx < sz; ppx++) {
            float dx = ppx - c, dy = py - c;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            int idx = (py * sz + ppx) * 4;
            if (d < r * 0.7f) {
              px[idx] = (byte) 255;
              px[idx + 1] = (byte) 255;
              px[idx + 2] = (byte) 200;
              px[idx + 3] = (byte) 255;
            } else if (d < r) {
              float t = (d - r * 0.7f) / (r * 0.3f);
              px[idx] = (byte) 255;
              px[idx + 1] = (byte) 255;
              px[idx + 2] = (byte) 180;
              px[idx + 3] = (byte) (255 * (1 - t * t));
            }
          }
        }
        NativeBridge.nUploadGenericTexture(this.handle, SUN_TEXTURE_ID, sz, sz, px);
      }

      // Procedural moon fallback
      if (!moonLoaded) {
        int sz = 64;
        byte[] px = new byte[sz * sz * 4];
        float c = sz / 2.0f, r = sz / 2.0f - 2.0f;
        for (int py = 0; py < sz; py++) {
          for (int ppx = 0; ppx < sz; ppx++) {
            float dx = ppx - c, dy = py - c;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            int idx = (py * sz + ppx) * 4;
            if (d < r * 0.85f) {
              int g = (int) (220 * (Math.sin(ppx * 0.8) * Math.cos(py * 0.6) * 0.1 + 0.9));
              px[idx] = (byte) (g + 10);
              px[idx + 1] = (byte) (g + 10);
              px[idx + 2] = (byte) (g + 20);
              px[idx + 3] = (byte) 255;
            } else if (d < r) {
              float t = (d - r * 0.85f) / (r * 0.15f);
              px[idx] = (byte) 200;
              px[idx + 1] = (byte) 200;
              px[idx + 2] = (byte) 220;
              px[idx + 3] = (byte) (255 * (1 - t));
            }
          }
        }
        for (int phase = 0; phase < 8; phase++)
          NativeBridge.nUploadGenericTexture(this.handle, MOON_TEXTURE_ID + phase, sz, sz, px);
      }

      // Upload star texture (small soft white dot)
      int starSz = 8;
      byte[] starPx = new byte[starSz * starSz * 4];
      float sc = starSz / 2.0f - 0.5f;
      for (int py = 0; py < starSz; py++) {
        for (int ppx = 0; ppx < starSz; ppx++) {
          float dx = ppx - sc, dy = py - sc;
          float d = (float) Math.sqrt(dx * dx + dy * dy);
          float md = sc + 0.5f;
          int idx = (py * starSz + ppx) * 4;
          if (d < md) {
            float a = 1 - d / md;
            a *= a;
            starPx[idx] = (byte) 255;
            starPx[idx + 1] = (byte) 255;
            starPx[idx + 2] = (byte) 255;
            starPx[idx + 3] = (byte) (a * 255);
          }
        }
      }
      NativeBridge.nUploadGenericTexture(this.handle, STAR_TEXTURE_ID, starSz, starSz, starPx);

      this.sunMoonTexturesUploaded = true;
      MetalLogger.info("[SKY] Celestial textures ready (sun={}, moon={}, retries={})",
          sunLoaded ? "MC" : "procedural", moonLoaded ? "MC" : "procedural", this.celestialTextureRetries);
    } catch (Throwable t) {
      MetalLogger.error("[SKY] Failed to upload celestial textures: {}", t.getMessage());
    }
  }

  /**
   * Find a MC resource using multiple strategies:
   * 1. Fabric ModContainer root paths (most reliable in dev/prod)
   * 2. Thread context classloader
   * 3. MinecraftClient's classloader
   * 4. MC resource manager
   */
  private InputStream findMCResource(String resourcePath) {
    // Only log detailed diagnostics on first attempt
    boolean verbose = (this.celestialTextureRetries == 0);

    // Strategy 1: Fabric ModContainer — access game JAR root paths directly
    try {
      Optional<ModContainer> mcMod = FabricLoader.getInstance().getModContainer("minecraft");
      if (mcMod.isPresent()) {
        if (verbose) {
          List<Path> roots = mcMod.get().getRootPaths();
          MetalLogger.info("[SKY] Fabric MC mod found, {} root paths", roots.size());
          for (Path root : roots) {
            MetalLogger.info("[SKY]   root: {} (exists={})", root, Files.exists(root));
          }
        }
        Optional<Path> found = mcMod.get().findPath(resourcePath);
        if (verbose)
          MetalLogger.info("[SKY] findPath('{}') = present={}", resourcePath, found.isPresent());
        if (found.isPresent()) {
          boolean exists = Files.exists(found.get());
          if (verbose)
            MetalLogger.info("[SKY]   path={} exists={}", found.get(), exists);
          if (exists) {
            return Files.newInputStream(found.get());
          }
        }
        for (Path root : mcMod.get().getRootPaths()) {
          Path resolved = root.resolve(resourcePath);
          if (Files.exists(resolved)) {
            MetalLogger.info("[SKY] Found via Fabric root: {}", resolved);
            return Files.newInputStream(resolved);
          }
        }
      } else if (verbose) {
        MetalLogger.info("[SKY] Fabric: 'minecraft' mod container not found");
      }
    } catch (Throwable t) {
      if (verbose)
        MetalLogger.info("[SKY] Fabric strategy exception: {} - {}", t.getClass().getName(), t.getMessage());
    }

    // Strategy 2: Thread context classloader (Knot classloader in Fabric)
    try {
      ClassLoader contextCl = Thread.currentThread().getContextClassLoader();
      if (contextCl != null) {
        if (verbose)
          MetalLogger.info("[SKY] Context CL class: {}", contextCl.getClass().getName());
        InputStream is = contextCl.getResourceAsStream(resourcePath);
        if (is != null) {
          MetalLogger.info("[SKY] Found via context classloader: {}", resourcePath);
          return is;
        }
      }
    } catch (Throwable t) {
      if (verbose)
        MetalLogger.info("[SKY] Context CL exception: {}", t.getMessage());
    }

    // Strategy 3: MC class classloader
    try {
      ClassLoader mcCl = MinecraftClient.class.getClassLoader();
      if (verbose)
        MetalLogger.info("[SKY] MC CL class: {}", mcCl != null ? mcCl.getClass().getName() : "null");
      InputStream is = MinecraftClient.class.getResourceAsStream("/" + resourcePath);
      if (is != null) {
        MetalLogger.info("[SKY] Found via MC classloader: {}", resourcePath);
        return is;
      }
    } catch (Throwable t) {
      if (verbose)
        MetalLogger.info("[SKY] MC CL exception: {}", t.getMessage());
    }

    // Strategy 4: System classloader (has full classpath visible)
    try {
      ClassLoader sysCl = ClassLoader.getSystemClassLoader();
      if (sysCl != null) {
        InputStream is = sysCl.getResourceAsStream(resourcePath);
        if (is != null) {
          MetalLogger.info("[SKY] Found via system classloader: {}", resourcePath);
          return is;
        }
        if (verbose)
          MetalLogger.info("[SKY] System CL: not found");
      }
    } catch (Throwable t) {
      if (verbose)
        MetalLogger.info("[SKY] System CL exception: {}", t.getMessage());
    }

    // Strategy 5: Resource manager (Optional variant)
    try {
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc != null && mc.getResourceManager() != null) {
        String idPath = resourcePath;
        if (idPath.startsWith("assets/minecraft/")) {
          idPath = idPath.substring("assets/minecraft/".length());
        }
        Identifier id = Identifier.ofVanilla(idPath);
        Optional<Resource> res = mc.getResourceManager().getResource(id);
        if (verbose)
          MetalLogger.info("[SKY] ResourceManager.getResource('{}') = present={}", id, res.isPresent());
        if (res.isPresent()) {
          return res.get().getInputStream();
        }
      }
    } catch (Throwable t) {
      if (verbose)
        MetalLogger.info("[SKY] ResourceManager exception: {}", t.getMessage());
    }

    // Strategy 6: Search classpath JARs directly for the resource
    try {
      String classpath = System.getProperty("java.class.path");
      if (classpath != null) {
        String[] entries = classpath.split(java.io.File.pathSeparator);
        for (String entry : entries) {
          java.io.File f = new java.io.File(entry);
          if (f.isFile() && (entry.endsWith(".jar") || entry.endsWith(".zip"))) {
            try {
              JarFile jar = new JarFile(f);
              JarEntry je = jar.getJarEntry(resourcePath);
              if (je != null) {
                MetalLogger.info("[SKY] Found in classpath JAR: {}", entry);
                // Return the stream — caller must close, jar stays open
                return jar.getInputStream(je);
              }
              jar.close();
            } catch (Throwable ignored) {
            }
          }
        }
        if (verbose)
          MetalLogger.info("[SKY] Not found in {} classpath JARs", entries.length);
      }
    } catch (Throwable t) {
      if (verbose)
        MetalLogger.info("[SKY] Classpath JAR search failed: {}", t.getMessage());
    }

    // Strategy 7: Search Loom cache directories for MC client JAR
    // Prefer highest version (sort descending) so 1.21.11 is found before 1.20.1
    try {
      String userHome = System.getProperty("user.home");
      if (userHome != null) {
        Path loomCache = Path.of(userHome, ".gradle", "caches", "fabric-loom");
        if (Files.isDirectory(loomCache)) {
          // Collect all minecraft-client.jar paths and sort descending by path
          // so higher version numbers (1.21.x) come before lower (1.20.x)
          try (var stream = Files.walk(loomCache, 3)) {
            java.util.List<Path> mcJars = stream
                .filter(p -> p.getFileName().toString().equals("minecraft-client.jar"))
                .sorted(java.util.Comparator.<Path, String>comparing(p -> p.toString()).reversed())
                .collect(java.util.stream.Collectors.toList());
            if (verbose)
              MetalLogger.info("[SKY] Found {} MC JARs in loom cache", mcJars.size());
            for (Path jarPath : mcJars) {
              if (verbose)
                MetalLogger.info("[SKY] Trying loom cache JAR: {}", jarPath);
              try {
                JarFile jar = new JarFile(jarPath.toFile());
                JarEntry je = jar.getJarEntry(resourcePath);
                if (je != null) {
                  MetalLogger.info("[SKY] Found in loom cache: {}", jarPath);
                  return jar.getInputStream(je);
                }
                jar.close();
              } catch (Throwable ignored) {
              }
            }
          }
        }
        if (verbose)
          MetalLogger.info("[SKY] Not found in loom cache");
      }
    } catch (Throwable t) {
      if (verbose)
        MetalLogger.info("[SKY] Loom cache search failed: {}", t.getMessage());
    }

    if (this.celestialTextureRetries % 60 == 0) {
      MetalLogger.warn("[SKY] Could not find: {} (retry {})", resourcePath, this.celestialTextureRetries);
    }
    return null;
  }

  /**
   * Render sun and moon as textured quads in world space.
   * The quads are positioned at a fixed distance from the camera, rotated by
   * sunAngle/moonAngle around the east-west axis (X axis in MC conventions).
   */
  private void renderCelestialBodies(Camera camera) {
    if (!this.sunMoonTexturesUploaded) {
      this.uploadCelestialTextures();
    }

    // Render stars first (behind sun/moon)
    this.renderStars(camera);

    float distance = 100.0f; // Distance from camera (in view units)
    float quadSize = 30.0f; // Size of the quad

    // Sun quad: rotated by sunAngle around the east-west axis
    // MC convention: 0°=noon (top), 90°=sunset (horizon), 180°=midnight (below),
    // 270°=sunrise (horizon)
    // cos(angle) = height: cos(0)=1 (top at noon), cos(90)=0 (horizon at sunset)
    // sin(angle) = depth: orbital progression direction
    float sunCos = (float) Math.cos(this.sunAngle);
    float sunSin = (float) Math.sin(this.sunAngle);

    // Sun position in camera-relative space
    float sunY = sunCos * distance; // cos for height: noon=up, midnight=down
    float sunZ = -sunSin * distance; // sin for depth along orbit

    // Only render sun if above horizon (sunCos > -0.1 means above or near horizon)
    if (sunCos > -0.1f) {
      float alpha = Math.min(1.0f, (sunCos + 0.1f) * 5.0f) * this.rainGradient;
      if (alpha > 0.01f) {
        this.queueCelestialQuad(0, sunY, sunZ, quadSize, SUN_TEXTURE_ID, alpha);
      }
    }

    // Moon quad: opposite side of the sky
    float moonCos = (float) Math.cos(this.moonAngle);
    float moonSin = (float) Math.sin(this.moonAngle);
    float moonY = moonCos * distance;
    float moonZ = -moonSin * distance;

    // Moon — use the correct phase texture (MOON_TEXTURE_ID + moonPhaseIndex)
    float moonSize = 20.0f;
    if (moonCos > -0.1f) {
      float moonAlpha = Math.min(1.0f, (moonCos + 0.1f) * 5.0f) * this.rainGradient;
      if (moonAlpha > 0.01f) {
        int moonTexId = MOON_TEXTURE_ID + this.moonPhaseIndex;
        this.queueCelestialQuad(0, moonY, moonZ, moonSize, moonTexId, moonAlpha);
      }
    }
  }

  /**
   * Queue a celestial body quad as a generic draw.
   * The quad faces the camera (billboard) and is oriented tangent to the
   * celestial sphere.
   * 
   * @param posX X position in camera space
   * @param posY Y position in camera space (height)
   * @param posZ Z position in camera space (depth)
   */
  private void queueCelestialQuad(float posX, float posY, float posZ, float size, int textureId, float alpha) {
    float halfSize = size / 2.0f;

    // Compute billboard axes: the quad should face the camera (at origin)
    // Normal = direction from quad center toward camera = -position (normalized)
    float len = (float) Math.sqrt(posX * posX + posY * posY + posZ * posZ);
    if (len < 0.001f)
      return;
    float nx = -posX / len;
    float ny = -posY / len;
    float nz = -posZ / len;

    // "Right" vector = cross(worldUp, normal), where worldUp = (0, 1, 0)
    // If normal is nearly parallel to worldUp, use (1, 0, 0) as fallback up
    float upX = 0, upY = 1, upZ = 0;
    if (Math.abs(ny) > 0.95f) {
      upX = 1;
      upY = 0;
      upZ = 0;
    }

    float rx = upY * nz - upZ * ny;
    float ry = upZ * nx - upX * nz;
    float rz = upX * ny - upY * nx;
    float rLen = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
    if (rLen < 0.001f)
      return;
    rx /= rLen;
    ry /= rLen;
    rz /= rLen;

    // "Up" vector = cross(normal, right) — gives the tangent-up on the celestial
    // sphere
    float ux = ny * rz - nz * ry;
    float uy = nz * rx - nx * rz;
    float uz = nx * ry - ny * rx;
    float uLen = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
    if (uLen < 0.001f)
      return;
    ux /= uLen;
    uy /= uLen;
    uz /= uLen;

    // 4 corners of the quad, centered at (posX, posY, posZ)
    // corner = center + right * dx + up * dy
    float c0x = posX - rx * halfSize + ux * halfSize; // top-left
    float c0y = posY - ry * halfSize + uy * halfSize;
    float c0z = posZ - rz * halfSize + uz * halfSize;
    float c1x = posX + rx * halfSize + ux * halfSize; // top-right
    float c1y = posY + ry * halfSize + uy * halfSize;
    float c1z = posZ + rz * halfSize + uz * halfSize;
    float c2x = posX + rx * halfSize - ux * halfSize; // bottom-right
    float c2y = posY + ry * halfSize - uy * halfSize;
    float c2z = posZ + rz * halfSize - uz * halfSize;
    float c3x = posX - rx * halfSize - ux * halfSize; // bottom-left
    float c3y = posY - ry * halfSize - uy * halfSize;
    float c3z = posZ - rz * halfSize - uz * halfSize;

    // 6 vertices = 2 triangles, 32 bytes each (float3 pos + ubyte4 color + float2
    // uv + float2 light)
    byte[] vertexData = new byte[6 * 32];
    int alphaInt = (int) (alpha * 255);
    int offset = 0;

    // Triangle 1: top-left, top-right, bottom-right
    offset = writeCelestialVertex(vertexData, offset, c0x, c0y, c0z, 0.0f, 0.0f, alphaInt);
    offset = writeCelestialVertex(vertexData, offset, c1x, c1y, c1z, 1.0f, 0.0f, alphaInt);
    offset = writeCelestialVertex(vertexData, offset, c2x, c2y, c2z, 1.0f, 1.0f, alphaInt);
    // Triangle 2: top-left, bottom-right, bottom-left
    offset = writeCelestialVertex(vertexData, offset, c0x, c0y, c0z, 0.0f, 0.0f, alphaInt);
    offset = writeCelestialVertex(vertexData, offset, c2x, c2y, c2z, 1.0f, 1.0f, alphaInt);
    offset = writeCelestialVertex(vertexData, offset, c3x, c3y, c3z, 0.0f, 1.0f, alphaInt);

    NativeBridge.nQueueGenericDraw(this.handle, vertexData, 6, textureId, 0, this.frameProjection);
  }

  private int writeCelestialVertex(byte[] buf, int offset, float x, float y, float z,
      float u, float v, int alpha) {
    putFloat(buf, offset, x);
    putFloat(buf, offset + 4, y);
    putFloat(buf, offset + 8, z);
    buf[offset + 12] = (byte) 255;
    buf[offset + 13] = (byte) 255;
    buf[offset + 14] = (byte) 255;
    buf[offset + 15] = (byte) alpha;
    putFloat(buf, offset + 16, u);
    putFloat(buf, offset + 20, v);
    // Light: celestial bodies don't need per-vertex lighting
    putFloat(buf, offset + 24, 0.0f);
    putFloat(buf, offset + 28, 0.0f);
    return offset + 32;
  }

  private static void putFloat(byte[] buf, int offset, float value) {
    int bits = Float.floatToIntBits(value);
    buf[offset] = (byte) (bits & 0xFF);
    buf[offset + 1] = (byte) ((bits >> 8) & 0xFF);
    buf[offset + 2] = (byte) ((bits >> 16) & 0xFF);
    buf[offset + 3] = (byte) ((bits >> 24) & 0xFF);
  }

  /**
   * Generate a random star field on the unit sphere.
   * Uses a fixed seed for consistency across sessions.
   */
  private void generateStarField() {
    Random rand = new Random(10842L);
    this.starX = new float[STAR_COUNT];
    this.starY = new float[STAR_COUNT];
    this.starZ = new float[STAR_COUNT];

    for (int i = 0; i < STAR_COUNT; i++) {
      // Uniform random point on unit sphere via spherical coordinates
      double theta = rand.nextDouble() * 2.0 * Math.PI;
      double phi = Math.acos(2.0 * rand.nextDouble() - 1.0);
      this.starX[i] = (float) (Math.sin(phi) * Math.cos(theta));
      this.starY[i] = (float) (Math.sin(phi) * Math.sin(theta));
      this.starZ[i] = (float) (Math.cos(phi));
    }
    this.starsGenerated = true;
    MetalLogger.info("[SKY] Generated {} star positions", STAR_COUNT);
  }

  /**
   * Render stars as small textured quads on the celestial sphere.
   * Stars rotate with the sky (same rotation as sun) and are only visible
   * when starBrightness > 0 (nighttime).
   */
  private void renderStars(Camera camera) {
    if (this.starBrightness <= 0.01f)
      return;
    if (!this.starsGenerated)
      generateStarField();

    float distance = 100.0f;
    float starSize = 0.7f;
    float alpha = this.starBrightness * this.rainGradient;
    if (alpha <= 0.01f)
      return;
    int alphaInt = Math.min(255, (int) (alpha * 255));

    float cosA = (float) Math.cos(this.sunAngle);
    float sinA = (float) Math.sin(this.sunAngle);

    // Count visible stars (above horizon after rotation)
    int visibleCount = 0;
    for (int i = 0; i < STAR_COUNT; i++) {
      float ry = this.starY[i] * cosA - this.starZ[i] * sinA;
      if (ry > -0.05f)
        visibleCount++;
    }
    if (visibleCount == 0)
      return;

    byte[] vertexData = new byte[visibleCount * 6 * 32];
    int offset = 0;
    float halfSize = starSize / 2.0f;

    for (int i = 0; i < STAR_COUNT; i++) {
      float sx = this.starX[i];
      float sy = this.starY[i];
      float sz = this.starZ[i];

      // Rotate by sunAngle around the X axis (east-west)
      float ry = sy * cosA - sz * sinA;
      float rz = sy * sinA + sz * cosA;

      // Skip if below horizon
      if (ry <= -0.05f)
        continue;

      // Fade near horizon
      float starAlpha = alphaInt;
      if (ry < 0.1f) {
        starAlpha *= (ry + 0.05f) / 0.15f;
      }
      int sAlpha = Math.min(255, Math.max(0, (int) starAlpha));
      if (sAlpha <= 0)
        continue;

      float px = sx * distance;
      float py = ry * distance;
      float pz = rz * distance;

      // Compute billboard tangent vectors (quad perpendicular to radial direction)
      float nx = sx, ny = ry, nz = rz; // unit normal (radial)

      // Choose up vector not parallel to normal
      float ux = 0, uy = 1, uz = 0;
      if (Math.abs(ny) > 0.9f) {
        ux = 1;
        uy = 0;
        uz = 0;
      }

      // right = cross(up, normal)
      float rx = uy * nz - uz * ny;
      float rry = uz * nx - ux * nz;
      float rrz = ux * ny - uy * nx;
      float rLen = (float) Math.sqrt(rx * rx + rry * rry + rrz * rrz);
      if (rLen < 0.001f)
        continue;
      rx /= rLen;
      rry /= rLen;
      rrz /= rLen;

      // tangentUp = cross(normal, right)
      float tux = ny * rrz - nz * rry;
      float tuy = nz * rx - nx * rrz;
      float tuz = nx * rry - ny * rx;
      float tuLen = (float) Math.sqrt(tux * tux + tuy * tuy + tuz * tuz);
      if (tuLen < 0.001f)
        continue;
      tux /= tuLen;
      tuy /= tuLen;
      tuz /= tuLen;

      // 4 corners of the star quad
      float c0x = px - rx * halfSize + tux * halfSize;
      float c0y = py - rry * halfSize + tuy * halfSize;
      float c0z = pz - rrz * halfSize + tuz * halfSize;
      float c1x = px + rx * halfSize + tux * halfSize;
      float c1y = py + rry * halfSize + tuy * halfSize;
      float c1z = pz + rrz * halfSize + tuz * halfSize;
      float c2x = px + rx * halfSize - tux * halfSize;
      float c2y = py + rry * halfSize - tuy * halfSize;
      float c2z = pz + rrz * halfSize - tuz * halfSize;
      float c3x = px - rx * halfSize - tux * halfSize;
      float c3y = py - rry * halfSize - tuy * halfSize;
      float c3z = pz - rrz * halfSize - tuz * halfSize;

      // Triangle 1: 0-1-2
      offset = writeStarVertex(vertexData, offset, c0x, c0y, c0z, 0.0f, 0.0f, sAlpha);
      offset = writeStarVertex(vertexData, offset, c1x, c1y, c1z, 1.0f, 0.0f, sAlpha);
      offset = writeStarVertex(vertexData, offset, c2x, c2y, c2z, 1.0f, 1.0f, sAlpha);
      // Triangle 2: 0-2-3
      offset = writeStarVertex(vertexData, offset, c0x, c0y, c0z, 0.0f, 0.0f, sAlpha);
      offset = writeStarVertex(vertexData, offset, c2x, c2y, c2z, 1.0f, 1.0f, sAlpha);
      offset = writeStarVertex(vertexData, offset, c3x, c3y, c3z, 0.0f, 1.0f, sAlpha);
    }

    int vertexCount = offset / 32;
    if (vertexCount > 0) {
      // Trim to actual size if some stars were skipped
      if (offset < vertexData.length) {
        byte[] trimmed = new byte[offset];
        System.arraycopy(vertexData, 0, trimmed, 0, offset);
        vertexData = trimmed;
      }
      NativeBridge.nQueueGenericDraw(this.handle, vertexData, vertexCount, STAR_TEXTURE_ID, 0, this.frameProjection);
    }
  }

  private int writeStarVertex(byte[] buf, int offset, float x, float y, float z,
      float u, float v, int alpha) {
    putFloat(buf, offset, x);
    putFloat(buf, offset + 4, y);
    putFloat(buf, offset + 8, z);
    buf[offset + 12] = (byte) 255; // R
    buf[offset + 13] = (byte) 255; // G
    buf[offset + 14] = (byte) 255; // B
    buf[offset + 15] = (byte) alpha;
    putFloat(buf, offset + 16, u);
    putFloat(buf, offset + 20, v);
    // Light: stars don't need per-vertex lighting
    putFloat(buf, offset + 24, 0.0f);
    putFloat(buf, offset + 28, 0.0f);
    return offset + 32;
  }

  // =========================================================================
  // PERFORMANCE TRACKING: For F3 debug overlay
  // =========================================================================

  /** Get the last frame render time in milliseconds */
  public float getLastFrameTimeMs() {
    return this.lastFrameTimeNanos / 1_000_000.0f;
  }

  /** Get average frame time over the last N frames in milliseconds */
  public float getAvgFrameTimeMs() {
    int count = Math.min(this.totalFramesDone, this.frameTimes.length);
    if (count == 0)
      return 0.0f;
    long sum = 0;
    for (int i = 0; i < count; i++) {
      sum += this.frameTimes[i];
    }
    return (sum / count) / 1_000_000.0f;
  }

  /** Get Metal FPS (based on actual frame timing) */
  public float getMetalFPS() {
    float avgMs = getAvgFrameTimeMs();
    return avgMs > 0.0f ? 1000.0f / avgMs : 0.0f;
  }

  /** Get total frames rendered */
  public int getTotalFrames() {
    return this.totalFramesDone;
  }

  /** Get arena usage info */
  public String getArenaInfo() {
    return (this.persistentArena.cursor() / 1024 / 1024) + "MB/"
        + (this.persistentArena.capacity() / 1024 / 1024) + "MB";
  }

  /** Get chunk mesh count */
  public int getChunkMeshCount() {
    MeshShaderBackend backend = this.meshBackend();
    return backend != null ? backend.chunkMeshCount() : 0;
  }

  public void destroy() {
    this.cachedMeshBackend = null;
    if (this.pipelineCache != null) {
      this.pipelineCache.reset();
      this.pipelineCache = null;
    }
    if (this.handle != 0L) {
      NativeBridge.nDestroy(this.handle);
      this.handle = 0L;
    }
    this.ready = false;
  }

  // FEATURE_002: Sodiumless mode support
  public void setsodiumless(boolean enabled) {
    this.sodiumlessMode = enabled;
    MetalLogger.info("Sodiumless mode: {}", enabled ? "enabled" : "disabled");
  }

  // FEATURE_004: Atlas management
  public void forceAtlasReupload() {
    this.atlasNeedsReupload = true;
    MetalLogger.debug("Atlas reupload forced");
  }

  public boolean uploadAtlas() {
    if (!this.ready || this.handle == 0L) {
      return false;
    }

    // DEFENSIVE: If we have never uploaded an atlas, proactively check
    // whether the GL atlas texture exists yet.
    if (!this.atlasNeedsReupload) {
      if (this.diagCounter % 120 == 0) {
        net.minecraft.util.Identifier blocksAtlasId = net.minecraft.util.Identifier.of("minecraft",
            "textures/atlas/blocks.png");
        boolean hasAtlasOnGpu = NativeBridge.nVerifyAtlasTexture(this.handle) != -2;
        if (!hasAtlasOnGpu) {
          // Check if the GL texture exists now (it may have been uploaded since create())
          try {
            net.minecraft.client.texture.AbstractTexture tex = MinecraftClient.getInstance().getTextureManager()
                .getTexture(blocksAtlasId);
            if (tex instanceof net.minecraft.client.texture.SpriteAtlasTexture) {
              MetalLogger.info("[FEATURE_009] GL atlas texture found but NOT on Metal GPU — forcing GL readback");
              this.atlasNeedsReupload = true;
            }
          } catch (Exception e) {
            // Not ready yet
          }
        }
      }
    }

    if (this.atlasNeedsReupload) {
      net.minecraft.util.Identifier blocksAtlasId = net.minecraft.util.Identifier.of("minecraft",
          "textures/atlas/blocks.png");

      // STRATEGY: Read back the ACTUAL GL texture that Minecraft uploaded.
      // This guarantees pixel-perfect match with what Minecraft's vertex UVs
      // reference.
      // At render time, the GL texture is guaranteed to be uploaded (unlike at
      // create() time).
      boolean uploaded = false;
      try {
        net.minecraft.client.texture.AbstractTexture tex = MinecraftClient.getInstance().getTextureManager()
            .getTexture(blocksAtlasId);
        if (tex instanceof net.minecraft.client.texture.SpriteAtlasTexture atlasTexture) {
          com.metalrender.sodium.mixins.accessor.SpriteAtlasTextureAccessor accessor = (com.metalrender.sodium.mixins.accessor.SpriteAtlasTextureAccessor) atlasTexture;
          int width = accessor.metalrender$getAtlasWidth();
          int height = accessor.metalrender$getAtlasHeight();

          if (width > 0 && height > 0) {
            MetalLogger.info("[FEATURE_009] GL readback: atlas {}x{}", width, height);

            // MC 1.21.11 uses GpuTexture (abstract) instead of a raw glId int.
            // Use reflection to find the GL texture name inside the concrete GpuTexture.
            com.mojang.blaze3d.textures.GpuTexture gpuTex = tex.getGlTexture();
            if (gpuTex == null) {
              MetalLogger.warn("[FEATURE_009] getGlTexture() returned null");
            } else {
              MetalLogger.info("[FEATURE_009] GpuTexture class: {} w={} h={}",
                  gpuTex.getClass().getName(), gpuTex.getWidth(0), gpuTex.getHeight(0));

              // Find the GL texture name (int field > 0) via reflection on the concrete class
              int glId = -1;
              try {
                // Walk up the class hierarchy to find the int field holding the GL texture name
                Class<?> clazz = gpuTex.getClass();
                while (clazz != null && clazz != Object.class) {
                  for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    if (f.getType() == int.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                      f.setAccessible(true);
                      int val = f.getInt(gpuTex);
                      MetalLogger.info("[FEATURE_009] GpuTexture field '{}' = {}", f.getName(), val);
                      if (val > 0 && glId < 0) {
                        glId = val;
                      }
                    }
                  }
                  clazz = clazz.getSuperclass();
                }
              } catch (Exception reflEx) {
                MetalLogger.warn("[FEATURE_009] Reflection on GpuTexture failed: {}", reflEx.getMessage());
              }

              if (glId > 0) {
                MetalLogger.info("[FEATURE_009] Found GL texture ID: {}", glId);

                // Save current GL state
                int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);

                // Bind the GL texture
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glId);

                // Verify the texture is actually bound (check GL dimensions)
                int texW = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
                int texH = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
                MetalLogger.info("[FEATURE_009] GL texture dimensions: {}x{} (expected {}x{})",
                    texW, texH, width, height);

                if (texW > 0 && texH > 0) {
                  int readW = texW;
                  int readH = texH;
                  int bufSize = readW * readH * 4;
                  java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(bufSize);

                  org.lwjgl.opengl.GL11.glGetTexImage(
                      org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                      0,
                      org.lwjgl.opengl.GL12.GL_BGRA,
                      org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                      buf);

                  int glError = org.lwjgl.opengl.GL11.glGetError();
                  if (glError != 0) {
                    MetalLogger.error("[FEATURE_009] GL error after readback: 0x{}", Integer.toHexString(glError));
                  }

                  // Diagnostic: count non-zero pixels
                  buf.rewind();
                  int nonZero = 0, opaque = 0, totalSampled = 0;
                  for (int y = 0; y < readH; y += 16) {
                    for (int x = 0; x < readW; x += 16) {
                      totalSampled++;
                      int idx = (y * readW + x) * 4;
                      int bv = buf.get(idx) & 0xFF, gv = buf.get(idx + 1) & 0xFF;
                      int rv = buf.get(idx + 2) & 0xFF, av = buf.get(idx + 3) & 0xFF;
                      if (bv != 0 || gv != 0 || rv != 0 || av != 0) {
                        nonZero++;
                        if (av > 128)
                          opaque++;
                      }
                    }
                  }
                  MetalLogger.info("[FEATURE_009] GL readback stats: sampled={} nonZero={} opaque={}",
                      totalSampled, nonZero, opaque);

                  // Also save debug PNG for visual comparison
                  try {
                    java.io.File debugDir = new java.io.File("metalrender_debug");
                    debugDir.mkdirs();
                    try (
                        net.minecraft.client.texture.NativeImage debugImg = new net.minecraft.client.texture.NativeImage(
                            net.minecraft.client.texture.NativeImage.Format.RGBA, readW, readH, true)) {
                      buf.rewind();
                      for (int y = 0; y < readH; y++) {
                        for (int x = 0; x < readW; x++) {
                          int idx = (y * readW + x) * 4;
                          int b0 = buf.get(idx) & 0xFF;
                          int g0 = buf.get(idx + 1) & 0xFF;
                          int r0 = buf.get(idx + 2) & 0xFF;
                          int a0 = buf.get(idx + 3) & 0xFF;
                          int argb = (a0 << 24) | (r0 << 16) | (g0 << 8) | b0;
                          debugImg.setColorArgb(x, y, argb);
                        }
                      }
                      debugImg.writeTo(new java.io.File(debugDir, "atlas_gl_readback.png"));
                      MetalLogger.info("[FEATURE_009] GL readback PNG saved to metalrender_debug/");
                    }
                  } catch (Exception pngEx) {
                    MetalLogger.warn("[FEATURE_009] Debug PNG failed: {}", pngEx.getMessage());
                  }

                  // Upload to Metal
                  buf.rewind();
                  boolean ok = NativeBridge.nUploadAtlas(this.handle, buf, readW, readH);
                  org.lwjgl.system.MemoryUtil.memFree(buf);

                  if (ok) {
                    MetalLogger.info("[FEATURE_009] Atlas uploaded to Metal via GL readback ({}x{})", readW, readH);
                    int verifyResult = NativeBridge.nVerifyAtlasTexture(this.handle);
                    if (verifyResult >= 0) {
                      int bv = verifyResult & 0xFF;
                      int gv = (verifyResult >> 8) & 0xFF;
                      int rv = (verifyResult >> 16) & 0xFF;
                      int av = (verifyResult >> 24) & 0xFF;
                      MetalLogger.info("[FEATURE_009] Verify OK: pixel(0,0) BGRA=({},{},{},{}) raw=0x{}",
                          bv, gv, rv, av, Integer.toHexString(verifyResult));
                    } else {
                      MetalLogger.error("[FEATURE_009] Verify failed: {}", verifyResult);
                    }
                    uploaded = true;
                  } else {
                    MetalLogger.error("[FEATURE_009] nUploadAtlas failed for GL readback {}x{}", readW, readH);
                  }
                } else {
                  MetalLogger.warn("[FEATURE_009] GL texture has zero dimensions");
                }

                // Restore previous texture binding
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex);
              } else {
                MetalLogger.warn("[FEATURE_009] Could not find GL texture ID via reflection");
              }
            }
          }
        }
      } catch (Exception e) {
        MetalLogger.error("[FEATURE_009] GL readback failed: {}", e.getMessage());
        e.printStackTrace();
      }

      // Fallback: try manual blit from repository if GL readback failed
      if (!uploaded) {
        MetalLogger.warn("[FEATURE_009] GL readback path failed — trying repository fallback");
        java.util.Optional<com.metalrender.render.atlas.CapturedAtlas> opt = com.metalrender.render.atlas.CapturedAtlasRepository
            .get(blocksAtlasId);
        if (opt.isPresent()) {
          com.metalrender.render.atlas.CapturedAtlas atlas = opt.get();
          java.nio.ByteBuffer directBuf = atlas.toDirectBuffer();
          boolean ok = NativeBridge.nUploadAtlas(this.handle, directBuf, atlas.width(), atlas.height());
          if (ok) {
            MetalLogger.info("[FEATURE_009] Atlas uploaded via repository fallback ({}x{})",
                atlas.width(), atlas.height());
            uploaded = true;
          }
        } else {
          MetalLogger.warn("[FEATURE_009] No atlas data available (GL readback failed, no repository data)");
        }
      }

      this.atlasNeedsReupload = false;
      return uploaded;
    }
    return false;
  }

  // FEATURE_005: Batch chunk build output upload
  public void uploadBuildResults(java.util.List<ChunkBuildOutput> results) {
    if (!this.ready || this.handle == 0L || results == null || results.isEmpty()) {
      return;
    }
    MeshShaderBackend backend = this.meshBackend();
    if (backend != null) {
      for (ChunkBuildOutput output : results) {
        backend.uploadBuildOutput(this.handle, this.persistentArena, output);
      }
      MetalLogger.debug("Uploaded {} chunk build results", results.size());
    }
  }
}
