package com.metalrender.render;

import java.util.HashSet;
import java.util.Set;

/**
 * Centralized texture upload cache management.
 * 
 * Mixin classes cannot expose public static methods (Mixin framework rejects
 * them).
 * This non-mixin utility class holds the texture upload caches so they can be
 * cleared from MetalWorldRenderer at frame start, while still being accessed
 * from the mixin classes during draw interception.
 * 
 * Clearing each frame prevents stale textures when GL recycles texture IDs.
 */
public final class TextureCacheManager {

  /** GL texture IDs uploaded to Metal for entity/RenderLayer draws this frame. */
  public static final Set<Integer> uploadedTextures = new HashSet<>();

  /** GL texture IDs uploaded to Metal for GUI draws this frame. */
  public static final Set<Integer> uploadedGuiTextures = new HashSet<>();

  /** Override texture IDs that have been cleared (via FBO) this frame. */
  public static final Set<Integer> clearedOffscreenTextures = new HashSet<>();

  // ---- Offscreen FBO redirect state ----
  // Set by RenderLayerMixin when an offscreen FBO is bound.
  // Read by GL11CMixin to rebind offscreen FBO before glDrawElements.
  public static volatile boolean redirectFboForOffscreen = false;
  public static volatile int offscreenFboId = 0;

  // ---- Offscreen draw version tracking ----
  // Incremented by RenderLayerMixin each time a draw targets an offscreen
  // texture.
  // GuiRenderStateMixin compares this to its last-uploaded version to decide
  // whether to re-read the GL texture (content changed since last upload).
  public static final java.util.Map<Integer, Integer> offscreenDrawVersion = new java.util.HashMap<>();

  /**
   * Clear all texture upload caches. Called at the start of each frame
   * by MetalWorldRenderer.renderFrame().
   */
  public static void clearAll() {
    uploadedTextures.clear();
    uploadedGuiTextures.clear();
    clearedOffscreenTextures.clear();
    offscreenDrawVersion.clear();
  }

  private TextureCacheManager() {
  }
}
