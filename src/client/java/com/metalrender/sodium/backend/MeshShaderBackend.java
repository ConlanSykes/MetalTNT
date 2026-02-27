package com.metalrender.sodium.backend;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.performance.RenderingMetrics;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.PersistentBufferArena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class MeshShaderBackend {
  private static final int MAX_LOD_LEVELS = 3;
  private static final int MAX_CHUNK_CACHE_SIZE = 16384;
  private static final double MAX_CHUNK_DISTANCE = 4096.0;

  private final boolean meshSupported;
  private final Map<Long, ChunkMesh> chunkMeshes = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, ReentrantLock> chunkLocks = new ConcurrentHashMap<>();
  private volatile PersistentBufferArena arena; // stored for freeing old allocations
  private long lastCleanupFrame = 0;
  private volatile int exhaustionLogCount = 0;

  public MeshShaderBackend() {
    this.meshSupported = NativeBridge.nSupportsMeshShaders();
    MetalLogger.info("[MetalRender] Mesh shader support: {}",
        this.meshSupported ? "available"
            : "unavailable (compute fallback)");
  }

  public boolean isMeshEnabled() {
    boolean enabled = this.meshSupported && MetalRenderConfig.meshShadersEnabled();
    if (Math.random() < 0.001) {
      MetalLogger.info(
          "[MeshBackend] isMeshEnabled() = %s (supported=%s, config=%s)",
          enabled, this.meshSupported, MetalRenderConfig.meshShadersEnabled());
    }
    return enabled;
  }

  public void destroy() {
    this.chunkMeshes.clear();
  }

  public int chunkMeshCount() {
    return this.chunkMeshes.size();
  }

  // Sodium COMPACT vertex stride: 5 x uint32 = 20 bytes
  private static final int SODIUM_COMPACT_STRIDE = 20;

  private ReentrantLock getLockForKey(long key) {
    return this.chunkLocks.computeIfAbsent(key, k -> new ReentrantLock());
  }

  public void uploadBuildOutput(long nativeHandle, PersistentBufferArena arena,
      ChunkBuildOutput output) {
    if (arena == null || output == null) {
      return;
    }

    RenderSection section = output.render;
    if (section == null) {
      return;
    }

    BlockPos origin = new BlockPos(section.getOriginX(), section.getOriginY(),
        section.getOriginZ());
    long key = origin.asLong();
    this.arena = arena; // remember arena for future frees

    // Per-key lock prevents two threads from uploading the same chunk
    // simultaneously, which would leak the first thread's allocation.
    ReentrantLock lock = getLockForKey(key);
    lock.lock();
    try {
      if (output.meshes == null || output.meshes.isEmpty()) {
        freeChunkAllocations(key, arena);
        this.chunkMeshes.remove(key);
        return;
      }

      // Free old allocation before re-uploading
      freeChunkAllocations(key, arena);

      ChunkMesh mesh = new ChunkMesh(origin, MAX_LOD_LEVELS);

      for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> entry : output.meshes.entrySet()) {
        BuiltSectionMeshParts parts = entry.getValue();
        if (parts == null || parts.getVertexData() == null ||
            parts.getVertexData().getLength() == 0) {
          continue;
        }

        ByteBuffer rawData = parts.getVertexData().getDirectBuffer().duplicate().order(
            ByteOrder.LITTLE_ENDIAN);
        rawData.clear();
        int rawBytes = parts.getVertexData().getLength();
        rawData.limit(rawBytes);

        int vertexCount = rawBytes / SODIUM_COMPACT_STRIDE;
        if (vertexCount <= 0) {
          continue;
        }

        ByteBuffer persistent = arena.buffer();
        if (persistent == null) {
          continue;
        }

        int offset = arena.allocate(rawBytes);
        if (offset < 0) {
          // Buffer genuinely full — skip this chunk upload.
          // Do NOT evict existing distant chunks, because Sodium won't
          // re-send them and they'd be lost permanently.
          if (this.exhaustionLogCount++ < 10) {
            MetalLogger.warn("[MetalRender] Persistent buffer full, skipping chunk {} "
                + "(cursor={}MB/{}MB, chunks={}, requested={}B)",
                origin,
                arena.cursor() / 1024 / 1024,
                arena.capacity() / 1024 / 1024,
                this.chunkMeshes.size(), rawBytes);
          }
          continue;
        }

        ByteBuffer target = persistent.duplicate();
        target.position(offset);
        target.limit(offset + rawBytes);
        target.put(rawData);

        mesh.addDraw(0, new DrawCommand(offset, vertexCount, rawBytes));
      }

      if (mesh.hasDraws()) {
        this.chunkMeshes.put(key, mesh);
        int sz = this.chunkMeshes.size();
        if (sz <= 20 || sz % 200 == 0) {
          System.err.println("[MetalRender] [UPLOAD] Chunk at " + origin
              + " stored; total=" + sz
              + " arena=" + (arena.cursor() / 1024 / 1024) + "MB/"
              + (arena.capacity() / 1024 / 1024) + "MB");
        }
      } else {
        this.chunkMeshes.remove(key);
      }
    } finally {
      lock.unlock();
    }
  }

  public void removeChunkMesh(BlockPos chunkPos) {
    if (chunkPos != null) {
      long key = chunkPos.asLong();
      ReentrantLock lock = getLockForKey(key);
      lock.lock();
      try {
        freeChunkAllocations(key, this.arena);
        this.chunkMeshes.remove(key);
      } finally {
        lock.unlock();
      }
      // Clean up the lock entry if no longer needed
      this.chunkLocks.remove(key);
    }
  }

  private void freeChunkAllocations(long key, PersistentBufferArena arena) {
    ChunkMesh old = this.chunkMeshes.get(key);
    if (old != null && arena != null) {
      for (int lvl = 0; lvl < old.levelCount(); lvl++) {
        for (DrawCommand draw : old.drawsForLevel(lvl)) {
          arena.free(draw.vertexOffset, draw.byteLength);
        }
      }
    }
  }

  private void cleanupDistantChunks(Camera camera) {
    if (this.chunkMeshes.size() < MAX_CHUNK_CACHE_SIZE) {
      return;
    }

    Vec3d cameraPos = camera.getCameraPos();
    List<Long> toRemove = new ArrayList<>();

    for (ChunkMesh mesh : this.chunkMeshes.values()) {
      double distance = mesh.distanceTo(cameraPos);
      if (distance > MAX_CHUNK_DISTANCE) {
        toRemove.add(mesh.origin.asLong());
      }
    }

    for (Long key : toRemove) {
      freeChunkAllocations(key, this.arena);
      this.chunkMeshes.remove(key);
      this.chunkLocks.remove(key);
    }

    if (!toRemove.isEmpty()) {
      MetalLogger.info(
          "[MeshBackend] Cleaned up {} distant chunks (cache size: {} -> {})",
          toRemove.size(), this.chunkMeshes.size() + toRemove.size(),
          this.chunkMeshes.size());
    }
  }

  public int emitDraws(long nativeHandle, RenderOptimizer optimizer,
      Camera camera) {
    if (this.chunkMeshes.isEmpty()) {
      return 0;
    }

    RenderingMetrics.resetFrame();
    long currentFrame = System.nanoTime() / 16_666_666L;
    if (currentFrame - this.lastCleanupFrame > 60) {
      this.cleanupDistantChunks(camera);
      this.lastCleanupFrame = currentFrame;
    }

    Vec3d cameraPos = camera.getCameraPos();
    boolean lodEnabled = MetalRenderConfig.distanceLodEnabled();
    int lodNear = MetalRenderConfig.lodDistanceThreshold();
    int lodFar = MetalRenderConfig.lodFarDistance();
    float distantScale = MetalRenderConfig.lodDistantScale();

    // Sort chunks front-to-back from camera position.
    // This populates the depth buffer with nearby geometry first,
    // allowing early-z rejection to skip fragment shading on
    // distant/occluded chunks — a significant GPU perf win.
    double cx = cameraPos.x;
    double cy = cameraPos.y;
    double cz = cameraPos.z;
    List<ChunkMesh> sorted = new ArrayList<>(this.chunkMeshes.values());
    sorted.sort((a, b) -> {
      double da = a.distanceSquaredTo(cx, cy, cz);
      double db = b.distanceSquaredTo(cx, cy, cz);
      return Double.compare(da, db);
    });

    int commandIndex = 0;
    for (ChunkMesh mesh : sorted) {
      if (!optimizer.shouldRenderChunk(mesh.origin, camera)) {
        continue;
      }

      double worldDistance = mesh.distanceTo(cameraPos);

      int lodLevel = 0;
      if (lodEnabled) {
        if (worldDistance > lodFar * 16.0) {
          lodLevel = Math.min(mesh.levelCount() - 1, 2);
        } else if (worldDistance > lodNear * 16.0) {
          lodLevel = Math.min(mesh.levelCount() - 1, 1);
        }
      }

      List<DrawCommand> draws = mesh.drawsForLevel(lodLevel);
      if (draws.isEmpty() && lodLevel > 0) {
        draws = mesh.drawsForLevel(lodLevel - 1);
      }
      if (draws.isEmpty()) {
        draws = mesh.drawsForLevel(0);
      }

      RenderingMetrics.recordLodUsage(lodLevel, 0, 0);

      for (DrawCommand draw : draws) {
        int vertexCount = draw.vertexCount;

        RenderingMetrics.addVertices(vertexCount);
        RenderingMetrics.addDrawCommand();
        NativeBridge.nQueueIndirectDraw(nativeHandle, commandIndex++,
            draw.vertexOffset, 0L, vertexCount,
            mesh.origin.getX(), mesh.origin.getY(),
            1, mesh.origin.getZ(),
            (float) worldDistance);
      }
    }

    return commandIndex;
  }

  private static final class ChunkMesh {
    final BlockPos origin;
    final LODLevel[] levels;

    ChunkMesh(BlockPos origin, int levelCount) {
      this.origin = origin;
      this.levels = new LODLevel[Math.max(1, levelCount)];
      for (int i = 0; i < this.levels.length; i++) {
        this.levels[i] = new LODLevel();
      }
    }

    void addDraw(int levelIndex, DrawCommand command) {
      this.level(levelIndex).add(command);
    }

    boolean hasDraws() {
      for (LODLevel level : this.levels) {
        if (!level.draws.isEmpty()) {
          return true;
        }
      }
      return false;
    }

    List<DrawCommand> drawsForLevel(int index) {
      return this.level(index).draws;
    }

    int levelCount() {
      return this.levels.length;
    }

    double distanceTo(Vec3d cameraPos) {
      double centerX = (double) this.origin.getX() + 8.0 - cameraPos.x;
      double centerY = (double) this.origin.getY() + 8.0 - cameraPos.y;
      double centerZ = (double) this.origin.getZ() + 8.0 - cameraPos.z;
      return Math.sqrt(centerX * centerX + centerY * centerY +
          centerZ * centerZ);
    }

    double distanceSquaredTo(double cx, double cy, double cz) {
      double dx = (double) this.origin.getX() + 8.0 - cx;
      double dy = (double) this.origin.getY() + 8.0 - cy;
      double dz = (double) this.origin.getZ() + 8.0 - cz;
      return dx * dx + dy * dy + dz * dz;
    }

    private LODLevel level(int index) {
      int clamped = Math.max(0, Math.min(index, this.levels.length - 1));
      return this.levels[clamped];
    }
  }

  private static final class LODLevel {
    final List<DrawCommand> draws = new CopyOnWriteArrayList<>();

    void add(DrawCommand command) {
      this.draws.add(command);
    }
  }

  private static final class DrawCommand {
    final int vertexOffset;
    final int vertexCount;
    final int byteLength; // original byte length for freeing

    DrawCommand(int vertexOffset, int vertexCount, int byteLength) {
      this.vertexOffset = vertexOffset;
      this.vertexCount = vertexCount;
      this.byteLength = byteLength;
    }
  }
}
