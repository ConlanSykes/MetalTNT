package com.metalrender.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-slot item render cache. Caches rendered item textures by (slotId, itemKey)
 * to avoid re-rendering items that haven't changed. Works with Metal GPU blits
 * to extract per-slot tiles from the atlas snapshot.
 */
public final class ItemRenderCache {

    // ---- PendingBlit record ----

    /**
     * Describes a GPU blit from the atlas snapshot to a per-slot cache texture.
     */
    public static final class PendingBlit {
        public final int slotId;
        public final String itemKey;
        public final int cacheTexId;
        public final int atlasTexId;
        public final int srcX;
        public final int srcY;
        public final int srcW;
        public final int srcH;

        public PendingBlit(int slotId, String itemKey, int cacheTexId, int atlasTexId,
                           int srcX, int srcY, int srcW, int srcH) {
            this.slotId = slotId;
            this.itemKey = itemKey;
            this.cacheTexId = cacheTexId;
            this.atlasTexId = atlasTexId;
            this.srcX = srcX;
            this.srcY = srcY;
            this.srcW = srcW;
            this.srcH = srcH;
        }
    }

    // ---- Internal state ----

    /** Pending blits queued this frame, processed by compositeOverlay. */
    private static final List<PendingBlit> pendingBlits = new ArrayList<>();

    /** Cache: cacheKey → GL texture ID of the cached per-slot texture. */
    private static final Map<String, Integer> cache = new HashMap<>();

    /** Which slots have been rendered this frame. */
    private static final Set<Integer> renderedSlots = new HashSet<>();

    /** Hit / miss counters for diagnostics. */
    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicLong misses = new AtomicLong();

    /** Next available cache texture ID (simple monotonic allocator). */
    private static int nextCacheTexId = 60000; // start high to avoid GL ID collisions

    private ItemRenderCache() {}

    // ---- Cache key helpers ----

    private static String cacheKey(int slotId, String itemKey) {
        return slotId + ":" + itemKey;
    }

    // ---- Public API ----

    /**
     * Return the cached Metal texture ID for a given slot+item, or -1 if not cached.
     */
    public static int getCachedTexId(int slotId, String itemKey) {
        Integer texId = cache.get(cacheKey(slotId, itemKey));
        return texId != null ? texId : -1;
    }

    /**
     * Get or create a blit-target texture ID for a slot+item that had a cache miss.
     * If the slot already has a cache entry (different item), it is replaced.
     */
    public static int getOrCreateBlitTarget(int slotId, String itemKey) {
        String key = cacheKey(slotId, itemKey);
        Integer existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        // Remove any stale entry for this slot (item changed)
        cache.entrySet().removeIf(e -> e.getKey().startsWith(slotId + ":"));
        int texId = nextCacheTexId++;
        cache.put(key, texId);
        return texId;
    }

    /** Mark a slot as having been rendered (drawItem called) this frame. */
    public static void markSlotRendered(int slotId) {
        renderedSlots.add(slotId);
    }

    /** Clear per-frame slot tracking at end of frame. */
    public static void clearFrameTracking() {
        renderedSlots.clear();
    }

    // ---- Pending blits ----

    public static void addPendingBlit(PendingBlit blit) {
        pendingBlits.add(blit);
    }

    public static List<PendingBlit> getPendingBlits() {
        return new ArrayList<>(pendingBlits);
    }

    public static void clearPendingBlits() {
        pendingBlits.clear();
    }

    // ---- Metrics ----

    public static int getCacheSize() {
        return cache.size();
    }

    public static void recordHit() {
        hits.incrementAndGet();
    }

    public static void recordMiss() {
        misses.incrementAndGet();
    }

    public static long getHits() {
        return hits.get();
    }

    public static long getMisses() {
        return misses.get();
    }

    /** Full reset (e.g. on world change or config reload). */
    public static void reset() {
        pendingBlits.clear();
        cache.clear();
        renderedSlots.clear();
        hits.set(0);
        misses.set(0);
    }
}
