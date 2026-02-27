package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;

public final class RenderOptimizer {
  private static final RenderOptimizer INSTANCE = new RenderOptimizer();
  private int frustumCulledThisFrame = 0;
  private int occlusionCulledThisFrame = 0;
  private int totalChunksThisFrame = 0;
  private long currentFrame = 0L;
  private int viewportWidth = 1;
  private int viewportHeight = 1;

  private RenderOptimizer() {
  }

  public static RenderOptimizer getInstance() {
    return INSTANCE;
  }

  public void updateFrame(long nativeHandle, Camera camera,
      Matrix4f viewProjectionMatrix, int width,
      int height) {
    ++this.currentFrame;
    this.viewportWidth = Math.max(1, width);
    this.viewportHeight = Math.max(1, height);
    this.frustumCulledThisFrame = 0;
    this.occlusionCulledThisFrame = 0;
    this.totalChunksThisFrame = 0;
  }

  public boolean shouldRenderChunk(BlockPos chunkPos, Camera camera) {
    ++this.totalChunksThisFrame;
    return true;
  }

  public void finalizeFrame() {
  }

  public RenderOptimizer.PerformanceStats getFrameStats() {
    return new RenderOptimizer.PerformanceStats(
        this.totalChunksThisFrame, this.frustumCulledThisFrame,
        this.occlusionCulledThisFrame, 0, this.currentFrame);
  }

  public void invalidateCache() {
    this.frustumCulledThisFrame = 0;
    this.occlusionCulledThisFrame = 0;
    this.totalChunksThisFrame = 0;
  }

  public static class PerformanceStats {
    public final int totalChunks;
    public final int frustumCulled;
    public final int occlusionCulled;
    public final int cacheSize;
    public final long currentFrame;
    public final double cullPercentage;

    PerformanceStats(int total, int frustumCulled, int occlusionCulled,
        int cacheSize, long frame) {
      this.totalChunks = total;
      this.frustumCulled = frustumCulled;
      this.occlusionCulled = occlusionCulled;
      this.cacheSize = cacheSize;
      this.currentFrame = frame;
      int culled = frustumCulled + occlusionCulled;
      this.cullPercentage = total > 0 ? (double) culled / (double) total * 100.0D : 0.0D;
    }
  }
}
