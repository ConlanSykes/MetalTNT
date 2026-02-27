package com.metalrender.util;

import com.metalrender.nativebridge.NativeBridge;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PersistentBufferArena {
  private final AtomicInteger cursor = new AtomicInteger();
  private ByteBuffer mapped;
  private int capacity;
  private int alignment = 20;
  private long contextHandle;
  private final TreeMap<Integer, Integer> freeBlocks = new TreeMap<>();
  private int totalFreed = 0;
  private int totalAllocated = 0;

  public boolean initialize(long ctx) {
    if (ctx == 0L) {
      return false;
    }
    ByteBuffer buffer = NativeBridge.nMapPersistentBuffer(ctx);
    if (buffer == null) {
      return false;
    }
    this.contextHandle = ctx;
    this.mapped = buffer;
    this.capacity = NativeBridge.nPersistentCapacity(ctx);
    this.alignment = Math.max(1, NativeBridge.nPersistentAlign(ctx));
    this.cursor.set(0);
    this.freeBlocks.clear();
    this.totalFreed = 0;
    this.totalAllocated = 0;
    return true;
  }

  public void reset() {
    this.cursor.set(0);
    this.freeBlocks.clear();
    this.totalFreed = 0;
    this.totalAllocated = 0;
  }

  public int capacity() {
    return this.capacity;
  }

  public int alignment() {
    return this.alignment;
  }

  public ByteBuffer buffer() {
    return this.mapped;
  }

  public int cursor() {
    return this.cursor.get();
  }

  public int totalFreed() {
    return this.totalFreed;
  }

  public int totalAllocated() {
    return this.totalAllocated;
  }

  public int freeBlockCount() {
    return this.freeBlocks.size();
  }

  // Callback to clear stale chunk meshes when buffer wraps
  private Runnable onWrapCallback;

  public void setOnWrap(Runnable callback) {
    this.onWrapCallback = callback;
  }

  public synchronized void free(int offset, int length) {
    if (offset < 0 || length <= 0)
      return;
    int aligned = align(length, this.alignment);

    Map.Entry<Integer, Integer> next = this.freeBlocks.higherEntry(offset);
    if (next != null && offset + aligned == next.getKey()) {
      aligned += next.getValue();
      this.freeBlocks.remove(next.getKey());
      this.totalFreed -= next.getValue();
    }

    Map.Entry<Integer, Integer> prev = this.freeBlocks.lowerEntry(offset);
    if (prev != null && prev.getKey() + prev.getValue() == offset) {
      int mergedOffset = prev.getKey();
      int mergedLen = prev.getValue() + aligned;
      this.totalFreed -= prev.getValue();
      this.freeBlocks.remove(prev.getKey());
      offset = mergedOffset;
      aligned = mergedLen;
    }

    if (offset + aligned == this.cursor.get()) {
      this.cursor.set(offset);
      NativeBridge.nPersistentAdvance(this.contextHandle, offset);
    } else {
      this.freeBlocks.put(offset, aligned);
      this.totalFreed += aligned;
    }

    this.totalAllocated -= align(length, this.alignment);
  }

  public synchronized int allocate(int length) {
    if (this.mapped == null || length <= 0) {
      return -1;
    }
    int aligned = align(length, this.alignment);

    int bestOffset = -1;
    int bestSize = Integer.MAX_VALUE;
    for (Map.Entry<Integer, Integer> entry : this.freeBlocks.entrySet()) {
      int blockSize = entry.getValue();
      if (blockSize >= aligned && blockSize < bestSize) {
        bestOffset = entry.getKey();
        bestSize = blockSize;
        if (blockSize == aligned)
          break;
      }
    }

    if (bestOffset >= 0) {
      this.freeBlocks.remove(bestOffset);
      this.totalFreed -= bestSize;
      int remainder = bestSize - aligned;
      if (remainder >= this.alignment) {
        this.freeBlocks.put(bestOffset + aligned, remainder);
        this.totalFreed += remainder;
      } else {
        aligned = bestSize;
      }
      this.totalAllocated += aligned;
      return bestOffset;
    }

    int current = this.cursor.get();
    if (current + aligned > this.capacity) {
      return -1;
    }
    this.cursor.addAndGet(aligned);
    NativeBridge.nPersistentAdvance(this.contextHandle, this.cursor.get());
    this.totalAllocated += aligned;
    return current;
  }

  private static int align(int value, int alignment) {
    int remainder = value % alignment;
    if (remainder == 0)
      return value;
    return value + (alignment - remainder);
  }
}
