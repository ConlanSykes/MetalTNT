package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the F3 debug HUD with Metal-specific metrics.
 * Since all rendering is now under Metal, the GL-based FPS and render stats
 * are meaningless. This mixin provides accurate Metal pipeline info.
 */
@Mixin(DebugHud.class)
public abstract class DebugHudMixin {

    @Shadow
    public abstract boolean shouldShowDebugHud();

    @Shadow
    private void drawText(DrawContext context, List<String> text, boolean right) {
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void metalrender$replaceDebugHud(DrawContext context, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;
        if (!this.shouldShowDebugHud())
            return;

        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer == null)
            return;

        // Cancel vanilla F3 rendering (which references GL stats)
        ci.cancel();

        MinecraftClient mc = MinecraftClient.getInstance();

        // LEFT SIDE: Metal rendering info
        List<String> left = new ArrayList<>();
        left.add("§b§lMetalRenderr §r§7(Metal Backend)");
        left.add("");

        // FPS and frame timing
        float metalFPS = renderer.getMetalFPS();
        float avgMs = renderer.getAvgFrameTimeMs();
        float lastMs = renderer.getLastFrameTimeMs();
        left.add(String.format("§eMetal FPS: §f%.0f §7(%.1f ms avg, %.1f ms last)", metalFPS, avgMs, lastMs));

        // Chunk mesh info
        int meshCount = renderer.getChunkMeshCount();
        left.add(String.format("§eChunk Meshes: §f%d", meshCount));

        // Arena memory
        String arenaInfo = renderer.getArenaInfo();
        left.add(String.format("§eGPU Arena: §f%s", arenaInfo));

        // GPU device name
        try {
            long handle = renderer.getHandle();
            if (handle != 0L) {
                String deviceName = NativeBridge.nGetDeviceName(handle);
                left.add(String.format("§eGPU: §f%s", deviceName));

                // GPU memory stats
                long deviceMem = NativeBridge.nGetDeviceMemory(handle);
                long memUsage = NativeBridge.nGetMemoryUsage(handle);
                if (deviceMem > 0) {
                    left.add(String.format("§eGPU Memory: §f%dMB / %dMB",
                            memUsage / (1024 * 1024), deviceMem / (1024 * 1024)));
                }
            }
        } catch (Throwable ignored) {
        }

        // Feature support
        left.add("");
        left.add(String.format("§eIndirect Draw: §f%s", NativeBridge.nSupportsIndirect() ? "§aYes" : "§cNo"));
        left.add(String.format("§eMesh Shaders: §f%s", NativeBridge.nSupportsMeshShaders() ? "§aYes" : "§cNo"));

        // Total frames
        left.add("");
        left.add(String.format("§7Total Metal Frames: %d", renderer.getTotalFrames()));

        // RIGHT SIDE: World info (standard MC data that's still valid)
        List<String> right = new ArrayList<>();

        // Player position
        if (mc.player != null) {
            double px = mc.player.getX();
            double py = mc.player.getY();
            double pz = mc.player.getZ();
            right.add(String.format("§eXYZ: §f%.3f / %.5f / %.3f", px, py, pz));

            int blockX = (int) Math.floor(px);
            int blockY = (int) Math.floor(py);
            int blockZ = (int) Math.floor(pz);
            right.add(String.format("§eBlock: §f%d %d %d", blockX, blockY, blockZ));

            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            int chunkRelX = blockX & 15;
            int chunkRelZ = blockZ & 15;
            right.add(String.format("§eChunk: §f%d %d in [%d %d]",
                    chunkRelX, chunkRelZ, chunkX, chunkZ));

            // Facing direction
            float yaw = mc.player.getYaw();
            String direction;
            float normYaw = ((yaw % 360) + 360) % 360;
            if (normYaw >= 315 || normYaw < 45)
                direction = "South (+Z)";
            else if (normYaw >= 45 && normYaw < 135)
                direction = "West (-X)";
            else if (normYaw >= 135 && normYaw < 225)
                direction = "North (-Z)";
            else
                direction = "East (+X)";
            right.add(String.format("§eFacing: §f%s (%.1f / %.1f)", direction, yaw, mc.player.getPitch()));
        }

        // World time
        if (mc.world != null) {
            long timeOfDay = mc.world.getTimeOfDay();
            long dayCount = timeOfDay / 24000L;
            long dayTicks = timeOfDay % 24000L;
            right.add("");
            right.add(String.format("§eDay: §f%d §7(tick %d)", dayCount, dayTicks));

            // Dimension
            try {
                String dimName = mc.world.getRegistryKey().getValue().toString();
                right.add(String.format("§eDimension: §f%s", dimName));
            } catch (Throwable ignored) {
            }
        }

        // Java info
        right.add("");
        Runtime rt = Runtime.getRuntime();
        long maxMem = rt.maxMemory() / (1024 * 1024);
        long usedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        right.add(String.format("§eJava Mem: §f%dMB / %dMB", usedMem, maxMem));
        right.add(String.format("§eJava: §f%s", System.getProperty("java.version")));

        // Draw the text lists
        drawText(context, left, false);
        drawText(context, right, true);
    }
}
