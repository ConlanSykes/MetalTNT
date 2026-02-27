package com.metalrender.nativebridge;

import java.nio.Buffer;

public final class NativeBridge {
  private static volatile boolean libLoaded;

  private NativeBridge() {
  }

  public static boolean isLibLoaded() {
    return libLoaded;
  }

  public static native boolean nIsAvailable();

  public static native long nInit(int var0, int var1, float var2);

  public static native void nResize(long var0, int var2, int var3, float var4);

  public static native void nBeginFrame(long var0, float[] var2, float[] var3,
      float var4, float var5);

  public static native void nDrawTerrain(long var0, int var2);

  public static native void nDrawOverlay(long var0, int var2);

  // Generic draw system: entity/UI/particle rendering in Metal
  public static native void nClearGenericDraws(long var0);
  public static native int nUploadGenericTexture(long var0, int texId, int width, int height, byte[] pixelData);
  public static native void nQueueGenericDraw(long var0, byte[] vertexData, int vertexCount, int textureId, int blendMode, float[] modelViewMatrix);
  public static native void nSetProjectionMatrix(long var0, float[] matrix);

  // Sky brightness for day/night cycle (0.0 = night, 1.0 = full day)
  public static native void nSetSkyBrightness(long var0, float brightness);

  // Offscreen render-to-texture: Metal renders item models directly instead of GL
  public static native void nClearOffscreen(long handle); // clear persistent RT (scroll/tab change)
  public static native int nBeginOffscreenPass(long handle, int width, int height);
  public static native int nDrawOffscreen(long handle, byte[] vertexData, int vertexCount,
      int textureId, int blendMode, float[] projMatrix);
  public static native int nEndOffscreenPass(long handle, int snapshotTexId);

  // Item cache: GPU-side blit from atlas snapshot to per-item cache texture
  public static native int nBlitToItemCache(long handle, int srcTexId, int dstTexId,
      int srcX, int srcY, int srcW, int srcH);

  // Voxel Ray Tracing: shadow rays & GI via 3D voxel grid
  public static native void nUploadVoxelColumn(long handle, int chunkWorldX, int chunkWorldZ,
      int startY, int endY, byte[] voxelData);
  public static native void nSetVoxelOrigin(long handle, float originX, float originY, float originZ);
  public static native void nSetSunDirection(long handle, float dirX, float dirY, float dirZ);
  public static native void nSetInvViewProj(long handle, float[] matrix);
  public static native void nSetViewProj(long handle, float[] matrix);
  public static native void nSetCameraPosition(long handle, float x, float y, float z);
  public static native void nSetShadowDistance(long handle, float distanceBlocks);
  public static native void nSetPointLights(long handle, float[] lightData, int count);
  public static native void nComputeShadowAndGI(long handle);
  public static native void nApplyShadowComposite(long handle);

  public static native void nOnWorldLoaded(long var0);

  public static native void nOnWorldUnloaded(long var0);

  public static native void nDestroy(long var0);

  public static native String nGetDeviceName(long var0);

  public static native boolean nSupportsIndirect();

  public static native boolean nSupportsMeshShaders();

  public static native boolean nSupportsHiZ(long var0);

  public static native long nEnsureHiZ(long var0, int var2, int var3);

  public static native void nDestroyHiZ(long var0, long var2);

  public static native void nOcclusionBegin(long var0, long var2, float[] var4);

  public static native void nOcclusionEvaluate(long var0, long var2, Buffer var4, int var5, Buffer var6);

  public static native java.nio.ByteBuffer nMapPersistentBuffer(long var0);

  public static native int nPersistentCapacity(long var0);

  public static native int nPersistentAlign(long var0);

  public static native void nPersistentAdvance(long var0, int var2);

  public static native void nClearIndirectCommands(long var0);

  public static native void nQueueIndirectDraw(long var0, int var2, long var3,
      long var5, int var7, int var8,
      int var9, int var10, int var11,
      float var12);

  public static native int nExecuteIndirect(long var0, int var2);

  public static native void nPrewarmPipelines(long var0);

  public static native int[] nGetPipelineCacheStats(long var0);

  public static native void nResetPipelineCache(long var0);

  public static native void nSetTemporalJitter(long var0, float var2,
      float var3, float var4);

  public static native boolean nSupportsMetalFX();

  public static native void nSetMetalFXEnabled(long var0, boolean var2);

  public static native void nConfigureMetalFX(long var0, int var2, int var3,
      float var4);

  // FEATURE_004: CAMetalLayer Integration
  // Initialize CAMetalLayer for direct window presentation
  // windowPtr: Platform-specific window pointer (NSWindow* on macOS)
  // Returns: true if initialization successful
  public static native boolean nInitializeCAMetalLayer(long handle, long windowPtr);

  // Handle window resize events - updates drawable size
  public static native void nOnWindowResized(long handle, int width, int height);

  // FEATURE_004: Surface attachment for direct rendering
  public static native boolean nAttachSurface(long handle, long cocoaWindow);

  public static native void nDetachSurface(long handle);

  // FEATURE_005: GPU synchronization primitives
  public static native long nCreateFence(long handle);

  public static native boolean nPollFence(long handle, long fence);

  public static native int nWaitFence(long handle, long fence, long timeoutNanos);

  public static native void nDestroyFence(long handle, long fence);

  // FEATURE_005: Memory management
  public static native long nGetDeviceMemory(long handle);

  public static native long nGetMemoryUsage(long handle);

  // FEATURE_009: Atlas Texture Upload
  public static native boolean nUploadAtlas(long handle, java.nio.ByteBuffer pixelData, int width, int height);

  // Native→Java logging bridge
  public static native void nLogToJava(long handle, int level, String message);

  // Atlas verification: returns BGRA pixel(0,0) value, or -1 (null ctx), -2 (null
  // atlas)
  public static native int nVerifyAtlasTexture(long handle);

  // Native version stamp — verify correct dylib is loaded
  public static native String nGetNativeVersion();

  static {
    try {
      // Try loading from classpath first (extract temp file), then fall back to
      // system path
      String libName = System.mapLibraryName("metalrender"); // "libmetalrender.dylib"
      java.io.InputStream in = NativeBridge.class.getClassLoader().getResourceAsStream("native/" + libName);
      if (in == null) {
        // Also try without native/ prefix
        in = NativeBridge.class.getClassLoader().getResourceAsStream(libName);
      }
      if (in != null) {
        java.io.File tempFile = java.io.File.createTempFile("metalrender", ".dylib");
        tempFile.deleteOnExit();
        try (java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
          byte[] buf = new byte[65536];
          int len;
          while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
          }
        }
        in.close();
        System.load(tempFile.getAbsolutePath());
        System.err.println("[MetalRender] Loaded native library from classpath: " + tempFile.getAbsolutePath());
      } else {
        System.loadLibrary("metalrender");
        System.err.println("[MetalRender] Loaded native library via java.library.path");
      }
      libLoaded = true;
      System.err.println("[MetalRender] java.library.path=" +
          System.getProperty("java.library.path"));
    } catch (Throwable var1) {
      libLoaded = false;
      System.err.println("[MetalRender] Failed to load native library: " +
          var1);
      var1.printStackTrace();
      System.err.println("[MetalRender] java.library.path=" +
          System.getProperty("java.library.path"));
    }
  }
}
