#include "metalrender.h"
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <AppKit/AppKit.h>
#define GL_SILENCE_DEPRECATION
#include <OpenGL/gl3.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

// Native version stamp — logged at init, queryable from Java
static const char* NATIVE_VERSION = "v2026-02-21-generic-draw-v2";

namespace {
struct MetalContext {
  id<MTLDevice> device = nil;
  id<MTLCommandQueue> graphicsQueue = nil;
  id<MTLCommandQueue> computeQueue = nil;
  id<MTLLibrary> library = nil;
  id<MTLComputePipelineState> occlusionPipeline = nil;
  id<MTLBuffer> aabbBuffer = nil;
  id<MTLBuffer> occlusionResultBuffer = nil;
  id<MTLBuffer> occlusionConstants = nil;
  id<MTLBuffer> persistentBuffer = nil;
  id<MTLBuffer> indirectArgs = nil;
  uint32_t maxIndirectCommands = 65536;
  uint32_t currentIndirectCount = 0;
  size_t persistentCapacity = 1024 * 1024 * 1024; // 1GB — macOS unified memory
  // Alignment must be a multiple of vertex stride (20 bytes for Sodium COMPACT)
  // so that baseVertex = byteOffset / 20 divides evenly.
  size_t persistentAlignment = 20;
  size_t persistentCursor = 0;
  std::string deviceName;
  bool hasViewProj = false;
  float viewProj[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  // Projection-only matrix for entity/UI rendering (positions already in view space)
  bool hasProjMatrix = false;
  float projMatrix[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  bool meshShadersSupported = false;
  float temporalJitterX = 0.0F;
  float temporalJitterY = 0.0F;
  float temporalBlend = 0.0F;
  // Frame rendering state (for nBeginFrame)
  id<MTLCommandBuffer> currentCommandBuffer = nil;
  id<MTLRenderCommandEncoder> currentRenderEncoder = nil;
  CAMetalLayer *metalLayer = nil;
  id<CAMetalDrawable> currentDrawable = nil;
  id<MTLTexture> depthTexture = nil;
  id<MTLTexture> offscreenColorTexture = nil;  // For Option A fallback rendering
  uint32_t currentWidth = 0;
  uint32_t currentHeight = 0;
  // FEATURE_005: Test Triangle - Validation Geometry
  id<MTLBuffer> testVertexBuffer = nil;
  id<MTLBuffer> testIndexBuffer = nil;
  bool testTriangleCreated = false;
  id<MTLRenderPipelineState> testPipeline = nil;  // Simple pipeline for test triangle
  // FEATURE_005: Terrain rendering resources
  id<MTLRenderPipelineState> terrainPipeline = nil;
  id<MTLTexture> atlasTexture = nil;
  id<MTLSamplerState> atlasSampler = nil;
  // FEATURE_006: Currently bound index buffer for indirect draws
  id<MTLBuffer> currentIndexBuffer = nil;
  // Shared quad→triangle index buffer (pattern: 0,1,2, 2,3,0 repeated)
  id<MTLBuffer> quadIndexBuffer = nil;
  uint32_t quadIndexMaxQuads = 0;
  // Overlay NSView that hosts the CAMetalLayer on top of the GL content view
  NSView *metalOverlay = nil;
  // FEATURE_012: Per-draw data buffer (chunk origins for shader lookup)
  id<MTLBuffer> drawDataBuffer = nil;
  // FEATURE_012: Depth stencil state for terrain rendering
  id<MTLDepthStencilState> terrainDepthState = nil;
  // Fallback 1x1 magenta texture — bound when atlas is nil to make the problem visible
  id<MTLTexture> fallbackTexture = nil;
  // Generic draw system: for entities, UI, particles etc.
  id<MTLRenderPipelineState> genericPipeline = nil;
  id<MTLRenderPipelineState> additivePipeline = nil; // For celestial bodies (sun/moon/stars)
  id<MTLRenderPipelineState> multiplyPipeline = nil; // For entity shadows (multiply blend)
  id<MTLRenderPipelineState> offscreenPipeline = nil; // For offscreen RTT (no dest blending)
  id<MTLBuffer> genericVertexBuffer = nil;
  size_t genericVertexCapacity = 0;
  // Per-frame draw commands queued from Java
  struct GenericDrawCmd {
    uint32_t vertexOffset;    // byte offset into genericVertexBuffer
    uint32_t vertexCount;
    uint32_t textureId;       // index into boundTextures
    uint32_t blendMode;       // 0=sky, 1=alpha+depth, 2=UI, 3=text, 4=shadow(multiply)
    float modelView[16];
    bool hasModelView;        // true if per-draw matrix was provided
  };
  std::vector<GenericDrawCmd> genericDraws;
  size_t genericVertexCursor = 0; // current write position in bytes
  // Texture cache for entity/UI textures (NSNumber key → id<MTLTexture> value)
  NSMutableDictionary<NSNumber*, id<MTLTexture>> *boundTextures = nil;
  id<MTLSamplerState> genericSampler = nil;
  // UI depth state: no depth test or write (for 2D overlay rendering)
  id<MTLDepthStencilState> uiDepthState = nil;
  // Celestial depth state: depth TEST on (terrain occludes), depth WRITE off
  id<MTLDepthStencilState> skyDepthState = nil;
  // Shadow depth state: depth TEST on (LEQUAL), depth WRITE off
  id<MTLDepthStencilState> shadowDepthState = nil;
  // Frame synchronization: hold reference to previous command buffer
  // so we can wait for GPU completion before overwriting shared buffers
  id<MTLCommandBuffer> previousCommandBuffer = nil;
  // Sky brightness for day/night cycle (0.0 = night, 1.0 = full day)
  float skyBrightness = 1.0f;

  // Offscreen render-to-texture for item model rendering (Metal RTT)
  id<MTLTexture> offscreenRTColor = nil;         // Offscreen color render target
  id<MTLTexture> offscreenRTDepth = nil;         // Offscreen depth buffer
  int offscreenRTWidth = 0;
  int offscreenRTHeight = 0;
  bool inOffscreenPass = false;
  id<MTLCommandBuffer> offscreenCmdBuf = nil;
  id<MTLRenderCommandEncoder> offscreenEncoder = nil;
  // Reusable vertex buffer for offscreen draws (avoids 4KB setVertexBytes limit)
  id<MTLBuffer> offscreenVertexBuffer = nil;
  size_t offscreenVertexCapacity = 0;
  // Per-frame offscreen draw tracking (for diagnostics)
  uint32_t offscreenFrameDraws = 0;
  uint32_t offscreenFrameVerts = 0;
  // Per-frame clear flag: set true at frame start, consumed on first offscreen pass
  bool offscreenNeedsClear = true;
};

struct HiZResources {
  id<MTLTexture> depthTexture = nil;
  id<MTLTexture> pyramidTexture = nil;
  uint32_t width = 0;
  uint32_t height = 0;
};

// Forward declarations (after MetalContext is defined)
static id<CAMetalDrawable> getMetalDrawable(MetalContext *ctx);
static void presentFrame(MetalContext *ctx, id<CAMetalDrawable> drawable);

static std::mutex gMutex;

// Throttle per-frame debug logging to avoid performance impact
static uint64_t gFrameCount = 0;
static inline bool shouldLog() { return (gFrameCount % 300) == 0; }  // ~every 5 sec at 60fps

// ============================================================================
// FEATURE_012: Production Terrain Shader (Sodium COMPACT 20-byte format)
// Layout per vertex (5 x uint32):
//   uint posHi (x10|y10|z10 upper bits)
//   uint posLo (x10|y10|z10 lower bits)
//   uint color ARGB (AO pre-applied)
//   uint texUV (u15+sign | v15+sign)
//   uint lightMaterial (block8|sky8|material8|section8)
// Total: 20 bytes = 5 x uint32
// ============================================================================
static const char *kTerrainShaderSource = R"METAL(
#include <metal_stdlib>
using namespace metal;

constant uint POSITION_MAX_VALUE = 1u << 20u;
constant float MODEL_ORIGIN = 8.0f;
constant float MODEL_RANGE = 32.0f;
constant float VERTEX_SCALE = MODEL_RANGE / float(POSITION_MAX_VALUE);
constant float VERTEX_OFFSET = -MODEL_ORIGIN;

struct DrawData {
    float originX;
    float originY;
    float originZ;
    float padding;
};

struct FrameUniforms {
    float4x4 viewProj;
    float2 screenDim;
    float time;
    float debugMode;  // 0=normal, 1=UV viz, 2=vertex color only, 3=texture only
    float skyBrightness; // 0.0 = night, 1.0 = full day
};

struct TerrainVertexOut {
    float4 position [[position]];
    float2 texCoord;
    half4 color;
    half2 light;
};

float3 decodePosition(uint posHi, uint posLo) {
    uint xHi = (posHi >> 0u) & 0x3FFu;
    uint yHi = (posHi >> 10u) & 0x3FFu;
    uint zHi = (posHi >> 20u) & 0x3FFu;
    uint xLo = (posLo >> 0u) & 0x3FFu;
    uint yLo = (posLo >> 10u) & 0x3FFu;
    uint zLo = (posLo >> 20u) & 0x3FFu;
    float x = float((xHi << 10u) | xLo) * VERTEX_SCALE + VERTEX_OFFSET;
    float y = float((yHi << 10u) | yLo) * VERTEX_SCALE + VERTEX_OFFSET;
    float z = float((zHi << 10u) | zLo) * VERTEX_SCALE + VERTEX_OFFSET;
    return float3(x, y, z);
}

vertex TerrainVertexOut terrain_vertex(
    uint vertexId [[vertex_id]],
    uint instanceId [[instance_id]],
    device const uint* vertexData [[buffer(0)]],
    device const DrawData* drawData [[buffer(1)]],
    constant FrameUniforms& frame [[buffer(2)]]
) {
    // 20 bytes = 5 uint32s per vertex
    uint base = vertexId * 5u;
    uint posHi       = vertexData[base + 0u];
    uint posLo       = vertexData[base + 1u];
    uint colorPacked = vertexData[base + 2u];
    uint texPacked   = vertexData[base + 3u];
    uint lightMat    = vertexData[base + 4u];

    float3 localPos = decodePosition(posHi, posLo);
    DrawData dd = drawData[instanceId];
    float3 worldPos = localPos + float3(dd.originX, dd.originY, dd.originZ);

    // Decode ABGR color — Sodium stores vertex colors as ABGR uint32:
    //   Sodium's GL attribute: GL_UNSIGNED_BYTE×4 normalized → reads bytes as RGBA
    //   On little-endian, ABGR uint32 → memory bytes [R, G, B, A] → GL reads vec4(R,G,B,A)
    //   As uint: bits 0-7=R, 8-15=G, 16-23=B, 24-31=A
    half4 color = half4(
        half((colorPacked >> 0u) & 0xFFu) / 255.0h,   // R
        half((colorPacked >> 8u) & 0xFFu) / 255.0h,   // G
        half((colorPacked >> 16u) & 0xFFu) / 255.0h,  // B
        half((colorPacked >> 24u) & 0xFFu) / 255.0h   // A (AO)
    );

    // Decode texture coords (15-bit value, bit 15 = bias direction)
    const float TEX_SCALE = 1.0f / 32768.0f;
    uint uRaw = texPacked & 0xFFFFu;
    uint vRaw = (texPacked >> 16u) & 0xFFFFu;
    float2 texCoord = float2(
        float(uRaw & 0x7FFFu) * TEX_SCALE,
        float(vRaw & 0x7FFFu) * TEX_SCALE
    );

    // Decode light (block=low byte, sky=next byte, 8-248 range)
    half blockLight = half(lightMat & 0xFFu) / 255.0h;
    half skyLight   = half((lightMat >> 8u) & 0xFFu) / 255.0h;

    TerrainVertexOut out;
    out.position = frame.viewProj * float4(worldPos, 1.0f);
    out.texCoord = texCoord;
    out.color = color;
    out.light = half2(blockLight, skyLight);
    return out;
}

fragment half4 terrain_fragment(
    TerrainVertexOut in [[stage_in]],
    texture2d<half, access::sample> atlas [[texture(0)]],
    sampler atlasSampler [[sampler(0)]],
    constant FrameUniforms& frame [[buffer(2)]]
) {
    // Debug mode 1: UV visualization (R=U, G=V, B=0)
    if (frame.debugMode > 0.5 && frame.debugMode < 1.5) {
        return half4(half(in.texCoord.x), half(in.texCoord.y), 0.0h, 1.0h);
    }
    // Debug mode 2: vertex color only (no texture)
    if (frame.debugMode > 1.5 && frame.debugMode < 2.5) {
        return half4(in.color.rgb, 1.0h);
    }

    // Debug mode 4: flat white — proves geometry is reaching the fragment shader
    if (frame.debugMode > 3.5 && frame.debugMode < 4.5) {
        return half4(1.0h, 1.0h, 1.0h, 1.0h);
    }

    // Debug mode 5: world-position-based coloring (proves vertex positions are valid)
    // Each block-sized region gets a distinct color
    if (frame.debugMode > 4.5 && frame.debugMode < 5.5) {
        float3 wp = float3(in.texCoord.x, in.texCoord.y, 0.0);  // using texcoords as proxy
        return half4(half(fract(in.texCoord.x * 4.0)), half(fract(in.texCoord.y * 4.0)),
                     half(in.color.r), 1.0h);
    }

    half4 texColor = atlas.sample(atlasSampler, in.texCoord);

    // Debug mode 3: texture only — with diagnostic coloring
    // RED = texture sample returned all-zero (atlas empty at UV)
    // BLUE = texture has alpha but no RGB (unusual)
    // normal color = texture is working
    if (frame.debugMode > 2.5 && frame.debugMode < 3.5) {
        if (texColor.r < 0.01h && texColor.g < 0.01h && texColor.b < 0.01h && texColor.a < 0.01h) {
            return half4(1.0h, 0.0h, 0.0h, 1.0h); // BRIGHT RED = all-zero sample
        }
        if (texColor.r < 0.01h && texColor.g < 0.01h && texColor.b < 0.01h) {
            return half4(0.0h, 0.0h, 1.0h, 1.0h); // BLUE = zero RGB, non-zero alpha
        }
        return half4(texColor.rgb, 1.0h);
    }

    // Debug mode 6: show UV as atlas pixel coords (R=U*atlasW/4096, G=V*atlasH/4096)
    if (frame.debugMode > 5.5 && frame.debugMode < 6.5) {
        return half4(
            half(fract(in.texCoord.x * 8.0)),
            half(fract(in.texCoord.y * 8.0)),
            half(texColor.a),
            1.0h
        );
    }

    // Debug mode 7: texture with vertex color, NO discard, NO lighting
    if (frame.debugMode > 6.5 && frame.debugMode < 7.5) {
        return half4(texColor.rgb * in.color.rgb, 1.0h);
    }

    // Debug mode 8: hardcoded atlas probe — sample at known opaque sprite position
    // Sandstone is at atlas pixel (1012, 1028) = UV (1012/2048, 1028/2048) = (0.4941, 0.5020)
    // If blocks show sandstone color (warm beige) → atlas data is on GPU ✓
    // If blocks show RED → atlas is empty at this position ✗
    if (frame.debugMode > 7.5 && frame.debugMode < 8.5) {
        float2 probeUV = float2(1012.0 / 2048.0, 1028.0 / 2048.0);
        half4 probe = atlas.sample(atlasSampler, probeUV);
        if (probe.r < 0.01h && probe.g < 0.01h && probe.b < 0.01h && probe.a < 0.01h) {
            return half4(1.0h, 0.0h, 0.0h, 1.0h); // RED = probe position empty
        }
        return half4(probe.rgb, 1.0h); // Should be sandstone beige
    }

    // Debug mode 9: texture alpha visualization
    // White = fully opaque, Black = fully transparent, Grey = semi-transparent
    if (frame.debugMode > 8.5 && frame.debugMode < 9.5) {
        return half4(texColor.a, texColor.a, texColor.a, 1.0h);
    }

    // Discard fully transparent pixels (cutout transparency like leaves, flowers)
    if (texColor.a < 0.1h) {
        discard_fragment();
    }

    // Combine texture color with vertex tint color
    // Texture provides the block pattern, vertex color provides biome tinting
    half3 finalColor = texColor.rgb * in.color.rgb;

    // Light: Sodium encodes 0-15 light levels into bytes.
    // Separate block light (torches etc) from sky light (sun/moon).
    // Sky light is modulated by skyBrightness for day/night cycle.
    half blockLight = in.light.x;
    half skyLight = in.light.y * half(frame.skyBrightness);
    half lightLevel = max(blockLight, skyLight);
    // Apply Minecraft's non-linear brightness curve:
    // brightness = (1-j)/(j*3+1) * 0.9 + 0.1 where j = 1 - lightLevel
    // This matches MC's internal light-to-brightness table (0.1 ambient in Overworld)
    half j = 1.0h - lightLevel;
    half light = ((1.0h - j) / (j * 3.0h + 1.0h)) * 0.9h + 0.1h;
    finalColor *= light;

    return half4(finalColor, 1.0h);
}
)METAL";

// ============================================================================
// Generic Shader: For entity, UI, particle rendering.
// Accepts a unified vertex format: float3 pos, ubyte4 color, float2 uv.
// Supports textured and untextured (solid color) draws.
// ============================================================================
static const char *kGenericShaderSource = R"METAL(
#include <metal_stdlib>
using namespace metal;

struct GenericUniforms {
    float4x4 viewProj;
    uint flags; // bit 0: has texture, bit 1: world phase (apply sky brightness)
    float skyBrightness; // 0.0 = night, 1.0 = full day
};

struct GenericVertexIn {
    float3 position [[attribute(0)]];
    uchar4 color    [[attribute(1)]];
    float2 uv       [[attribute(2)]];
    float2 light    [[attribute(3)]]; // blockLight, skyLight (0-1)
};

struct GenericVertexOut {
    float4 position [[position]];
    half4  color;
    float2 uv;
    half2  light; // blockLight, skyLight
};

vertex GenericVertexOut generic_vertex(
    GenericVertexIn in [[stage_in]],
    constant GenericUniforms &uniforms [[buffer(1)]]
) {
    GenericVertexOut out;
    out.position = uniforms.viewProj * float4(in.position, 1.0);
    out.color = half4(float4(in.color) / 255.0h);
    out.uv = in.uv;
    out.light = half2(in.light);
    return out;
}

fragment half4 generic_fragment_textured(
    GenericVertexOut in [[stage_in]],
    texture2d<half> tex [[texture(0)]],
    sampler samp [[sampler(0)]],
    constant GenericUniforms &uniforms [[buffer(1)]]
) {
    half4 texColor = tex.sample(samp, in.uv);
    half4 result = texColor * in.color;
    // Discard fully transparent fragments — but NOT for offscreen "storage bucket" draws
    // (flag bit 2 = 4u). For offscreen, we want new items to fully overwrite old content,
    // including transparent pixels, to prevent old item remnants from bleeding through.
    if ((uniforms.flags & 4u) == 0u && result.a < 0.004h) discard_fragment();
    // Apply per-vertex lighting for world-phase draws (entities, mobs, items in world)
    if ((uniforms.flags & 2u) != 0u) {
        half blockLight = in.light.x;
        half skyLight = in.light.y * half(uniforms.skyBrightness);
        half lightLevel = max(blockLight, skyLight);
        // Apply Minecraft's non-linear brightness curve:
        // brightness = (1-j)/(j*3+1) * 0.9 + 0.1 where j = 1 - lightLevel
        // Matches MC's internal light table (0.1 Overworld ambient)
        half j = 1.0h - lightLevel;
        half brightness = ((1.0h - j) / (j * 3.0h + 1.0h)) * 0.9h + 0.1h;
        result.rgb *= brightness;
    }
    return result;
}

fragment half4 generic_fragment_colored(
    GenericVertexOut in [[stage_in]]
) {
    return in.color;
}
)METAL";

static const char *kOcclusionSource = R"METAL(
#include <metal_stdlib>
using namespace metal;
struct Aabb {
	float3 minBounds;
	float3 maxBounds;
};
struct OcclusionConstants {
	uint count;
	float2 hiZSize;
};
inline float4 projectCorner(float3 corner, constant float4x4& viewProj) {
	return viewProj * float4(corner, 1.0);
}
kernel void occlusion_test(const device Aabb* aabbs [[buffer(0)]],
						   constant float4x4& viewProj [[buffer(1)]],
						   device uchar* results [[buffer(2)]],
						   constant OcclusionConstants& constants [[buffer(3)]],
						   texture2d<float> hiZTexture [[texture(0)]],
						   uint id [[thread_position_in_grid]]) {
	if (id >= constants.count) return;
	constexpr sampler hiZSampler(coord::normalized, address::clamp_to_edge, filter::nearest, mip_filter::nearest);
	
	Aabb box = aabbs[id];
	float3 corners[8];
	corners[0] = float3(box.minBounds.x, box.minBounds.y, box.minBounds.z);
	corners[1] = float3(box.maxBounds.x, box.minBounds.y, box.minBounds.z);
	corners[2] = float3(box.minBounds.x, box.maxBounds.y, box.minBounds.z);
	corners[3] = float3(box.maxBounds.x, box.maxBounds.y, box.minBounds.z);
	corners[4] = float3(box.minBounds.x, box.minBounds.y, box.maxBounds.z);
	corners[5] = float3(box.maxBounds.x, box.minBounds.y, box.maxBounds.z);
	corners[6] = float3(box.minBounds.x, box.maxBounds.y, box.maxBounds.z);
	corners[7] = float3(box.maxBounds.x, box.maxBounds.y, box.maxBounds.z);
	
	float minX = 1.0, maxX = -1.0, minY = 1.0, maxY = -1.0;
	float nearestZ = 1.0;
	bool allBehind = true;
	bool allClipped = true;
	
	for (uint i = 0; i < 8; ++i) {
		float4 clip = projectCorner(corners[i], viewProj);
		if (clip.w <= 0.001f) continue;
		float3 ndc = clip.xyz / clip.w;
		
		if (ndc.z < 1.0f) allBehind = false;
		if (ndc.x >= -1.0f && ndc.x <= 1.0f && ndc.y >= -1.0f && ndc.y <= 1.0f) allClipped = false;
		
		minX = min(minX, ndc.x);
		maxX = max(maxX, ndc.x);
		minY = min(minY, ndc.y);
		maxY = max(maxY, ndc.y);
		nearestZ = min(nearestZ, ndc.z);
	}
	
	if (allBehind || allClipped || maxX < -1.0f || minX > 1.0f || maxY < -1.0f || minY > 1.0f) {
		results[id] = 1;
		return;
	}
	
	float2 screenMin = float2((minX + 1.0f) * 0.5f, (1.0f - maxY) * 0.5f);
	float2 screenMax = float2((maxX + 1.0f) * 0.5f, (1.0f - minY) * 0.5f);
	screenMin = clamp(screenMin, float2(0.0f), float2(1.0f));
	screenMax = clamp(screenMax, float2(0.0f), float2(1.0f));
	
	float2 screenSize = (screenMax - screenMin) * constants.hiZSize;
	float maxDim = max(screenSize.x, screenSize.y);
	float mipLevel = max(0.0f, floor(log2(maxDim)));
	
	float2 samplePos = (screenMin + screenMax) * 0.5f;
	float hiZDepth = hiZTexture.sample(hiZSampler, samplePos, level(mipLevel)).r;
	
	bool occluded = (nearestZ > hiZDepth + 0.0001f);
	results[id] = occluded ? 1 : 0;
}
)METAL";

static id<MTLLibrary> createLibraryFromSource(id<MTLDevice> device,
                                              NSString *label) {
  NSError *error = nil;
  MTLCompileOptions *options = [[MTLCompileOptions alloc] init];
  options.fastMathEnabled = YES;
  id<MTLLibrary> library = [device
      newLibraryWithSource:[NSString stringWithUTF8String:kOcclusionSource]
                   options:options
                     error:&error];
  if (!library || error) {
    fprintf(stderr, "[MetalRender] Failed to compile occlusion shader: %s\n",
            error ? [[error localizedDescription] UTF8String]
                  : "unknown error");
    return nil;
  }
  (void)label;
  return library;
}

static MetalContext *getContext(jlong handle) {
  return reinterpret_cast<MetalContext *>(static_cast<intptr_t>(handle));
}

static HiZResources *getHiZ(jlong handle) {
  return reinterpret_cast<HiZResources *>(static_cast<intptr_t>(handle));
}

static void destroyHiZImpl(HiZResources *hiz) {
  if (!hiz)
    return;
  if (hiz->depthTexture)
    hiz->depthTexture = nil;
  if (hiz->pyramidTexture)
    hiz->pyramidTexture = nil;
  delete hiz;
}

static bool ensureOcclusionPipeline(MetalContext *ctx) {
  if (!ctx || !ctx->device)
    return false;
  if (ctx->occlusionPipeline)
    return true;
  if (!ctx->library) {
    ctx->library =
        createLibraryFromSource(ctx->device, @"MetalRenderOcclusion");
    if (!ctx->library)
      return false;
  }
  NSError *error = nil;
  id<MTLFunction> func = [ctx->library newFunctionWithName:@"occlusion_test"];
  if (!func)
    return false;
  ctx->occlusionPipeline =
      [ctx->device newComputePipelineStateWithFunction:func error:&error];
  if (!ctx->occlusionPipeline || error) {
    fprintf(stderr, "[MetalRender] Failed to create occlusion pipeline: %s\n",
            error ? [[error localizedDescription] UTF8String]
                  : "unknown error");
    ctx->occlusionPipeline = nil;
    return false;
  }
  return true;
}

// Create the generic render pipeline for entity/UI/particle rendering
static bool ensureGenericPipeline(MetalContext *ctx) {
  if (ctx->genericPipeline) return true;
  if (!ctx || !ctx->device) return false;

  NSError *error = nil;
  MTLCompileOptions *options = [[MTLCompileOptions alloc] init];
  options.fastMathEnabled = YES;
  id<MTLLibrary> genericLib = [ctx->device
      newLibraryWithSource:[NSString stringWithUTF8String:kGenericShaderSource]
                   options:options
                     error:&error];
  if (!genericLib) {
    fprintf(stderr, "[MetalRender] Failed to compile generic shader: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
    return false;
  }

  id<MTLFunction> vertFunc = [genericLib newFunctionWithName:@"generic_vertex"];
  id<MTLFunction> fragTextured = [genericLib newFunctionWithName:@"generic_fragment_textured"];
  if (!vertFunc || !fragTextured) {
    fprintf(stderr, "[MetalRender] Failed to find generic shader functions\n");
    return false;
  }

  // Vertex descriptor: float3 pos (12) + ubyte4 color (4) + float2 uv (8) + float2 light (8) = 32 bytes
  MTLVertexDescriptor *vertDesc = [[MTLVertexDescriptor alloc] init];
  // Attribute 0: position (float3)
  vertDesc.attributes[0].format = MTLVertexFormatFloat3;
  vertDesc.attributes[0].offset = 0;
  vertDesc.attributes[0].bufferIndex = 0;
  // Attribute 1: color (ubyte4 normalized)
  vertDesc.attributes[1].format = MTLVertexFormatUChar4;
  vertDesc.attributes[1].offset = 12;
  vertDesc.attributes[1].bufferIndex = 0;
  // Attribute 2: uv (float2)
  vertDesc.attributes[2].format = MTLVertexFormatFloat2;
  vertDesc.attributes[2].offset = 16;
  vertDesc.attributes[2].bufferIndex = 0;
  // Attribute 3: light (float2 — blockLight, skyLight)
  vertDesc.attributes[3].format = MTLVertexFormatFloat2;
  vertDesc.attributes[3].offset = 24;
  vertDesc.attributes[3].bufferIndex = 0;
  // Layout: 32 bytes per vertex
  vertDesc.layouts[0].stride = 32;
  vertDesc.layouts[0].stepFunction = MTLVertexStepFunctionPerVertex;

  MTLRenderPipelineDescriptor *desc = [[MTLRenderPipelineDescriptor alloc] init];
  desc.label = @"MetalRender Generic (Textured)";
  desc.vertexFunction = vertFunc;
  desc.fragmentFunction = fragTextured;
  desc.vertexDescriptor = vertDesc;
  desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
  desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;

  // Alpha blending for entities/UI
  desc.colorAttachments[0].blendingEnabled = YES;
  desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
  desc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
  desc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
  desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
  desc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
  desc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;

  ctx->genericPipeline = [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
  if (!ctx->genericPipeline) {
    fprintf(stderr, "[MetalRender] Failed to create generic pipeline: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
    return false;
  }

  // Create additive blend pipeline for celestial bodies (sun/moon/stars)
  // MC renders sun/moon with additive blending (GL_SRC_ALPHA, GL_ONE) so
  // black background becomes transparent and bright pixels add to the sky.
  MTLRenderPipelineDescriptor *additiveDesc = [[MTLRenderPipelineDescriptor alloc] init];
  additiveDesc.label = @"MetalRender Generic (Additive)";
  additiveDesc.vertexFunction = vertFunc;
  additiveDesc.fragmentFunction = fragTextured;
  additiveDesc.vertexDescriptor = vertDesc;
  additiveDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
  additiveDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
  additiveDesc.colorAttachments[0].blendingEnabled = YES;
  additiveDesc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
  additiveDesc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOne;
  additiveDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
  additiveDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
  additiveDesc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
  additiveDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;

  ctx->additivePipeline = [ctx->device newRenderPipelineStateWithDescriptor:additiveDesc error:&error];
  if (!ctx->additivePipeline) {
    fprintf(stderr, "[MetalRender] Failed to create additive pipeline: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
    // Non-fatal: fall back to generic pipeline for celestial draws
  }

  // Create multiply blend pipeline for entity shadows (kept for future use).
  // Not currently used in the draw loop but available for true multiply blending.
  MTLRenderPipelineDescriptor *multiplyDesc = [[MTLRenderPipelineDescriptor alloc] init];
  multiplyDesc.label = @"MetalRender Generic (Multiply/Shadow)";
  multiplyDesc.vertexFunction = vertFunc;
  multiplyDesc.fragmentFunction = fragTextured;
  multiplyDesc.vertexDescriptor = vertDesc;
  multiplyDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
  multiplyDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
  multiplyDesc.colorAttachments[0].blendingEnabled = YES;
  // Standard alpha blend (MC shadows use dark vertex colors with alpha)
  multiplyDesc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
  multiplyDesc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
  multiplyDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
  multiplyDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorZero;
  multiplyDesc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
  multiplyDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;

  ctx->multiplyPipeline = [ctx->device newRenderPipelineStateWithDescriptor:multiplyDesc error:&error];
  if (!ctx->multiplyPipeline) {
    fprintf(stderr, "[MetalRender] Failed to create multiply pipeline: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
    // Non-fatal: shadows will fall back to generic alpha blend
  }

  // Create offscreen pipeline for item atlas rendering.
  // Uses NO destination blending (source fully replaces destination).
  // This prevents ghosting when using LoadActionLoad to preserve atlas content:
  // old item pixels at the same position are fully overwritten, not blended.
  MTLRenderPipelineDescriptor *offscreenDesc = [[MTLRenderPipelineDescriptor alloc] init];
  offscreenDesc.label = @"MetalRender Offscreen (No Dest Blend)";
  offscreenDesc.vertexFunction = vertFunc;
  offscreenDesc.fragmentFunction = fragTextured;
  offscreenDesc.vertexDescriptor = vertDesc;
  offscreenDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
  offscreenDesc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
  offscreenDesc.colorAttachments[0].blendingEnabled = YES;
  // Source overwrites destination entirely — no blending with old atlas content
  offscreenDesc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
  offscreenDesc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorZero;
  offscreenDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
  offscreenDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
  offscreenDesc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorZero;
  offscreenDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;

  ctx->offscreenPipeline = [ctx->device newRenderPipelineStateWithDescriptor:offscreenDesc error:&error];
  if (!ctx->offscreenPipeline) {
    fprintf(stderr, "[MetalRender] Failed to create offscreen pipeline: %s\n",
            error ? [[error localizedDescription] UTF8String] : "unknown");
    // Fall back to generic pipeline
    ctx->offscreenPipeline = ctx->genericPipeline;
  }

  // Create sampler for generic textures
  MTLSamplerDescriptor *sampDesc = [[MTLSamplerDescriptor alloc] init];
  sampDesc.minFilter = MTLSamplerMinMagFilterNearest;
  sampDesc.magFilter = MTLSamplerMinMagFilterNearest;
  sampDesc.mipFilter = MTLSamplerMipFilterNearest;
  sampDesc.sAddressMode = MTLSamplerAddressModeRepeat;
  sampDesc.tAddressMode = MTLSamplerAddressModeRepeat;
  ctx->genericSampler = [ctx->device newSamplerStateWithDescriptor:sampDesc];

  fprintf(stderr, "[MetalRender] Generic pipeline created successfully (32-byte vertex: pos+color+uv+light)\n");

  // Create white fallback texture for untextured draws (solid fills)
  if (!ctx->boundTextures) {
    ctx->boundTextures = [[NSMutableDictionary alloc] init];
  }
  if (!ctx->boundTextures[@0]) {
    MTLTextureDescriptor *whiteDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                    width:1
                                   height:1
                                mipmapped:NO];
    whiteDesc.usage = MTLTextureUsageShaderRead;
    whiteDesc.storageMode = MTLStorageModeShared;
    id<MTLTexture> whiteTex = [ctx->device newTextureWithDescriptor:whiteDesc];
    uint8_t whitePixel[] = {255, 255, 255, 255};
    [whiteTex replaceRegion:MTLRegionMake2D(0, 0, 1, 1)
              mipmapLevel:0
                withBytes:whitePixel
              bytesPerRow:4];
    [whiteTex setLabel:@"WhiteFallback"];
    ctx->boundTextures[@0] = whiteTex;
    fprintf(stderr, "[MetalRender] Created white fallback texture (id=0) for GUI fills\n");
  }

  // Create UI depth state: no depth test or write (for 2D overlay rendering)
  if (!ctx->uiDepthState) {
    MTLDepthStencilDescriptor *uiDepthDesc = [[MTLDepthStencilDescriptor alloc] init];
    uiDepthDesc.depthCompareFunction = MTLCompareFunctionAlways;
    uiDepthDesc.depthWriteEnabled = NO;
    uiDepthDesc.label = @"UI Depth (disabled)";
    ctx->uiDepthState = [ctx->device newDepthStencilStateWithDescriptor:uiDepthDesc];
    fprintf(stderr, "[MetalRender] UI depth state created (no depth test)\n");
  }

  // Create sky depth state: depth test ON (compare less — terrain occludes celestial),
  // depth write OFF (celestial bodies don't write to depth buffer)
  if (!ctx->skyDepthState) {
    MTLDepthStencilDescriptor *skyDepthDesc = [[MTLDepthStencilDescriptor alloc] init];
    skyDepthDesc.depthCompareFunction = MTLCompareFunctionLessEqual;
    skyDepthDesc.depthWriteEnabled = NO;
    skyDepthDesc.label = @"Sky Depth (test on, write off)";
    ctx->skyDepthState = [ctx->device newDepthStencilStateWithDescriptor:skyDepthDesc];
    fprintf(stderr, "[MetalRender] Sky depth state created (test on, write off)\n");
  }

  // Create shadow depth state: depth test LEQUAL, depth write OFF
  // Shadows are flat quads projected onto surfaces — they need depth test to be
  // occluded by geometry in front, but should not write depth themselves.
  if (!ctx->shadowDepthState) {
    MTLDepthStencilDescriptor *shadowDepthDesc = [[MTLDepthStencilDescriptor alloc] init];
    shadowDepthDesc.depthCompareFunction = MTLCompareFunctionLessEqual;
    shadowDepthDesc.depthWriteEnabled = NO;
    shadowDepthDesc.label = @"Shadow Depth (test on, write off)";
    ctx->shadowDepthState = [ctx->device newDepthStencilStateWithDescriptor:shadowDepthDesc];
    fprintf(stderr, "[MetalRender] Shadow depth state created (test on, write off)\n");
  }

  return true;
}

static id<MTLBuffer> ensureBufferCapacity(id<MTLDevice> device,
                                          id<MTLBuffer> buffer,
                                          size_t byteSize) {
  if (buffer && [buffer length] >= byteSize) {
    return buffer;
  }
  return [device newBufferWithLength:byteSize
                             options:MTLResourceStorageModeShared];
}

// FEATURE_010: Helper to create or recreate the depth texture
static void ensureDepthTexture(MetalContext *ctx, uint32_t width, uint32_t height) {
  if (!ctx || !ctx->device || width == 0 || height == 0) return;

  // Skip if existing depth texture already matches the requested size
  if (ctx->depthTexture &&
      ctx->depthTexture.width == width &&
      ctx->depthTexture.height == height) {
    return;
  }

  // Release old depth texture
  ctx->depthTexture = nil;

  MTLTextureDescriptor *depthDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                  width:width
                                 height:height
                              mipmapped:NO];
  depthDesc.usage = MTLTextureUsageRenderTarget;
  depthDesc.storageMode = MTLStorageModePrivate;

  ctx->depthTexture = [ctx->device newTextureWithDescriptor:depthDesc];
  if (ctx->depthTexture) {
    [ctx->depthTexture setLabel:@"Depth Texture"];
    fprintf(stderr, "[MetalRender] ensureDepthTexture: Created %ux%u depth texture\n", width, height);
  } else {
    fprintf(stderr, "[MetalRender] ensureDepthTexture: FAILED to create %ux%u depth texture\n", width, height);
  }
}

// Helper function to create offscreen color texture for Option A fallback
static id<MTLTexture> createOffscreenColorTexture(
    MetalContext *ctx, uint32_t width, uint32_t height) {
  if (!ctx || !ctx->device) {
    fprintf(stderr, "[MetalRender] Cannot create offscreen texture: invalid context\n");
    return nil;
  }

  MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                  width:width
                                 height:height
                              mipmapped:NO];
  
  descriptor.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  descriptor.storageMode = MTLStorageModePrivate;
  
  id<MTLTexture> texture = [ctx->device newTextureWithDescriptor:descriptor];
  
  if (texture) {
    fprintf(stderr, "[MetalRender] Created offscreen color texture (%u x %u)\n", width, height);
  } else {
    fprintf(stderr, "[MetalRender] Failed to create offscreen color texture\n");
  }
  
  return texture;
}

static bool supportsMeshShaders(id<MTLDevice> device) {
  if (!device)
    return false;
#ifdef __MAC_OS_X_VERSION_MAX_ALLOWED
  if (@available(macOS 14.0, *)) {
    if ([device respondsToSelector:@selector(supportsFamily:)]) {
#ifdef MTLGPUFamilyApple7
      if ([device supportsFamily:MTLGPUFamilyApple7]) {
        return true;
      }
#endif
#ifdef MTLGPUFamilyApple8
      if ([device supportsFamily:MTLGPUFamilyApple8]) {
        return true;
      }
#endif
#ifdef MTLGPUFamilyMetal3
      if ([device supportsFamily:MTLGPUFamilyMetal3]) {
        return true;
      }
#endif
#ifdef MTLGPUFamilyMac2
      if ([device supportsFamily:MTLGPUFamilyMac2]) {
        return true;
      }
#endif
    }
  }
#endif
  return false;
}

static float clampFloat(float value, float lo, float hi) {
  if (value < lo)
    return lo;
  if (value > hi)
    return hi;
  return value;
}

#if METALRENDER_HAS_METALFX
static bool isTruthyFlag(const char *value) {
  if (!value) {
    return false;
  }
  std::string flag(value);
  auto start = flag.find_first_not_of(" \t\n\r");
  if (start == std::string::npos) {
    return false;
  }
  auto end = flag.find_last_not_of(" \t\n\r");
  std::string trimmed = flag.substr(start, end - start + 1);
  if (trimmed.empty()) {
    return false;
  }
  for (char &c : trimmed) {
    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  }
  return trimmed == "1" || trimmed == "true" || trimmed == "yes" ||
         trimmed == "on";
}

static bool isFalsyFlag(const char *value) {
  if (!value) {
    return false;
  }
  std::string flag(value);
  auto start = flag.find_first_not_of(" \t\n\r");
  if (start == std::string::npos) {
    return false;
  }
  auto end = flag.find_last_not_of(" \t\n\r");
  std::string trimmed = flag.substr(start, end - start + 1);
  if (trimmed.empty()) {
    return false;
  }
  for (char &c : trimmed) {
    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  }
  return trimmed == "0" || trimmed == "false" || trimmed == "no" ||
         trimmed == "off";
}

static bool supportsMetalFX(id<MTLDevice> device) {
  if (!device)
    return false;
  const char *envValue = getenv("METALRENDER_ENABLE_METALFX");
  static bool envLogged = false;
  const char *rawValue = envValue ? envValue : "(not set - enabled by default)";
  if (!envLogged) {
    fprintf(stderr, "[MetalRender] METALRENDER_ENABLE_METALFX=%s\n", rawValue);
    envLogged = true;
  }

  if (isFalsyFlag(envValue)) {
    fprintf(
        stderr,
        "[MetalRender] MetalFX explicitly disabled via environment variable\n");
    return false;
  }
  if (@available(macOS 13.0, *)) {
    bool deviceSupported =
        [MTLFXTemporalScalerDescriptor supportsDevice:device];
    if (deviceSupported) {
      fprintf(
          stderr,
          "[MetalRender] MetalFX support detected and enabled for device: %s\n",
          [[device name] UTF8String]);
    } else {
      fprintf(stderr, "[MetalRender] MetalFX not supported by device: %s\n",
              [[device name] UTF8String]);
    }
    return deviceSupported;
  }
  fprintf(stderr, "[MetalRender] MetalFX requires macOS 13.0 or later\n");
  return false;
}

static void destroyMetalFXResources(MetalContext *ctx) {
  if (!ctx)
    return;
  bool hadResources = ctx->metalFxScaler || ctx->metalFxColor ||
                      ctx->metalFxDepth || ctx->metalFxOutput;
  if (ctx->metalFxDestroyed && !hadResources) {
    return;
  }
  if (ctx->metalFxDestroyed && hadResources) {
    fprintf(stderr, "[MetalRender] destroyMetalFXResources called twice "
                    "without recreation; skipping\n");
    return;
  }
  ctx->metalFxDestroyed = true;
  fprintf(stderr, "[MetalRender] Destroying MetalFX resources (hadScaler=%s)\n",
          ctx->metalFxScaler ? "yes" : "no");
  ctx->metalFxInputWidth = 0;
  ctx->metalFxInputHeight = 0;
  ctx->metalFxOutputWidth = 0;
  ctx->metalFxOutputHeight = 0;
  ctx->metalFxResetHistory = false;
  if (ctx->metalFxScaler) {
    fprintf(stderr, "[MetalRender] Releasing metalFxScaler\n");
    ctx->metalFxScaler = nil;
  }
  if (ctx->metalFxColor) {
    fprintf(stderr, "[MetalRender] Releasing metalFxColor\n");
    ctx->metalFxColor = nil;
  }
  if (ctx->metalFxDepth) {
    fprintf(stderr, "[MetalRender] Releasing metalFxDepth\n");
    ctx->metalFxDepth = nil;
  }
  if (ctx->metalFxOutput) {
    fprintf(stderr, "[MetalRender] Releasing metalFxOutput\n");
    ctx->metalFxOutput = nil;
  }
  fprintf(stderr, "[MetalRender] MetalFX resources destroyed.\n");
}

static bool ensureMetalFXResources(MetalContext *ctx, uint32_t outputWidth,
                                   uint32_t outputHeight, float scale) {
  if (!ctx || !ctx->metalFxSupported || !ctx->metalFxEnabled || !ctx->device) {
    return false;
  }
  if (@available(macOS 13.0, *)) {
    outputWidth = std::max<uint32_t>(1, outputWidth);
    outputHeight = std::max<uint32_t>(1, outputHeight);
    float clampedScale = clampFloat(scale, 0.25F, 1.0F);
    uint32_t inputWidth = std::max<uint32_t>(
        1, static_cast<uint32_t>(std::lround(outputWidth * clampedScale)));
    uint32_t inputHeight = std::max<uint32_t>(
        1, static_cast<uint32_t>(std::lround(outputHeight * clampedScale)));

    if (ctx->metalFxScaler && ctx->metalFxInputWidth == inputWidth &&
        ctx->metalFxInputHeight == inputHeight &&
        ctx->metalFxOutputWidth == outputWidth &&
        ctx->metalFxOutputHeight == outputHeight && ctx->metalFxColor &&
        ctx->metalFxDepth && ctx->metalFxOutput) {
      ctx->metalFxDestroyed = false;
      ctx->metalFxScaler.inputContentWidth = inputWidth;
      ctx->metalFxScaler.inputContentHeight = inputHeight;
      ctx->metalFxScaler.colorTexture = ctx->metalFxColor;
      ctx->metalFxScaler.depthTexture = ctx->metalFxDepth;
      ctx->metalFxScaler.outputTexture = ctx->metalFxOutput;
      static uint64_t metalFxFrameCounter = 0;
      metalFxFrameCounter++;
      if (metalFxFrameCounter % 1000 == 0) {
        fprintf(stderr,
                "[MetalRender] MetalFX upscaling active: input=%ux%u "
                "output=%ux%u scale=%.3f (frame %llu)\n",
                inputWidth, inputHeight, outputWidth, outputHeight,
                clampedScale, metalFxFrameCounter);
      }
      return true;
    }

    destroyMetalFXResources(ctx);

    fprintf(stderr,
            "[MetalRender] Allocating MetalFX resources: output=%ux%u "
            "scale=%.3f input=%ux%u\n",
            outputWidth, outputHeight, clampedScale, inputWidth, inputHeight);

    MTLFXTemporalScalerDescriptor *descriptor =
        [[MTLFXTemporalScalerDescriptor alloc] init];
    descriptor.colorTextureFormat = MTLPixelFormatBGRA8Unorm;
    descriptor.depthTextureFormat = MTLPixelFormatDepth32Float;
    descriptor.motionTextureFormat = MTLPixelFormatInvalid;
    descriptor.outputTextureFormat = MTLPixelFormatBGRA8Unorm;
    descriptor.inputWidth = inputWidth;
    descriptor.inputHeight = inputHeight;
    descriptor.outputWidth = outputWidth;
    descriptor.outputHeight = outputHeight;
    descriptor.autoExposureEnabled = NO;
    descriptor.inputContentPropertiesEnabled = YES;
    descriptor.inputContentMinScale = 0.25F;
    descriptor.inputContentMaxScale = 1.0F;
    if (@available(macOS 14.4, *)) {
      if ([descriptor
              respondsToSelector:@selector(setReactiveMaskTextureEnabled:)]) {
        descriptor.reactiveMaskTextureEnabled = NO;
      }
    }

    id<MTLFXTemporalScaler> scaler =
        [descriptor newTemporalScalerWithDevice:ctx->device];
    if (!scaler) {
      fprintf(stderr,
              "[MetalRender] Failed to create MetalFX temporal scaler\n");
      return false;
    }

    ctx->metalFxScaler = scaler;
    ctx->metalFxInputWidth = inputWidth;
    ctx->metalFxInputHeight = inputHeight;
    ctx->metalFxOutputWidth = outputWidth;
    ctx->metalFxOutputHeight = outputHeight;
    ctx->metalFxResetHistory = true;
    fprintf(stderr,
            "[MetalRender] MetalFX resources successfully allocated and ready "
            "(input=%ux%u, output=%ux%u, "
            "scale=%.3f)\n",
            inputWidth, inputHeight, outputWidth, outputHeight, clampedScale);

    MTLPixelFormat colorFormat = scaler.colorTextureFormat;
    MTLPixelFormat depthFormat = scaler.depthTextureFormat;
    MTLPixelFormat outputFormat = scaler.outputTextureFormat;

    MTLTextureUsage colorUsage = scaler.colorTextureUsage |
                                 MTLTextureUsageRenderTarget |
                                 MTLTextureUsageShaderRead;
    MTLTextureUsage depthUsage = scaler.depthTextureUsage |
                                 MTLTextureUsageRenderTarget |
                                 MTLTextureUsageShaderRead;
    MTLTextureUsage outputUsage =
        scaler.outputTextureUsage | MTLTextureUsageRenderTarget |
        MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;

    MTLTextureDescriptor *colorDesc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:colorFormat
                                                           width:inputWidth
                                                          height:inputHeight
                                                       mipmapped:NO];
    colorDesc.storageMode = MTLStorageModePrivate;
    colorDesc.usage = colorUsage;
    ctx->metalFxColor = [ctx->device newTextureWithDescriptor:colorDesc];
    if (!ctx->metalFxColor) {
      fprintf(
          stderr,
          "[MetalRender] Failed to allocate MetalFX color texture (%u x %u)\n",
          inputWidth, inputHeight);
      destroyMetalFXResources(ctx);
      return false;
    }

    MTLTextureDescriptor *depthDesc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:depthFormat
                                                           width:inputWidth
                                                          height:inputHeight
                                                       mipmapped:NO];
    depthDesc.storageMode = MTLStorageModePrivate;
    depthDesc.usage = depthUsage;
    ctx->metalFxDepth = [ctx->device newTextureWithDescriptor:depthDesc];
    if (!ctx->metalFxDepth) {
      fprintf(
          stderr,
          "[MetalRender] Failed to allocate MetalFX depth texture (%u x %u)\n",
          inputWidth, inputHeight);
      destroyMetalFXResources(ctx);
      return false;
    }

    MTLTextureDescriptor *outputDesc =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:outputFormat
                                                           width:outputWidth
                                                          height:outputHeight
                                                       mipmapped:NO];
    outputDesc.storageMode = MTLStorageModePrivate;
    outputDesc.usage = outputUsage;
    ctx->metalFxOutput = [ctx->device newTextureWithDescriptor:outputDesc];
    if (!ctx->metalFxOutput) {
      fprintf(
          stderr,
          "[MetalRender] Failed to allocate MetalFX output texture (%u x %u)\n",
          outputWidth, outputHeight);
      destroyMetalFXResources(ctx);
      return false;
    }

    scaler.colorTexture = ctx->metalFxColor;
    scaler.depthTexture = ctx->metalFxDepth;
    scaler.motionTexture = nil;
    scaler.outputTexture = ctx->metalFxOutput;
    scaler.preExposure = 1.0F;
    scaler.motionVectorScaleX = 1.0F;
    scaler.motionVectorScaleY = 1.0F;
    scaler.depthReversed = NO;
    scaler.inputContentWidth = inputWidth;
    scaler.inputContentHeight = inputHeight;
    scaler.jitterOffsetX = ctx->temporalJitterX;
    scaler.jitterOffsetY = ctx->temporalJitterY;
    ctx->metalFxDestroyed = false;
    return true;
  }
  return false;
}
// FEATURE_004: CAMetalLayer Integration - Helper Functions
// These helper functions manage window presentation via CAMetalLayer

// Initialize CAMetalLayer for the given window
static bool initCAMetalLayer(MetalContext *ctx, NSWindow *window) {
  if (!ctx || !window) {
    fprintf(stderr, "[MetalRender] initCAMetalLayer: Invalid context or window\n");
    return false;
  }

  if (!ctx->device) {
    fprintf(stderr, "[MetalRender] initCAMetalLayer: Device not initialized\n");
    return false;
  }

  // Create CAMetalLayer instance
  CAMetalLayer *layer = [CAMetalLayer layer];
  if (!layer) {
    fprintf(stderr, "[MetalRender] initCAMetalLayer: Failed to create CAMetalLayer\n");
    return false;
  }

  // Configure the metal layer
  layer.device = ctx->device;
  layer.pixelFormat = MTLPixelFormatBGRA8Unorm;  // Required for screen rendering
  layer.opaque = YES;                              // Metal is the final opaque output
  
  // Set drawable size from window bounds
  NSRect windowFrame = [window frame];

  // Validate window dimensions (Metal API requires drawable size > 0)
  if (windowFrame.size.width <= 0 || windowFrame.size.height <= 0) {
    fprintf(stderr, "[MetalRender] initCAMetalLayer: Invalid window dimensions (%.0f x %.0f)\n", 
            windowFrame.size.width, windowFrame.size.height);
    layer = nil;
    return false;
  }

  CGSize drawableSize = CGSizeMake(windowFrame.size.width, windowFrame.size.height);
  layer.drawableSize = drawableSize;
  
  // Store current window dimensions in context
  ctx->currentWidth = static_cast<uint32_t>(drawableSize.width);
  ctx->currentHeight = static_cast<uint32_t>(drawableSize.height);

  // Add layer to window's content view
  NSView *contentView = [window contentView];
  if (!contentView) {
    fprintf(stderr, "[MetalRender] initCAMetalLayer: No content view for window\n");
    layer = nil;
    return false;
  }

  // Create a separate overlay NSView to host the Metal layer ON TOP of GL content.
  // Using addSublayer: puts it behind the NSOpenGLContext framebuffer (invisible).
  // A subview sits above the parent view's GL rendering.
  NSView *overlay = [[NSView alloc] initWithFrame:contentView.bounds];
  overlay.wantsLayer = YES;
  // Make the overlay non-opaque so GL UI content shows through transparent areas
  overlay.layerContentsRedrawPolicy = NSViewLayerContentsRedrawOnSetNeedsDisplay;
  layer.frame = overlay.bounds;
  layer.contentsScale = contentView.window.backingScaleFactor;
  [overlay setLayer:layer];
  overlay.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
  [contentView addSubview:overlay];

  // Store in context for later use
  ctx->metalLayer = layer;
  ctx->metalOverlay = overlay;

  fprintf(stderr, "[MetalRender] CAMetalLayer added via overlay NSView on top of GL content\n");
  fprintf(stderr, "[MetalRender] Window size: %ux%u\n", ctx->currentWidth, ctx->currentHeight);
  
  return true;
}

// Get the next drawable from the metal layer
static id<CAMetalDrawable> getMetalDrawable(MetalContext *ctx) {
  if (!ctx || !ctx->metalLayer) {
    return nil;
  }

  id<CAMetalDrawable> drawable = [ctx->metalLayer nextDrawable];
  if (!drawable) {
    // This can happen if the window is minimized or not visible
    fprintf(stderr, "[MetalRender] getMetalDrawable: Failed to acquire drawable (window may not be visible)\n");
    return nil;
  }

  fprintf(stderr, "[MetalRender] Successfully acquired drawable from CAMetalLayer\n");
  return drawable;
}

// Present the rendered frame to the screen
static void presentFrame(MetalContext *ctx, id<CAMetalDrawable> drawable) {
  if (!ctx || !drawable || !ctx->currentCommandBuffer) {
    if (!ctx) {
      fprintf(stderr, "[MetalRender] presentFrame: Context is nil\n");
    } else if (!drawable) {
      fprintf(stderr, "[MetalRender] presentFrame: Drawable is nil\n");
    } else {
      fprintf(stderr, "[MetalRender] presentFrame: Command buffer is nil\n");
    }
    return;
  }

  // Schedule drawable for presentation
  [ctx->currentCommandBuffer presentDrawable:drawable];
  
  // Commit the command buffer to execute all recorded commands and present
  [ctx->currentCommandBuffer commit];
  
  fprintf(stderr, "[MetalRender] Frame presented to screen successfully\n");
}

// Update drawable size when window resizes
static void onWindowResize(MetalContext *ctx, uint32_t newWidth, uint32_t newHeight) {
  if (!ctx) {
    fprintf(stderr, "[MetalRender] onWindowResize: Context is nil\n");
    return;
  }

  if (!ctx->metalLayer) {
    fprintf(stderr, "[MetalRender] onWindowResize: Metal layer not initialized\n");
    return;
  }

  // Validate dimensions
  if (newWidth == 0 || newHeight == 0) {
    fprintf(stderr, "[MetalRender] onWindowResize: Invalid dimensions (%u, %u)\n", newWidth, newHeight);
    return;
  }

  // Update drawable size in the metal layer
  ctx->metalLayer.drawableSize = CGSizeMake(newWidth, newHeight);
  
  // Store updated dimensions in context
  ctx->currentWidth = newWidth;
  ctx->currentHeight = newHeight;

  fprintf(stderr, "[MetalRender] Window resized to %ux%u, drawable size updated\n", newWidth, newHeight);
}

#else
static bool supportsMetalFX(id<MTLDevice>) { return false; }
static void destroyMetalFXResources(MetalContext *) {}
static bool ensureMetalFXResources(MetalContext *, uint32_t, uint32_t, float) {
  return false;
}
#endif

// ============================================================================
// FEATURE_005: Test Triangle Helper Functions
// ============================================================================

/**
 * Creates test triangle geometry for pipeline validation.
 * Defines a single triangle with 3 vertices in normalized device coordinates.
 * 
 * Triangle vertices:
 *   - Vertex 0: (-0.5, -0.5, 1.0) - Bottom-left (red-ish)
 *   - Vertex 1: (0.5, -0.5, 1.0)  - Bottom-right (green-ish)
 *   - Vertex 2: (0.0, 0.5, 1.0)   - Top (blue-ish)
 * 
 * Index buffer: [0, 1, 2] - Standard triangle indices
 */
static void createTestTriangle(MetalContext *ctx) {
  fprintf(stderr, "[MetalRender] [createTestTriangle] ENTRY\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] Context=%p\n", ctx);
  
  if (!ctx) {
    fprintf(stderr, "[MetalRender] [createTestTriangle] ERROR: ctx is NULL\n");
    return;
  }
  
  fprintf(stderr, "[MetalRender] [createTestTriangle] Device=%p\n", ctx->device);
  fprintf(stderr, "[MetalRender] [createTestTriangle] testTriangleCreated=%s\n", ctx->testTriangleCreated ? "true" : "false");
  
  if (!ctx->device || ctx->testTriangleCreated) {
    if (!ctx->device) {
      fprintf(stderr, "[MetalRender] [createTestTriangle] ERROR: Device is NULL\n");
    }
    if (ctx->testTriangleCreated) {
      fprintf(stderr, "[MetalRender] [createTestTriangle] Triangle already created, skipping\n");
    }
    return;
  }
  
  fprintf(stderr, "[MetalRender] [createTestTriangle] FEATURE_005: Creating test triangle geometry...\n");
  
  // STEP 1: Define vertex data (3 vertices, float x, y, z = 12 bytes each)
  struct Vertex {
    float x, y, z;
  };
  
  fprintf(stderr, "[MetalRender] [createTestTriangle] Step 1: Defining vertex data\n");
  
  Vertex vertices[3] = {
    {-0.5f, -0.5f, 1.0f},  // Vertex 0: Bottom-left
    {0.5f, -0.5f, 1.0f},   // Vertex 1: Bottom-right
    {0.0f, 0.5f, 1.0f}     // Vertex 2: Top
  };
  
  fprintf(stderr, "[MetalRender] [createTestTriangle] Vertex array: size=%zu bytes\n", sizeof(vertices));
  fprintf(stderr, "[MetalRender] [createTestTriangle] Vertex[0]: (-0.5, -0.5, 1.0)\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] Vertex[1]: (0.5, -0.5, 1.0)\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] Vertex[2]: (0.0, 0.5, 1.0)\n");
  
  // STEP 2: Create Metal buffer for vertices (36 bytes total)
  fprintf(stderr, "[MetalRender] [createTestTriangle] Step 2: Creating vertex buffer\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] Device newBufferWithBytes: device=%p, length=%zu\n", ctx->device, sizeof(vertices));
  
  ctx->testVertexBuffer = [ctx->device newBufferWithBytes:vertices
                                                    length:sizeof(vertices)
                                                   options:MTLResourceStorageModeShared];
  
  fprintf(stderr, "[MetalRender] [createTestTriangle] Vertex buffer created: %p\n", ctx->testVertexBuffer);
  
  if (!ctx->testVertexBuffer) {
    fprintf(stderr, "[MetalRender] [createTestTriangle] ERROR: Failed to create test vertex buffer (returned NULL)\n");
    fprintf(stderr, "[MetalRender] [createTestTriangle] Device=%p, size=%zu\n", ctx->device, sizeof(vertices));
    return;
  }
  
  [ctx->testVertexBuffer setLabel:@"Test Triangle Vertex Buffer"];
  fprintf(stderr, "[MetalRender] [createTestTriangle] ✓ Vertex buffer created and labeled\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] Vertex buffer address: %p (size=%zu bytes)\n", ctx->testVertexBuffer, sizeof(vertices));
  
  // STEP 3: Define index data (3 uint32_t indices)
  fprintf(stderr, "[MetalRender] [createTestTriangle] Step 3: Defining index data\n");
  
  uint32_t indices[3] = {0, 1, 2};
  
  fprintf(stderr, "[MetalRender] [createTestTriangle] Index array: size=%zu bytes, count=3\n", sizeof(indices));
  fprintf(stderr, "[MetalRender] [createTestTriangle] Indices: [0, 1, 2]\n");
  
  // STEP 4: Create Metal buffer for indices (12 bytes total)
  fprintf(stderr, "[MetalRender] [createTestTriangle] Step 4: Creating index buffer\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] Device newBufferWithBytes: device=%p, length=%zu\n", ctx->device, sizeof(indices));
  
  ctx->testIndexBuffer = [ctx->device newBufferWithBytes:indices
                                                  length:sizeof(indices)
                                                 options:MTLResourceStorageModeShared];
  
  fprintf(stderr, "[MetalRender] [createTestTriangle] Index buffer created: %p\n", ctx->testIndexBuffer);
  
  if (!ctx->testIndexBuffer) {
    fprintf(stderr, "[MetalRender] [createTestTriangle] ERROR: Failed to create test index buffer (returned NULL)\n");
    fprintf(stderr, "[MetalRender] [createTestTriangle] Device=%p, size=%zu\n", ctx->device, sizeof(indices));
    fprintf(stderr, "[MetalRender] [createTestTriangle] Cleaning up: nullifying vertex buffer %p\n", ctx->testVertexBuffer);
    ctx->testVertexBuffer = nil;
    return;
  }
  
  [ctx->testIndexBuffer setLabel:@"Test Triangle Index Buffer"];
  fprintf(stderr, "[MetalRender] [createTestTriangle] ✓ Index buffer created and labeled\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] Index buffer address: %p (size=%zu bytes, count=3)\n", ctx->testIndexBuffer, sizeof(indices));
  
  // STEP 5: Verify indirect args buffer exists
  fprintf(stderr, "[MetalRender] [createTestTriangle] Step 5: Verifying indirect args buffer\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] Indirect args buffer: %p\n", ctx->indirectArgs);
  
  if (!ctx->indirectArgs) {
    fprintf(stderr, "[MetalRender] [createTestTriangle] WARNING: Indirect args buffer is NULL\n");
  }
  
  // STEP 6: Mark as created and log success
  ctx->testTriangleCreated = true;
  fprintf(stderr, "[MetalRender] [createTestTriangle] Step 6: Marking test triangle as created\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] testTriangleCreated=%s\n", ctx->testTriangleCreated ? "true" : "false");
  
  fprintf(stderr, "[MetalRender] [createTestTriangle] SUCCESS: Test triangle geometry created!\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle] Resources allocated:\n");
  fprintf(stderr, "[MetalRender] [createTestTriangle]   - testVertexBuffer: %p (36 bytes)\n", ctx->testVertexBuffer);
  fprintf(stderr, "[MetalRender] [createTestTriangle]   - testIndexBuffer: %p (12 bytes)\n", ctx->testIndexBuffer);
  fprintf(stderr, "[MetalRender] [createTestTriangle]   - indirectArgs: %p\n", ctx->indirectArgs);
  fprintf(stderr, "[MetalRender] [createTestTriangle] EXIT\n");
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nIsAvailable(JNIEnv *, jclass) {
  id<MTLDevice> device = MTLCreateSystemDefaultDevice();
  if (!device) {
    return JNI_FALSE;
  }
  device = nil;
  return JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_com_metalrender_nativebridge_NativeBridge_nInit(
    JNIEnv *env, jclass, jint width, jint height, jfloat scale) {
  (void)width;
  (void)height;
  (void)scale;
  (void)env;
  std::lock_guard<std::mutex> lock(gMutex);
  id<MTLDevice> device = MTLCreateSystemDefaultDevice();
  if (!device) {
    return 0;
  }
  MetalContext *ctx = new MetalContext();
  ctx->device = device;
  ctx->graphicsQueue = [device newCommandQueue];
  ctx->computeQueue = [device newCommandQueue];
  ctx->library = createLibraryFromSource(device, @"MetalRenderOcclusion");
  if (ctx->library) {
    ensureOcclusionPipeline(ctx);
  }
  ctx->persistentBuffer =
      [device newBufferWithLength:ctx->persistentCapacity
                          options:MTLResourceStorageModeShared];
  // Indexed indirect args: MTLDrawIndexedPrimitivesIndirectArguments (5 x uint32 = 20 bytes)
  ctx->indirectArgs = [device
      newBufferWithLength:ctx->maxIndirectCommands * sizeof(uint32_t) * 5
                  options:MTLResourceStorageModeShared];
  // Per-draw data buffer for chunk origins (16 bytes per draw)
  ctx->drawDataBuffer = [device
      newBufferWithLength:ctx->maxIndirectCommands * sizeof(float) * 4
                  options:MTLResourceStorageModeShared];
  // Zero-init draw data buffer to prevent ghost geometry from stale data
  if (ctx->drawDataBuffer) {
    memset([ctx->drawDataBuffer contents], 0, [ctx->drawDataBuffer length]);
  }

  // Create shared quad index buffer: for each quad (4 verts), emit 6 indices
  // Pattern: 0,1,2, 2,3,0, 4,5,6, 6,7,4, ...
  // Only needs to cover the LARGEST SINGLE DRAW CALL (not total buffer).
  // A 16x16x16 section has at most ~100K verts; cap at 262144 for safety.
  uint32_t maxVertices = 262144;  // 64K quads, more than any single section
  uint32_t maxQuads = maxVertices / 4;
  uint32_t maxIndices = maxQuads * 6;
  ctx->quadIndexBuffer = [device
      newBufferWithLength:maxIndices * sizeof(uint32_t)
                  options:MTLResourceStorageModeShared];
  if (ctx->quadIndexBuffer) {
    uint32_t *indices = reinterpret_cast<uint32_t *>([ctx->quadIndexBuffer contents]);
    for (uint32_t q = 0; q < maxQuads; ++q) {
      uint32_t base = q * 4;
      uint32_t i = q * 6;
      indices[i + 0] = base + 0;
      indices[i + 1] = base + 1;
      indices[i + 2] = base + 2;
      indices[i + 3] = base + 2;
      indices[i + 4] = base + 3;
      indices[i + 5] = base + 0;
    }
    ctx->quadIndexMaxQuads = maxQuads;
    [ctx->quadIndexBuffer setLabel:@"Quad Index Buffer"];
    fprintf(stderr, "[MetalRender] Created quad index buffer: %u quads, %u indices\n", maxQuads, maxIndices);
  }
  // Create 1x1 magenta fallback texture — bound when atlas is nil so we SEE the problem
  {
    MTLTextureDescriptor *fbDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:1
                                    height:1
                                 mipmapped:NO];
    fbDesc.usage = MTLTextureUsageShaderRead;
    fbDesc.storageMode = MTLStorageModeShared;
    ctx->fallbackTexture = [device newTextureWithDescriptor:fbDesc];
    if (ctx->fallbackTexture) {
      // Magenta BGRA: B=255, G=0, R=255, A=255
      uint8_t magenta[4] = {255, 0, 255, 255};
      [ctx->fallbackTexture replaceRegion:MTLRegionMake2D(0, 0, 1, 1)
                              mipmapLevel:0
                                withBytes:magenta
                              bytesPerRow:4];
      [ctx->fallbackTexture setLabel:@"Fallback Magenta 1x1"];
      fprintf(stderr, "[MetalRender] Created 1x1 magenta fallback texture\n");
    }
  }

  NSString *name = [device name];
  if (name)
    ctx->deviceName = [name UTF8String];
  ctx->meshShadersSupported = supportsMeshShaders(device);
  fprintf(stderr, "[MetalRender] ==========================================\n");
  fprintf(stderr, "[MetalRender] NATIVE VERSION: %s\n", NATIVE_VERSION);
  fprintf(stderr, "[MetalRender] Device: %s\n", ctx->deviceName.c_str());
  fprintf(stderr, "[MetalRender] Persistent buffer: %luMB\n", (unsigned long)(ctx->persistentCapacity / 1024 / 1024));
  fprintf(stderr, "[MetalRender] ==========================================\n");
  return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nResize(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jfloat scale) {
  MetalContext *ctx = getContext(handle);
  if (!ctx) return;

  uint32_t newWidth = (uint32_t)width;
  uint32_t newHeight = (uint32_t)height;
  if (newWidth == 0 || newHeight == 0) return;

  ctx->currentWidth = newWidth;
  ctx->currentHeight = newHeight;

  // FEATURE_010: Recreate depth texture on resize
  ensureDepthTexture(ctx, newWidth, newHeight);

  // Invalidate offscreen color texture so it's recreated at new size
  ctx->offscreenColorTexture = nil;

  if (ctx->metalLayer) {
    NSView *overlay = ctx->metalOverlay;  // capture for block
    dispatch_async(dispatch_get_main_queue(), ^{
      ctx->metalLayer.drawableSize = CGSizeMake(newWidth, newHeight);
      // Update overlay view frame to match parent bounds
      if (overlay && overlay.superview) {
        overlay.frame = overlay.superview.bounds;
        ctx->metalLayer.frame = overlay.bounds;
      }
    });
    fprintf(stderr, "[MetalRender] nResize: updated to %ux%u (scale=%.2f)\n", newWidth, newHeight, scale);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginFrame(
    JNIEnv *env, jclass, jlong handle, jfloatArray viewProjArray,
    jfloatArray clearColorArray, jfloat nearPlane, jfloat farPlane) {
  ++gFrameCount;
  
  MetalContext *ctx = getContext(handle);
  if (!ctx) return;
  if (!ctx->graphicsQueue || !ctx->persistentBuffer || !ctx->indirectArgs) return;

  // Mark that the first offscreen pass this frame should CLEAR the atlas
  ctx->offscreenNeedsClear = true;

  // FRAME SYNC: Wait for previous frame's GPU work to complete
  // before overwriting shared buffers (indirectArgs, drawDataBuffer).
  // Without this, the GPU from frame N may still be reading those buffers
  // when frame N+1 zeroes them, causing intermittent missing geometry.
  if (ctx->previousCommandBuffer) {
    [ctx->previousCommandBuffer waitUntilCompleted];
    ctx->previousCommandBuffer = nil;
  }

  // Store view-projection matrix
  if (viewProjArray && env->GetArrayLength(viewProjArray) >= 16) {
    env->GetFloatArrayRegion(viewProjArray, 0, 16, ctx->viewProj);
    ctx->hasViewProj = true;
  }

  // Get drawable from metalLayer
  id<CAMetalDrawable> drawable = nil;
  if (ctx->metalLayer) {
    drawable = [ctx->metalLayer nextDrawable];
  }
  ctx->currentDrawable = drawable;

  uint32_t width = ctx->currentWidth > 0 ? ctx->currentWidth : 1920;
  uint32_t height = ctx->currentHeight > 0 ? ctx->currentHeight : 1080;
  
  id<MTLTexture> renderTarget = nil;
  if (drawable) {
    renderTarget = drawable.texture;
    width = (uint32_t)renderTarget.width;
    height = (uint32_t)renderTarget.height;
    ctx->currentWidth = width;
    ctx->currentHeight = height;
  } else {
    if (!ctx->offscreenColorTexture) {
      ctx->offscreenColorTexture = createOffscreenColorTexture(ctx, width, height);
    }
    renderTarget = ctx->offscreenColorTexture;
  }

  if (!renderTarget) return;

  ctx->currentCommandBuffer = [ctx->graphicsQueue commandBuffer];
  if (!ctx->currentCommandBuffer) return;
  [ctx->currentCommandBuffer setLabel:@"Frame Command Buffer"];

  MTLRenderPassDescriptor *renderPass = [MTLRenderPassDescriptor renderPassDescriptor];
  if (!renderPass) { ctx->currentCommandBuffer = nil; return; }

  renderPass.colorAttachments[0].texture = renderTarget;
  renderPass.colorAttachments[0].loadAction = MTLLoadActionClear;
  renderPass.colorAttachments[0].storeAction = MTLStoreActionStore;

  // Set clear color from Java parameter
  if (clearColorArray && env->GetArrayLength(clearColorArray) >= 4) {
    jfloat clearColor[4] = {0.0F, 0.0F, 0.0F, 1.0F};
    env->GetFloatArrayRegion(clearColorArray, 0, 4, clearColor);
    renderPass.colorAttachments[0].clearColor = MTLClearColorMake(
        (double)clearColor[0], (double)clearColor[1], (double)clearColor[2],
        (double)clearColor[3]);
  } else {
    // Sky blue clear — Metal handles the sky since GL sky is cancelled
    renderPass.colorAttachments[0].clearColor =
        MTLClearColorMake(0.392, 0.584, 0.929, 1.0);
  }

  // Setup depth attachment if available
  if (ctx->depthTexture) {
    renderPass.depthAttachment.texture = ctx->depthTexture;
    renderPass.depthAttachment.loadAction = MTLLoadActionClear;
    renderPass.depthAttachment.clearDepth = 1.0;
    renderPass.depthAttachment.storeAction = MTLStoreActionStore;
  }

  // Create render command encoder
  ctx->currentRenderEncoder = [ctx->currentCommandBuffer
      renderCommandEncoderWithDescriptor:renderPass];
  if (!ctx->currentRenderEncoder) {
    ctx->currentCommandBuffer = nil;
    return;
  }
  [ctx->currentRenderEncoder setLabel:@"Frame Render Encoder"];

  // Setup viewport and scissor
  MTLViewport viewport = {0.0, 0.0, (double)width, (double)height, 0.0, 1.0};
  [ctx->currentRenderEncoder setViewport:viewport];
  MTLScissorRect scissor = {0, 0, width, height};
  [ctx->currentRenderEncoder setScissorRect:scissor];

  if (shouldLog()) {
    NSLog(@"[MetalRender] [nBeginFrame] frame %llu: %ux%u drawable=%p encoder=%p depth=%p",
            gFrameCount, width, height, drawable, ctx->currentRenderEncoder, ctx->depthTexture);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawTerrain(JNIEnv *, jclass,
                                                            jlong handle, jint passIndex) {
  MetalContext *ctx = getContext(handle);

  // ============================================================================
  // STEP 1: VALIDATE CONTEXT AND REQUIRED RESOURCES
  // ============================================================================
  if (!ctx || !ctx->currentRenderEncoder || !ctx->currentCommandBuffer || !ctx->device) {
    fprintf(stderr, "[MetalRender] [nDrawTerrain] ERROR: Missing context/encoder/cmdBuf/device\n");
    return;
  }

  const char *testModeEnv = getenv("TEST_TRIANGLE");
  bool testModeEnabled = testModeEnv != nullptr && strcmp(testModeEnv, "true") == 0;

  // ============================================================================
  // STEP 2: CREATE / SELECT PIPELINE
  // ============================================================================

  if (testModeEnabled) {
    // TEST MODE: Use simple white triangle pipeline
    if (!ctx->testPipeline) {
      NSString *shaderSrc = @"#include <metal_stdlib>\n"
                            "using namespace metal;\n"
                            "struct VertexOut {\n"
                            "  float4 position [[position]];\n"
                            "};\n"
                            "vertex VertexOut testVertex(uint vid [[vertex_id]],\n"
                            "                             constant float3 *positions [[buffer(0)]]) {\n"
                            "  VertexOut out;\n"
                            "  out.position = float4(positions[vid], 1.0);\n"
                            "  return out;\n"
                            "}\n"
                            "fragment float4 testFragment() {\n"
                            "  return float4(1.0, 1.0, 1.0, 1.0);\n"
                            "}\n";
      NSError *error = nil;
      id<MTLLibrary> testLib = [ctx->device newLibraryWithSource:shaderSrc options:nil error:&error];
      if (!testLib) {
        fprintf(stderr, "[MetalRender] [nDrawTerrain] ERROR: test shader compile: %s\n",
                error ? [[error localizedDescription] UTF8String] : "unknown");
        return;
      }
      MTLRenderPipelineDescriptor *desc = [[MTLRenderPipelineDescriptor alloc] init];
      desc.vertexFunction = [testLib newFunctionWithName:@"testVertex"];
      desc.fragmentFunction = [testLib newFunctionWithName:@"testFragment"];
      desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      ctx->testPipeline = [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
      if (!ctx->testPipeline) {
        fprintf(stderr, "[MetalRender] [nDrawTerrain] ERROR: test pipeline: %s\n",
                error ? [[error localizedDescription] UTF8String] : "unknown");
        return;
      }
    }
    [ctx->currentRenderEncoder setRenderPipelineState:ctx->testPipeline];
  } else {
    // FEATURE_012: PRODUCTION TERRAIN PIPELINE
    if (!ctx->terrainPipeline) {
      fprintf(stderr, "[MetalRender] [FEATURE_012] Creating production terrain pipeline...\n");
      NSError *error = nil;
      NSString *src = [NSString stringWithUTF8String:kTerrainShaderSource];
      id<MTLLibrary> terrainLib = [ctx->device newLibraryWithSource:src options:nil error:&error];
      if (!terrainLib) {
        fprintf(stderr, "[MetalRender] [FEATURE_012] ERROR: terrain shader compile: %s\n",
                error ? [[error localizedDescription] UTF8String] : "unknown");
        return;
      }
      id<MTLFunction> vertFunc = [terrainLib newFunctionWithName:@"terrain_vertex"];
      id<MTLFunction> fragFunc = [terrainLib newFunctionWithName:@"terrain_fragment"];
      if (!vertFunc || !fragFunc) {
        fprintf(stderr, "[MetalRender] [FEATURE_012] ERROR: shader functions not found\n");
        return;
      }

      MTLRenderPipelineDescriptor *desc = [[MTLRenderPipelineDescriptor alloc] init];
      desc.vertexFunction = vertFunc;
      desc.fragmentFunction = fragFunc;
      desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
      // Enable depth testing
      desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
      // Enable alpha blending for vertex-color debug fallback
      desc.colorAttachments[0].blendingEnabled = YES;
      desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
      desc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
      desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
      desc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
      desc.label = @"Terrain Pipeline (F012)";

      ctx->terrainPipeline = [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
      if (!ctx->terrainPipeline) {
        fprintf(stderr, "[MetalRender] [FEATURE_012] ERROR: terrain pipeline: %s\n",
                error ? [[error localizedDescription] UTF8String] : "unknown");
        return;
      }

      // Create depth stencil state: less-than, write enabled
      MTLDepthStencilDescriptor *depthDesc = [[MTLDepthStencilDescriptor alloc] init];
      depthDesc.depthCompareFunction = MTLCompareFunctionLess;
      depthDesc.depthWriteEnabled = YES;
      depthDesc.label = @"Terrain Depth State";
      ctx->terrainDepthState = [ctx->device newDepthStencilStateWithDescriptor:depthDesc];

      fprintf(stderr, "[MetalRender] [FEATURE_012] ✅ Production terrain pipeline + depth state created\n");
    }

    [ctx->currentRenderEncoder setRenderPipelineState:ctx->terrainPipeline];

    // Set depth stencil state
    if (ctx->terrainDepthState) {
      [ctx->currentRenderEncoder setDepthStencilState:ctx->terrainDepthState];
    }

    // Sodium/OpenGL uses counter-clockwise winding for front faces.
    [ctx->currentRenderEncoder setFrontFacingWinding:MTLWindingCounterClockwise];
    [ctx->currentRenderEncoder setCullMode:MTLCullModeBack];
  }

  // ============================================================================
  // STEP 3: CREATE AND BIND UNIFORM BUFFER
  // ============================================================================
  struct FrameUniforms {
    float viewProj[16];
    float screenDim[2];
    float time;
    float debugMode;  // 0=normal, 1=UV viz, 2=vertex color, 3=texture only
    float skyBrightness; // 0.0 = night, 1.0 = full day
  } uniforms;
  memcpy(uniforms.viewProj, ctx->viewProj, sizeof(float) * 16);
  uniforms.screenDim[0] = (float)(ctx->currentWidth > 0 ? ctx->currentWidth : 1920);
  uniforms.screenDim[1] = (float)(ctx->currentHeight > 0 ? ctx->currentHeight : 1080);
  uniforms.time = 0.0f;
  uniforms.skyBrightness = ctx->skyBrightness;
  // Debug mode: 0=normal, 1=UV viz, 2=vertex color, 3=texture only, 4=flat white
  const char* debugModeEnv = getenv("METAL_DEBUG_MODE");
  uniforms.debugMode = debugModeEnv ? (float)atoi(debugModeEnv) : 0.0f;

  id<MTLBuffer> uniformBuffer = [ctx->device newBufferWithBytes:&uniforms
                                                         length:sizeof(uniforms)
                                                        options:MTLResourceStorageModeShared];
  if (!uniformBuffer) {
    fprintf(stderr, "[MetalRender] [nDrawTerrain] ERROR: failed to create uniform buffer\n");
    return;
  }
  [ctx->currentRenderEncoder setVertexBuffer:uniformBuffer offset:0 atIndex:2];
  [ctx->currentRenderEncoder setFragmentBuffer:uniformBuffer offset:0 atIndex:2];

  // ============================================================================
  // STEP 4: BIND TEXTURE ATLAS AND SAMPLER
  // ============================================================================
  if (ctx->atlasTexture) {
    [ctx->currentRenderEncoder setFragmentTexture:ctx->atlasTexture atIndex:0];
    if (shouldLog()) {
      NSLog(@"[MetalRender] [nDrawTerrain] Atlas bound: %lux%lu, debugMode=%.0f",
            (unsigned long)ctx->atlasTexture.width,
            (unsigned long)ctx->atlasTexture.height,
            uniforms.debugMode);
    }
  } else if (ctx->fallbackTexture) {
    [ctx->currentRenderEncoder setFragmentTexture:ctx->fallbackTexture atIndex:0];
    if (shouldLog()) {
      NSLog(@"[MetalRender] [nDrawTerrain] WARNING: No atlas! Using magenta fallback");
    }
  } else {
    if (shouldLog()) {
      NSLog(@"[MetalRender] [nDrawTerrain] WARNING: No atlas AND no fallback texture!");
    }
  }
  if (ctx->atlasSampler) {
    [ctx->currentRenderEncoder setFragmentSamplerState:ctx->atlasSampler atIndex:0];
  }

  // ============================================================================
  // STEP 5: BIND VERTEX DATA AND DRAW
  // ============================================================================
  if (testModeEnabled) {
    // Test triangle path
    if (!ctx->testTriangleCreated) {
      createTestTriangle(ctx);
    }
    if (ctx->testTriangleCreated && ctx->testVertexBuffer && ctx->testIndexBuffer) {
      [ctx->currentRenderEncoder setVertexBuffer:ctx->testVertexBuffer offset:0 atIndex:0];
      [ctx->currentRenderEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                            indexCount:3
                                             indexType:MTLIndexTypeUInt32
                                           indexBuffer:ctx->testIndexBuffer
                                     indexBufferOffset:0];
      ctx->currentIndexBuffer = ctx->testIndexBuffer;
    }
  } else {
    // FEATURE_012: Production terrain rendering
    // Bind persistent buffer (contains all chunk vertex data) at buffer(0)
    if (ctx->persistentBuffer) {
      [ctx->currentRenderEncoder setVertexBuffer:ctx->persistentBuffer offset:0 atIndex:0];
    }
    // Bind per-draw data buffer (chunk origins) at buffer(1)
    if (ctx->drawDataBuffer) {
      [ctx->currentRenderEncoder setVertexBuffer:ctx->drawDataBuffer offset:0 atIndex:1];
    }

    if (shouldLog()) {
      NSLog(@"[MetalRender] [nDrawTerrain] FEATURE_012: %u queued draws, atlas=%s, persistent=%p (%luMB), drawData=%p",
              ctx->currentIndirectCount,
              ctx->atlasTexture ? "YES" : "no",
              ctx->persistentBuffer,
              ctx->persistentBuffer ? (unsigned long)([ctx->persistentBuffer length] / 1024 / 1024) : 0,
              ctx->drawDataBuffer);
      
      // DIAGNOSTIC: Dump first draw command and first few vertices to verify data
      if (ctx->indirectArgs && ctx->currentIndirectCount > 0) {
        struct IndexedIndirectArgs {
          uint32_t indexCount;
          uint32_t instanceCount;
          uint32_t indexStart;
          int32_t  baseVertex;
          uint32_t baseInstance;
        };
        IndexedIndirectArgs *args = reinterpret_cast<IndexedIndirectArgs *>([ctx->indirectArgs contents]);
        NSLog(@"[MetalRender] [DIAG-DRAW0] indexCount=%u instanceCount=%u indexStart=%u baseVertex=%d baseInstance=%u",
              args[0].indexCount, args[0].instanceCount, args[0].indexStart, args[0].baseVertex, args[0].baseInstance);
        
        // Dump first 4 vertices' raw data with decoded UV coordinates
        if (ctx->persistentBuffer && args[0].baseVertex >= 0) {
          uint32_t *vdata = reinterpret_cast<uint32_t *>([ctx->persistentBuffer contents]);
          uint32_t startVert = static_cast<uint32_t>(args[0].baseVertex);
          for (int v = 0; v < 4 && v < (int)(args[0].indexCount); v++) {
            uint32_t idx = (startVert + v) * 5;
            uint32_t texPacked = vdata[idx+3];
            uint32_t uRaw = texPacked & 0xFFFFu;
            uint32_t vRaw = (texPacked >> 16u) & 0xFFFFu;
            float u = float(uRaw & 0x7FFFu) / 32768.0f;
            float vv = float(vRaw & 0x7FFFu) / 32768.0f;
            NSLog(@"[MetalRender] [DIAG-VERT%d] posHi=0x%08X posLo=0x%08X color=0x%08X tex=0x%08X light=0x%08X  UV=(%.6f, %.6f) uRaw=%u vRaw=%u",
                  v, vdata[idx], vdata[idx+1], vdata[idx+2], vdata[idx+3], vdata[idx+4],
                  u, vv, uRaw, vRaw);
          }
        }
        
        // Dump first DrawData (chunk origin)
        if (ctx->drawDataBuffer) {
          struct DrawData { float x, y, z, pad; };
          DrawData *dd = reinterpret_cast<DrawData *>([ctx->drawDataBuffer contents]);
          NSLog(@"[MetalRender] [DIAG-ORIGIN0] x=%.1f y=%.1f z=%.1f", dd[0].x, dd[0].y, dd[0].z);
        }
      }
    }
  }

  // Pass handling
  if (passIndex == 1) {
    // TODO FEATURE_013: Transparent pass with disabled depth write
    fprintf(stderr, "[MetalRender] [nDrawTerrain] Transparent pass (placeholder)\n");
  }
}
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPrewarmPipelines(JNIEnv *,
                                                                 jclass,
                                                                 jlong handle) {
}
JNIEXPORT jintArray JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetPipelineCacheStats(
    JNIEnv *env, jclass, jlong handle) {
  jintArray result = env->NewIntArray(0);
  return result;
}
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nResetPipelineCache(
    JNIEnv *, jclass, jlong handle) {}

// ============================================================================
// GENERIC DRAW SYSTEM: Queue vertex data from Java for entity/UI/particle rendering
// Called from RenderLayerMixin when MC tries to submit non-terrain draws to GL
// ============================================================================

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nClearGenericDraws(JNIEnv *, jclass,
                                                                  jlong handle) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx) return;
  ctx->genericDraws.clear();
  ctx->genericVertexCursor = 0;
}

// Upload a texture from raw RGBA pixel data. Returns a texture ID for binding.
JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nUploadGenericTexture(
    JNIEnv *env, jclass, jlong handle, jint texId, jint width, jint height,
    jbyteArray pixelData) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx || !ctx->device || width <= 0 || height <= 0) return -1;

  uint32_t texIdU = static_cast<uint32_t>(texId);
  NSNumber *key = @(texIdU);

  // Ensure dictionary exists
  if (!ctx->boundTextures) {
    ctx->boundTextures = [[NSMutableDictionary alloc] init];
  }

  // Validate pixel data length
  jsize dataLen = env->GetArrayLength(pixelData);
  jsize expectedLen = (jsize)(width * height * 4);
  if (dataLen < expectedLen) {
    fprintf(stderr, "[MetalRender] nUploadGenericTexture: pixel data too small! "
            "got %d, need %d for %dx%d (id=%d)\n",
            (int)dataLen, (int)expectedLen, width, height, texId);
    return -1;
  }

  // ALWAYS create a new texture — never replaceRegion on an existing texture
  // that the GPU might be reading. This avoids SIGSEGV race conditions in
  // Metal's texture compression (AGX driver) when CPU writes overlap GPU reads.
  MTLTextureDescriptor *desc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                  width:(NSUInteger)width
                                 height:(NSUInteger)height
                              mipmapped:NO];
  desc.usage = MTLTextureUsageShaderRead;
  desc.storageMode = MTLStorageModeShared;

  id<MTLTexture> newTex = [ctx->device newTextureWithDescriptor:desc];
  if (!newTex) {
    fprintf(stderr, "[MetalRender] nUploadGenericTexture: Failed to create %dx%d texture (id=%d)\n",
            width, height, texId);
    return -1;
  }
  [newTex setLabel:[NSString stringWithFormat:@"GenericTex_%d", texId]];

  // Upload pixel data to the NEW texture (safe — GPU has no reference to it yet)
  jbyte *pixels = env->GetByteArrayElements(pixelData, nullptr);
  if (pixels) {
    [newTex replaceRegion:MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height)
           mipmapLevel:0
             withBytes:pixels
           bytesPerRow:(NSUInteger)(width * 4)];
    env->ReleaseByteArrayElements(pixelData, pixels, JNI_ABORT);
  }

  // Swap into the dictionary — old texture (if any) is released by ARC
  // after the GPU finishes using it (Metal retains textures referenced by
  // in-flight command buffers)
  ctx->boundTextures[key] = newTex;

  static int uploadCount = 0;
  uploadCount++;
  if (uploadCount <= 30) {
    // Verify upload: read back from Metal texture and compare with input
    uint8_t readback[4] = {0};
    // Find first non-zero pixel in input to verify
    int sampleX = -1, sampleY = -1;
    jbyte *verifyPixels = env->GetByteArrayElements(pixelData, nullptr);
    if (verifyPixels) {
      for (int py = 0; py < height && sampleX < 0; py++) {
        for (int px = 0; px < width && sampleX < 0; px++) {
          int idx = (py * width + px) * 4;
          uint8_t r = (uint8_t)verifyPixels[idx], g = (uint8_t)verifyPixels[idx+1];
          uint8_t b = (uint8_t)verifyPixels[idx+2], a = (uint8_t)verifyPixels[idx+3];
          if (r > 0 || g > 0 || b > 0 || a > 0) {
            sampleX = px; sampleY = py;
          }
        }
      }
      if (sampleX >= 0) {
        [newTex getBytes:readback
             bytesPerRow:(NSUInteger)(width * 4)
              fromRegion:MTLRegionMake2D((NSUInteger)sampleX, (NSUInteger)sampleY, 1, 1)
             mipmapLevel:0];
        int srcIdx = (sampleY * width + sampleX) * 4;
        fprintf(stderr, "[MetalRender] nUploadGenericTexture: id=%d (%dx%d) "
                "verify@(%d,%d) input=(%u,%u,%u,%u) metal=(%u,%u,%u,%u) %s\n",
                texId, width, height, sampleX, sampleY,
                (uint8_t)verifyPixels[srcIdx], (uint8_t)verifyPixels[srcIdx+1],
                (uint8_t)verifyPixels[srcIdx+2], (uint8_t)verifyPixels[srcIdx+3],
                readback[0], readback[1], readback[2], readback[3],
                (readback[0] == (uint8_t)verifyPixels[srcIdx] &&
                 readback[1] == (uint8_t)verifyPixels[srcIdx+1] &&
                 readback[2] == (uint8_t)verifyPixels[srcIdx+2] &&
                 readback[3] == (uint8_t)verifyPixels[srcIdx+3]) ? "MATCH" : "MISMATCH!");
      } else {
        fprintf(stderr, "[MetalRender] nUploadGenericTexture: id=%d (%dx%d) ALL ZERO input!\n",
                texId, width, height);
      }
      env->ReleaseByteArrayElements(pixelData, verifyPixels, JNI_ABORT);
    }
  }
  return texId;
}

// Queue a generic draw command with vertex data in unified format (32 bytes/vertex)
// Vertex layout: float3 pos (12) + ubyte4 color (4) + float2 uv (8) + float2 light (8)
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nQueueGenericDraw(
    JNIEnv *env, jclass, jlong handle,
    jbyteArray vertexData, jint vertexCount,
    jint textureId, jint blendMode,
    jfloatArray modelViewMatrix) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  static uint64_t nativeQueueCallCount = 0;
  nativeQueueCallCount++;
  if ((nativeQueueCallCount % 1000) == 1) {
    NSLog(@"[MetalRender] nQueueGenericDraw NATIVE: call=%llu ctx=%p device=%p vertCount=%d",
            nativeQueueCallCount, ctx, ctx ? (__bridge void*)ctx->device : nullptr, vertexCount);
  }
  if (!ctx || !ctx->device || vertexCount <= 0) return;

  const size_t GENERIC_VERTEX_SIZE = 32; // float3 + ubyte4 + float2 + float2(light)
  size_t dataSize = (size_t)vertexCount * GENERIC_VERTEX_SIZE;

  // Ensure generic vertex buffer has enough space
  size_t requiredSize = ctx->genericVertexCursor + dataSize;
  if (!ctx->genericVertexBuffer || ctx->genericVertexCapacity < requiredSize) {
    size_t newCapacity = std::max(requiredSize * 2, (size_t)(4 * 1024 * 1024)); // At least 4MB
    id<MTLBuffer> newBuf = [ctx->device newBufferWithLength:newCapacity
                                                    options:MTLResourceStorageModeShared];
    if (!newBuf) {
      fprintf(stderr, "[MetalRender] nQueueGenericDraw: Failed to allocate %zu byte vertex buffer\n", newCapacity);
      return;
    }
    [newBuf setLabel:@"Generic Vertex Buffer"];
    // Copy existing data if any
    if (ctx->genericVertexBuffer && ctx->genericVertexCursor > 0) {
      memcpy([newBuf contents], [ctx->genericVertexBuffer contents], ctx->genericVertexCursor);
    }
    ctx->genericVertexBuffer = newBuf;
    ctx->genericVertexCapacity = newCapacity;
  }

  // Copy vertex data into buffer
  jbyte *vdata = env->GetByteArrayElements(vertexData, nullptr);
  if (!vdata) return;
  uint8_t *dst = reinterpret_cast<uint8_t *>([ctx->genericVertexBuffer contents]) + ctx->genericVertexCursor;
  memcpy(dst, vdata, dataSize);
  env->ReleaseByteArrayElements(vertexData, vdata, JNI_ABORT);

  // Record draw command
  MetalContext::GenericDrawCmd cmd;
  cmd.vertexOffset = static_cast<uint32_t>(ctx->genericVertexCursor);
  cmd.vertexCount = static_cast<uint32_t>(vertexCount);
  cmd.textureId = static_cast<uint32_t>(textureId);
  cmd.blendMode = static_cast<uint32_t>(blendMode);

  // Copy model-view matrix (or identity)
  if (modelViewMatrix && env->GetArrayLength(modelViewMatrix) >= 16) {
    env->GetFloatArrayRegion(modelViewMatrix, 0, 16, cmd.modelView);
    cmd.hasModelView = true;
  } else {
    // Identity matrix
    memset(cmd.modelView, 0, sizeof(cmd.modelView));
    cmd.modelView[0] = cmd.modelView[5] = cmd.modelView[10] = cmd.modelView[15] = 1.0f;
    cmd.hasModelView = false;
  }

  ctx->genericDraws.push_back(cmd);
  ctx->genericVertexCursor += dataSize;

  static uint64_t queueCount = 0;
  if ((queueCount++ % 5000) == 0) {
    fprintf(stderr, "[MetalRender] nQueueGenericDraw: %zu draws queued, %zu bytes total, texId=%d\n",
            ctx->genericDraws.size(), ctx->genericVertexCursor, textureId);
  }
}

// Set the projection-only matrix for entity/UI rendering
// (entity vertex positions are already in view/camera space)
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetProjectionMatrix(
    JNIEnv *env, jclass, jlong handle, jfloatArray matrix) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx || !matrix) return;
  if (env->GetArrayLength(matrix) < 16) return;
  env->GetFloatArrayRegion(matrix, 0, 16, ctx->projMatrix);
  ctx->hasProjMatrix = true;
  static uint64_t setProjCount = 0;
  if ((setProjCount++ % 300) == 0) {
    NSLog(@"[MetalRender] nSetProjectionMatrix: diag [0]=%.4f [5]=%.4f [10]=%.4f [14]=%.4f [15]=%.4f",
          ctx->projMatrix[0], ctx->projMatrix[5], ctx->projMatrix[10], ctx->projMatrix[14], ctx->projMatrix[15]);
  }
}

// Set sky brightness for day/night cycle (0.0 = night, 1.0 = full day)
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetSkyBrightness(JNIEnv *, jclass,
                                                                   jlong handle, jfloat brightness) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx) return;
  ctx->skyBrightness = brightness;
  static uint64_t logCount = 0;
  if ((logCount++ % 300) == 0) {
    NSLog(@"[MetalRender] nSetSkyBrightness: %.4f", brightness);
  }
}

// ============================================================================
// Offscreen Render-to-Texture (Metal RTT) for item model rendering
// Replaces GL offscreen rendering with pure Metal:
//   nClearOffscreen      → clears the persistent offscreen RT (for scroll/tab changes)
//   nBeginOffscreenPass  → creates offscreen texture + render pass (LOAD to persist)
//   nDrawOffscreen       → immediate-encodes draw commands to offscreen encoder
//   nEndOffscreenPass    → ends pass, snapshots to persistent texture, commits
// ============================================================================

// Clear the persistent offscreen render target to transparent.
// Called by Java when MC signals a full atlas redraw (scroll, tab change, etc.)
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nClearOffscreen(
    JNIEnv *env, jclass, jlong handle) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx || !ctx->offscreenRTColor) return;

  // End any in-progress offscreen pass first
  if (ctx->inOffscreenPass && ctx->offscreenEncoder) {
    [ctx->offscreenEncoder endEncoding];
    ctx->offscreenEncoder = nil;
    if (ctx->offscreenCmdBuf) {
      [ctx->offscreenCmdBuf commit];
      [ctx->offscreenCmdBuf waitUntilCompleted];
      ctx->offscreenCmdBuf = nil;
    }
    ctx->inOffscreenPass = false;
  }

  // Create a quick render pass just to clear the color RT
  MTLRenderPassDescriptor *rpd = [MTLRenderPassDescriptor renderPassDescriptor];
  rpd.colorAttachments[0].texture = ctx->offscreenRTColor;
  rpd.colorAttachments[0].loadAction = MTLLoadActionClear;
  rpd.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
  rpd.colorAttachments[0].storeAction = MTLStoreActionStore;
  rpd.depthAttachment.texture = ctx->offscreenRTDepth;
  rpd.depthAttachment.loadAction = MTLLoadActionClear;
  rpd.depthAttachment.clearDepth = 1.0;
  rpd.depthAttachment.storeAction = MTLStoreActionDontCare;

  id<MTLCommandBuffer> cmdBuf = [ctx->graphicsQueue commandBuffer];
  id<MTLRenderCommandEncoder> enc = [cmdBuf renderCommandEncoderWithDescriptor:rpd];
  if (enc) {
    [enc endEncoding];
  }
  [cmdBuf commit];
  [cmdBuf waitUntilCompleted];

  NSLog(@"[MetalRender] nClearOffscreen: cleared %dx%d RT to transparent",
        ctx->offscreenRTWidth, ctx->offscreenRTHeight);
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBeginOffscreenPass(
    JNIEnv *env, jclass, jlong handle, jint width, jint height) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx || !ctx->device || !ctx->graphicsQueue) return -1;

  if (!ensureGenericPipeline(ctx)) return -2;

  // Safety: end any existing offscreen pass
  if (ctx->inOffscreenPass && ctx->offscreenEncoder) {
    [ctx->offscreenEncoder endEncoding];
    ctx->offscreenEncoder = nil;
    if (ctx->offscreenCmdBuf) {
      [ctx->offscreenCmdBuf commit];
      [ctx->offscreenCmdBuf waitUntilCompleted];
      ctx->offscreenCmdBuf = nil;
    }
    ctx->inOffscreenPass = false;
  }

  // Create/resize offscreen textures if needed
  bool sizeChanged = (!ctx->offscreenRTColor || ctx->offscreenRTWidth != width || ctx->offscreenRTHeight != height);
  id<MTLTexture> oldColorRT = nil;
  int oldWidth = ctx->offscreenRTWidth;
  int oldHeight = ctx->offscreenRTHeight;

  if (sizeChanged) {
    // Keep reference to old RT so we can copy its content to the new one
    oldColorRT = ctx->offscreenRTColor;

    MTLTextureDescriptor *colorDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                    width:width
                                   height:height
                                mipmapped:NO];
    colorDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    colorDesc.storageMode = MTLStorageModePrivate;
    ctx->offscreenRTColor = [ctx->device newTextureWithDescriptor:colorDesc];
    [ctx->offscreenRTColor setLabel:@"Offscreen RTT Color"];

    MTLTextureDescriptor *depthDesc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                    width:width
                                   height:height
                                mipmapped:NO];
    depthDesc.usage = MTLTextureUsageRenderTarget;
    depthDesc.storageMode = MTLStorageModePrivate;
    ctx->offscreenRTDepth = [ctx->device newTextureWithDescriptor:depthDesc];
    [ctx->offscreenRTDepth setLabel:@"Offscreen RTT Depth"];

    ctx->offscreenRTWidth = width;
    ctx->offscreenRTHeight = height;

    NSLog(@"[MetalRender] Created offscreen RTT textures: %dx%d (old was %dx%d)", width, height, oldWidth, oldHeight);

    // If we had old content, COPY it to the new larger RT to preserve previously-rendered items.
    // MC resizes the atlas when more items are needed but expects old items to be preserved
    // (via GL texture copy which we don't intercept). This blit preserves them in our Metal RT.
    if (oldColorRT && oldWidth > 0 && oldHeight > 0) {
      // Clear the new RT first (it's uninitialized)
      MTLRenderPassDescriptor *clearRpd = [MTLRenderPassDescriptor renderPassDescriptor];
      clearRpd.colorAttachments[0].texture = ctx->offscreenRTColor;
      clearRpd.colorAttachments[0].loadAction = MTLLoadActionClear;
      clearRpd.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
      clearRpd.colorAttachments[0].storeAction = MTLStoreActionStore;

      id<MTLCommandBuffer> blitCmdBuf = [ctx->graphicsQueue commandBuffer];
      [blitCmdBuf setLabel:@"Offscreen RT Resize Clear+Copy"];

      id<MTLRenderCommandEncoder> clearEnc = [blitCmdBuf renderCommandEncoderWithDescriptor:clearRpd];
      [clearEnc endEncoding];

      // Copy old content into the new RT (top-left corner)
      int copyW = MIN(oldWidth, width);
      int copyH = MIN(oldHeight, height);
      id<MTLBlitCommandEncoder> blit = [blitCmdBuf blitCommandEncoder];
      [blit copyFromTexture:oldColorRT
                sourceSlice:0
                sourceLevel:0
               sourceOrigin:MTLOriginMake(0, 0, 0)
                 sourceSize:MTLSizeMake(copyW, copyH, 1)
                  toTexture:ctx->offscreenRTColor
           destinationSlice:0
           destinationLevel:0
          destinationOrigin:MTLOriginMake(0, 0, 0)];
      [blit endEncoding];
      [blitCmdBuf commit];
      [blitCmdBuf waitUntilCompleted];

      NSLog(@"[MetalRender] Copied old RT content (%dx%d) to new RT (%dx%d)", copyW, copyH, width, height);
    }
  }

  // The Metal offscreen RT is our "storage bucket" — it persists between frames.
  // MC renders items incrementally: a few items on first frame, then only animated items.
  // Strategy:
  //   - CLEAR only when the RT is FIRST created (no old content to copy).
  //   - On RESIZE, old content was already copied above, so use LOAD.
  //   - LOAD on all subsequent passes to preserve previously-rendered items.
  //   - Use offscreenPipeline (src=One, dst=Zero) so drawn pixels fully replace old ones.
  //   - Fragment shader's discard_fragment() at alpha < 0.004 prevents transparent pixels
  //     from erasing old items. This way, only actually-drawn item pixels overwrite.
  //   - When MC wants to clear (e.g., scroll/tab change), it calls nClearOffscreen.
  // The Metal offscreen RT is our "storage bucket" — it persists between frames.
  // MC renders items incrementally: a few items on first frame, then only animated items.
  // Strategy:
  //   - CLEAR only when the RT is FIRST created (no old content to copy).
  //   - On RESIZE, old content was already copied above, so use LOAD.
  //   - LOAD on all subsequent passes to preserve previously-rendered items.
  //   - Use offscreenPipeline (src=One, dst=Zero) so drawn pixels fully replace old ones.
  //   - Transparent pixels (alpha=0) are WRITTEN, not discarded, so they properly
  //     overwrite old content when atlas positions are reused (e.g., tab switch/scroll).
  //   - When MC wants to clear (e.g., scroll/tab change), it calls nClearOffscreen.
  bool doClear = sizeChanged && !oldColorRT; // only clear if truly new (no old content)

  MTLRenderPassDescriptor *rpd = [MTLRenderPassDescriptor renderPassDescriptor];
  rpd.colorAttachments[0].texture = ctx->offscreenRTColor;
  rpd.colorAttachments[0].loadAction = doClear ? MTLLoadActionClear : MTLLoadActionLoad;
  rpd.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
  rpd.colorAttachments[0].storeAction = MTLStoreActionStore;
  rpd.depthAttachment.texture = ctx->offscreenRTDepth;
  rpd.depthAttachment.loadAction = MTLLoadActionClear; // always clear depth per pass
  rpd.depthAttachment.clearDepth = 1.0;
  rpd.depthAttachment.storeAction = MTLStoreActionDontCare;

  NSLog(@"[MetalRender] nBeginOffscreenPass %dx%d (%s)",
        width, height, doClear ? "CLEAR-new" : "LOAD-persist");

  // Create command buffer and render encoder
  ctx->offscreenCmdBuf = [ctx->graphicsQueue commandBuffer];
  [ctx->offscreenCmdBuf setLabel:@"Offscreen RTT"];
  ctx->offscreenEncoder = [ctx->offscreenCmdBuf renderCommandEncoderWithDescriptor:rpd];
  if (!ctx->offscreenEncoder) {
    NSLog(@"[MetalRender] Failed to create offscreen render encoder");
    ctx->offscreenCmdBuf = nil;
    return -3;
  }
  [ctx->offscreenEncoder setLabel:@"Offscreen RTT Encoder"];

  // Configure encoder: One/Zero pipeline (items fully overwrite old pixels, no alpha blend
  // accumulation = no blue halo). discard_fragment() in shader preserves untouched areas.
  id<MTLRenderPipelineState> offPipeline = ctx->offscreenPipeline ? ctx->offscreenPipeline : ctx->genericPipeline;
  [ctx->offscreenEncoder setRenderPipelineState:offPipeline];
  if (ctx->genericSampler) {
    [ctx->offscreenEncoder setFragmentSamplerState:ctx->genericSampler atIndex:0];
  }
  if (ctx->terrainDepthState) {
    [ctx->offscreenEncoder setDepthStencilState:ctx->terrainDepthState];
  }
  MTLViewport vp = {0, 0, (double)width, (double)height, 0, 1};
  [ctx->offscreenEncoder setViewport:vp];
  MTLScissorRect scissor = {0, 0, (NSUInteger)width, (NSUInteger)height};
  [ctx->offscreenEncoder setScissorRect:scissor];

  ctx->inOffscreenPass = true;
  ctx->offscreenFrameDraws = 0;
  ctx->offscreenFrameVerts = 0;

  static uint64_t beginCount = 0;
  beginCount++;
  if (beginCount <= 20 || (beginCount % 100) == 0) {
    NSLog(@"[MetalRender] nBeginOffscreenPass #%llu: %dx%d (%s) pipeline=%s",
          beginCount, width, height, doClear ? "CLEAR-new" : "LOAD-persist",
          offPipeline == ctx->offscreenPipeline ? "offscreen(One/Zero)" : "generic(Alpha)");
  }
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawOffscreen(
    JNIEnv *env, jclass, jlong handle,
    jbyteArray vertexData, jint vertexCount,
    jint textureId, jint blendMode,
    jfloatArray projMatrix) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx || !ctx->inOffscreenPass || !ctx->offscreenEncoder) return -1;
  if (vertexCount <= 0) return -2;

  // Get vertex data
  jsize dataLen = env->GetArrayLength(vertexData);
  jbyte *data = env->GetByteArrayElements(vertexData, nullptr);
  if (!data) return -3;

  // Use setVertexBytes for small data (<=4KB), proper buffer for larger data
  if (dataLen <= 4096) {
    [ctx->offscreenEncoder setVertexBytes:data length:dataLen atIndex:0];
  } else {
    // Grow reusable buffer if needed
    if (!ctx->offscreenVertexBuffer || ctx->offscreenVertexCapacity < (size_t)dataLen) {
      size_t newCap = std::max((size_t)dataLen, ctx->offscreenVertexCapacity * 2);
      newCap = std::max(newCap, (size_t)8192); // minimum 8KB
      ctx->offscreenVertexBuffer = [ctx->device newBufferWithLength:newCap
                                                         options:MTLResourceStorageModeShared];
      ctx->offscreenVertexCapacity = newCap;
    }
    memcpy([ctx->offscreenVertexBuffer contents], data, dataLen);
    [ctx->offscreenEncoder setVertexBuffer:ctx->offscreenVertexBuffer offset:0 atIndex:0];
  }
  env->ReleaseByteArrayElements(vertexData, data, JNI_ABORT);

  // Build uniforms with the offscreen projection matrix
  struct GenericUniforms {
    float viewProj[16];
    uint32_t flags;
    float skyBrightness;
  };

  GenericUniforms uniforms;
  if (projMatrix && env->GetArrayLength(projMatrix) >= 16) {
    env->GetFloatArrayRegion(projMatrix, 0, 16, uniforms.viewProj);
  } else {
    memset(uniforms.viewProj, 0, sizeof(uniforms.viewProj));
    uniforms.viewProj[0] = uniforms.viewProj[5] = uniforms.viewProj[10] = uniforms.viewProj[15] = 1.0f;
  }
  uniforms.skyBrightness = 1.0f; // full brightness for UI items

  // Bind source texture (entity/item atlas — already in boundTextures cache)
  NSNumber *texKey = @(textureId);
  id<MTLTexture> tex = ctx->boundTextures ? ctx->boundTextures[texKey] : nil;
  if (tex) {
    [ctx->offscreenEncoder setFragmentTexture:tex atIndex:0];
    uniforms.flags = 1 | 4; // has texture + disable discard (storage bucket mode)
  } else {
    uniforms.flags = 0 | 4; // no texture + disable discard
  }

  [ctx->offscreenEncoder setVertexBytes:&uniforms length:sizeof(uniforms) atIndex:1];
  [ctx->offscreenEncoder setFragmentBytes:&uniforms length:sizeof(uniforms) atIndex:1];

  // Draw triangles
  [ctx->offscreenEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                            vertexStart:0
                            vertexCount:vertexCount];

  static uint64_t offscreenDrawCount = 0;
  ctx->offscreenFrameDraws++;
  ctx->offscreenFrameVerts += vertexCount;
  if ((offscreenDrawCount++ % 100) == 0) {
    NSLog(@"[MetalRender] nDrawOffscreen #%llu: %d verts (frame total: %u draws, %u verts), tex=%d, hasTex=%d, dataLen=%d",
          offscreenDrawCount, vertexCount, ctx->offscreenFrameDraws, ctx->offscreenFrameVerts, textureId, tex != nil, (int)dataLen);
  }
  return 0;
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nEndOffscreenPass(
    JNIEnv *env, jclass, jlong handle, jint snapshotTexId) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx) { NSLog(@"[MetalRender] nEndOffscreenPass: ctx is NULL!"); return -1; }
  if (!ctx->inOffscreenPass) { NSLog(@"[MetalRender] nEndOffscreenPass: NOT in offscreen pass!"); return -2; }

  NSLog(@"[MetalRender] nEndOffscreenPass ENTRY: texId=%d %dx%d draws=%u verts=%u",
        snapshotTexId, ctx->offscreenRTWidth, ctx->offscreenRTHeight,
        ctx->offscreenFrameDraws, ctx->offscreenFrameVerts);

  // End render encoding
  if (ctx->offscreenEncoder) {
    [ctx->offscreenEncoder endEncoding];
    ctx->offscreenEncoder = nil;
  }

  // Create a persistent snapshot texture (copy of offscreen result)
  // Each item gets its own snapshot so deferred compositing works correctly
  MTLTextureDescriptor *snapDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                  width:ctx->offscreenRTWidth
                                 height:ctx->offscreenRTHeight
                               mipmapped:NO];
  snapDesc.usage = MTLTextureUsageShaderRead;
  snapDesc.storageMode = MTLStorageModePrivate;
  id<MTLTexture> snapshot = [ctx->device newTextureWithDescriptor:snapDesc];
  [snapshot setLabel:[NSString stringWithFormat:@"Offscreen Snapshot %d", snapshotTexId]];

  // GPU blit copy: offscreen render target → snapshot texture
  id<MTLBlitCommandEncoder> blit = [ctx->offscreenCmdBuf blitCommandEncoder];
  [blit copyFromTexture:ctx->offscreenRTColor
            sourceSlice:0
            sourceLevel:0
           sourceOrigin:MTLOriginMake(0, 0, 0)
             sourceSize:MTLSizeMake(ctx->offscreenRTWidth, ctx->offscreenRTHeight, 1)
              toTexture:snapshot
       destinationSlice:0
       destinationLevel:0
      destinationOrigin:MTLOriginMake(0, 0, 0)];
  [blit endEncoding];

  // Commit and wait — synchronous because MC reuses the offscreen buffer
  [ctx->offscreenCmdBuf commit];
  [ctx->offscreenCmdBuf waitUntilCompleted];
  ctx->offscreenCmdBuf = nil;

  // Register snapshot in texture cache under the snapshot ID
  if (!ctx->boundTextures) {
    ctx->boundTextures = [[NSMutableDictionary alloc] init];
  }
  ctx->boundTextures[@(snapshotTexId)] = snapshot;

  ctx->inOffscreenPass = false;

  static uint64_t snapshotCount = 0;
  snapshotCount++;
  if (snapshotCount <= 20 || (snapshotCount % 100) == 0) {
    NSLog(@"[MetalRender] nEndOffscreenPass #%llu: snapshot texId=%d (%dx%d) — frame had %u draws, %u verts",
          snapshotCount, snapshotTexId, ctx->offscreenRTWidth, ctx->offscreenRTHeight,
          ctx->offscreenFrameDraws, ctx->offscreenFrameVerts);
  }
  // Reset per-frame counters
  ctx->offscreenFrameDraws = 0;
  ctx->offscreenFrameVerts = 0;
  return 0;
}

// ============================================================================
// Item Cache: GPU-side blit from atlas snapshot to per-item cache texture
// ============================================================================

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nBlitToItemCache(
    JNIEnv *env, jclass, jlong handle,
    jint srcTexId, jint dstTexId,
    jint srcX, jint srcY, jint srcW, jint srcH) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx || !ctx->device || !ctx->graphicsQueue) return -1;
  if (!ctx->boundTextures) return -2;

  id<MTLTexture> srcTex = ctx->boundTextures[@(srcTexId)];
  if (!srcTex) {
    NSLog(@"[MetalRender] nBlitToItemCache: src tex %d not found", srcTexId);
    return -3;
  }

  // Clamp source region to texture bounds
  int clampedX = MAX(0, MIN(srcX, (int)srcTex.width - 1));
  int clampedY = MAX(0, MIN(srcY, (int)srcTex.height - 1));
  int clampedW = MIN(srcW, (int)srcTex.width - clampedX);
  int clampedH = MIN(srcH, (int)srcTex.height - clampedY);
  if (clampedW <= 0 || clampedH <= 0) {
    NSLog(@"[MetalRender] nBlitToItemCache: invalid region (%d,%d %dx%d) in %dx%d tex",
          srcX, srcY, srcW, srcH, (int)srcTex.width, (int)srcTex.height);
    return -4;
  }

  // Create or reuse destination texture
  id<MTLTexture> dstTex = ctx->boundTextures[@(dstTexId)];
  if (!dstTex || (int)dstTex.width != clampedW || (int)dstTex.height != clampedH) {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                    width:clampedW
                                   height:clampedH
                                mipmapped:NO];
    desc.usage = MTLTextureUsageShaderRead;
    desc.storageMode = MTLStorageModePrivate;
    dstTex = [ctx->device newTextureWithDescriptor:desc];
    [dstTex setLabel:[NSString stringWithFormat:@"ItemCache %d", dstTexId]];
  }

  // GPU blit: copy sub-region from source to destination
  id<MTLCommandBuffer> cmdBuf = [ctx->graphicsQueue commandBuffer];
  [cmdBuf setLabel:@"ItemCache Blit"];
  id<MTLBlitCommandEncoder> blit = [cmdBuf blitCommandEncoder];
  [blit copyFromTexture:srcTex
            sourceSlice:0
            sourceLevel:0
           sourceOrigin:MTLOriginMake(clampedX, clampedY, 0)
             sourceSize:MTLSizeMake(clampedW, clampedH, 1)
              toTexture:dstTex
       destinationSlice:0
       destinationLevel:0
      destinationOrigin:MTLOriginMake(0, 0, 0)];
  [blit endEncoding];
  [cmdBuf commit];
  [cmdBuf waitUntilCompleted];

  // Register in texture cache
  ctx->boundTextures[@(dstTexId)] = dstTex;

  static uint64_t blitCount = 0;
  blitCount++;
  if (blitCount <= 20 || (blitCount % 100) == 0) {
    NSLog(@"[MetalRender] nBlitToItemCache #%llu: src=%d (%d,%d %dx%d) → dst=%d",
          blitCount, srcTexId, clampedX, clampedY, clampedW, clampedH, dstTexId);
  }
  return 0;
}

// Flush all queued generic draws: create a second Metal render pass on the same
// drawable (preserving terrain color + depth), render entities/UI, then present.
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDrawOverlay(JNIEnv *, jclass,
                                                            jlong handle, jint passIndex) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);

  static uint64_t overlayCallCount = 0;
  overlayCallCount++;
  if ((overlayCallCount % 60) == 1) {
    NSLog(@"[MetalRender] nDrawOverlay CALLED: ctx=%p drawable=%p queue=%p genericDraws=%zu cursor=%zu",
            ctx, ctx ? (__bridge void*)ctx->currentDrawable : nullptr,
            ctx ? (__bridge void*)ctx->graphicsQueue : nullptr,
            ctx ? ctx->genericDraws.size() : 0,
            ctx ? ctx->genericVertexCursor : 0);
  }

  if (!ctx || !ctx->currentDrawable || !ctx->graphicsQueue) {
    if (ctx) {
      ctx->genericDraws.clear();
      ctx->genericVertexCursor = 0;
      ctx->currentDrawable = nil;
    }
    return;
  }

  // If no generic draws queued, just present terrain
  if (ctx->genericDraws.empty()) {
    id<MTLCommandBuffer> presentCB = [ctx->graphicsQueue commandBuffer];
    if (presentCB) {
      [presentCB setLabel:@"Present Only"];
      [presentCB presentDrawable:ctx->currentDrawable];
      ctx->previousCommandBuffer = presentCB;
      [presentCB commit];
    }
    ctx->currentDrawable = nil;

    static uint64_t emptyPresentCount = 0;
    emptyPresentCount++;
    if ((emptyPresentCount % 60) == 1) {
      NSLog(@"[MetalRender] nDrawOverlay: No generic draws, presenting terrain only (called %llu times)", emptyPresentCount);
    }
    return;
  }

  // STEP 1: Ensure generic pipeline exists
  NSLog(@"[MetalRender] OVERLAY STEP 1: ensureGenericPipeline (draws=%zu)", ctx->genericDraws.size());
  if (!ensureGenericPipeline(ctx)) {
    NSLog(@"[MetalRender] OVERLAY STEP 1 FAILED: pipeline creation failed");
    fprintf(stderr, "[MetalRender] nDrawOverlay: Failed to create generic pipeline\n");
    id<MTLCommandBuffer> presentCB = [ctx->graphicsQueue commandBuffer];
    if (presentCB) {
      [presentCB presentDrawable:ctx->currentDrawable];
      ctx->previousCommandBuffer = presentCB;
      [presentCB commit];
    }
    ctx->currentDrawable = nil;
    ctx->genericDraws.clear();
    ctx->genericVertexCursor = 0;
    return;
  }
  NSLog(@"[MetalRender] OVERLAY STEP 1 OK: pipeline=%p", ctx->genericPipeline);

  // STEP 2: Create render pass that preserves terrain (color + depth)
  id<MTLTexture> drawableTex = [ctx->currentDrawable texture];
  NSLog(@"[MetalRender] OVERLAY STEP 2: drawableTex=%p depthTex=%p size=%ux%u",
        drawableTex, ctx->depthTexture, ctx->currentWidth, ctx->currentHeight);
  if (!drawableTex) {
    NSLog(@"[MetalRender] OVERLAY STEP 2 FAILED: drawable texture is nil");
    ctx->currentDrawable = nil;
    ctx->genericDraws.clear();
    ctx->genericVertexCursor = 0;
    return;
  }

  MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
  pass.colorAttachments[0].texture = drawableTex;
  pass.colorAttachments[0].loadAction = MTLLoadActionLoad;
  pass.colorAttachments[0].storeAction = MTLStoreActionStore;

  if (ctx->depthTexture) {
    pass.depthAttachment.texture = ctx->depthTexture;
    pass.depthAttachment.loadAction = MTLLoadActionLoad;
    pass.depthAttachment.storeAction = MTLStoreActionDontCare;
  } else {
    NSLog(@"[MetalRender] OVERLAY STEP 2 WARNING: no depth texture, skipping depth attachment");
  }

  // STEP 3: Create command buffer and render encoder
  id<MTLCommandBuffer> cmdBuf = [ctx->graphicsQueue commandBuffer];
  if (!cmdBuf) {
    NSLog(@"[MetalRender] OVERLAY STEP 3 FAILED: command buffer nil");
    ctx->currentDrawable = nil;
    ctx->genericDraws.clear();
    ctx->genericVertexCursor = 0;
    return;
  }
  [cmdBuf setLabel:@"Generic Draw Pass"];

  id<MTLRenderCommandEncoder> enc = [cmdBuf renderCommandEncoderWithDescriptor:pass];
  if (!enc) {
    NSLog(@"[MetalRender] OVERLAY STEP 3 FAILED: render encoder nil (drawableTex=%p depthTex=%p)",
          drawableTex, ctx->depthTexture);
    ctx->currentDrawable = nil;
    ctx->genericDraws.clear();
    ctx->genericVertexCursor = 0;
    return;
  }
  NSLog(@"[MetalRender] OVERLAY STEP 3 OK: encoder=%p", enc);
  [enc setLabel:@"Entity/UI Renderer"];

  // STEP 4: Configure encoder
  uint32_t w = ctx->currentWidth;
  uint32_t h = ctx->currentHeight;
  MTLViewport viewport = {0.0, 0.0, (double)w, (double)h, 0.0, 1.0};
  [enc setViewport:viewport];
  MTLScissorRect scissor = {0, 0, w, h};
  [enc setScissorRect:scissor];

  [enc setRenderPipelineState:ctx->genericPipeline];
  int currentPipelineMode = 1; // 0=additive, 1=alpha-blend (generic)

  if (ctx->genericSampler) {
    [enc setFragmentSamplerState:ctx->genericSampler atIndex:0];
  }

  // Set up uniform struct — will be updated per-draw with the correct matrix
  struct GenericUniforms {
    float viewProj[16];
    uint32_t flags;
    float skyBrightness; // 0.0 = night, 1.0 = full day
  };

  // STEP 5: Render each queued draw with per-draw matrix and depth state
  [enc setVertexBuffer:ctx->genericVertexBuffer offset:0 atIndex:0];

  // Track current depth state to minimize state changes
  int currentDepthMode = -1; // -1 = not set yet, 1 = 3D depth, 2 = UI no-depth

  // DIAG: Dump first few vertices from first draw (throttled)
  static uint64_t dumpCount = 0;
  if ((dumpCount++ % 300) == 0 && !ctx->genericDraws.empty()) {
    // Count 3D vs UI draws
    uint32_t worldDraws = 0, uiDraws = 0;
    for (const auto &cmd : ctx->genericDraws) {
      if (cmd.blendMode == 2) uiDraws++;
      else worldDraws++;
    }
    NSLog(@"[MetalRender] OVERLAY draws: %u WORLD + %u UI = %zu total",
          worldDraws, uiDraws, ctx->genericDraws.size());
  }

  // ONE-TIME: Dump ALL draws' vertex bounds, colors, textures for diagnosis
  static bool dumpedAllDraws = false;
  static uint64_t dumpFrameWait = 0;
  dumpFrameWait++;
  if (!dumpedAllDraws && dumpFrameWait > 120) {  // Wait ~2 seconds for stable gameplay
    dumpedAllDraws = true;
    NSLog(@"[MetalRender] === FULL DRAW DUMP (frame) === %zu draws, viewport %ux%u ===", 
          ctx->genericDraws.size(), w, h);
    const uint8_t *vbuf = reinterpret_cast<const uint8_t *>([ctx->genericVertexBuffer contents]);
    int drawIdx = 0;
    for (const auto &dcmd : ctx->genericDraws) {
      // Parse vertex positions to find screen-space bounds
      float minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
      uint8_t firstR = 0, firstG = 0, firstB = 0, firstA = 0;
      const uint8_t *vertBase = vbuf + dcmd.vertexOffset;
      for (uint32_t vi = 0; vi < dcmd.vertexCount; vi++) {
        const uint8_t *v = vertBase + vi * 32;
        float vx, vy;
        memcpy(&vx, v + 0, 4);
        memcpy(&vy, v + 4, 4);
        if (vx < minX) minX = vx;
        if (vy < minY) minY = vy;
        if (vx > maxX) maxX = vx;
        if (vy > maxY) maxY = vy;
        if (vi == 0) {
          firstR = v[12]; firstG = v[13]; firstB = v[14]; firstA = v[15];
        }
      }
      // Check if draw covers significant portion of screen
      float coverageX = (maxX - minX) / (float)w;
      float coverageY = (maxY - minY) / (float)h;
      bool isLarge = (coverageX > 0.5f && coverageY > 0.5f);
      NSLog(@"[MetalRender] DRAW[%d] blend=%u tex=%u verts=%u bounds=(%.1f,%.1f)-(%.1f,%.1f) "
            @"color=(%u,%u,%u,%u) coverage=%.1f%%x%.1f%% %s",
            drawIdx, dcmd.blendMode, dcmd.textureId, dcmd.vertexCount,
            minX, minY, maxX, maxY,
            firstR, firstG, firstB, firstA,
            coverageX * 100, coverageY * 100,
            isLarge ? "*** LARGE ***" : "");
      // For large draws, also dump the projection matrix
      if (isLarge && dcmd.hasModelView) {
        NSLog(@"[MetalRender]   matrix: [%.3f %.3f %.3f %.3f] [%.3f %.3f %.3f %.3f] "
              @"[%.3f %.3f %.3f %.3f] [%.3f %.3f %.3f %.3f]",
              dcmd.modelView[0], dcmd.modelView[1], dcmd.modelView[2], dcmd.modelView[3],
              dcmd.modelView[4], dcmd.modelView[5], dcmd.modelView[6], dcmd.modelView[7],
              dcmd.modelView[8], dcmd.modelView[9], dcmd.modelView[10], dcmd.modelView[11],
              dcmd.modelView[12], dcmd.modelView[13], dcmd.modelView[14], dcmd.modelView[15]);
      }
      drawIdx++;
    }
    NSLog(@"[MetalRender] === END FULL DRAW DUMP ===");
  }

  // Stable-sort draws by blendMode to ensure correct layering:
  // blendMode 0 (sky/celestial, additive blend, no depth write)
  // blendMode 1 (3D entities, alpha blend, depth test+write)
  // blendMode 4 (entity shadows, multiply blend, depth test no write, depth bias)
  // blendMode 2 (UI, alpha blend, no depth test)
  // blendMode 3 (text overlay, alpha blend, no depth test)
  // Map blendMode to sort order so shadows (4) come after entities (1) but before UI (2)
  auto sortOrder = [](uint32_t bm) -> int {
    switch (bm) {
      case 0: return 0; // sky
      case 1: return 1; // entities
      case 4: return 2; // shadows (after entities, before UI)
      case 2: return 3; // UI
      case 3: return 4; // text
      default: return 5;
    }
  };
  std::stable_sort(ctx->genericDraws.begin(), ctx->genericDraws.end(),
    [&sortOrder](const MetalContext::GenericDrawCmd &a, const MetalContext::GenericDrawCmd &b) {
      return sortOrder(a.blendMode) < sortOrder(b.blendMode);
    });

  uint32_t drawCount = 0;
  // DIAGNOSTIC: Sample Metal texture data at draw time for first few UI draws
  static uint64_t drawTexSampleCount = 0;
  drawTexSampleCount++;
  bool shouldSampleTex = (drawTexSampleCount <= 30 || (drawTexSampleCount % 500) == 0);

  for (const auto &cmd : ctx->genericDraws) {
    // Switch depth state based on blend mode
    // blendMode 0 = sky/celestial (depth test ON to be occluded by terrain, depth write OFF)
    // blendMode 1 = 3D alpha (depth test + write enabled)
    // blendMode 4 = shadows (depth test ON lequal, depth write OFF, depth bias)
    // blendMode 2 = UI (no depth test)
    // blendMode 3 = text overlay (same depth state as UI, but drawn after)
    int depthMode;
    if (cmd.blendMode == 0) depthMode = 0;       // sky
    else if (cmd.blendMode == 1) depthMode = 1;  // 3D world
    else if (cmd.blendMode == 4) depthMode = 3;  // shadow
    else depthMode = 2;                           // UI/text
    if (depthMode != currentDepthMode) {
      if (depthMode == 0 && ctx->skyDepthState) {
        [enc setDepthStencilState:ctx->skyDepthState];
        [enc setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
      } else if (depthMode == 3 && ctx->shadowDepthState) {
        [enc setDepthStencilState:ctx->shadowDepthState];
        // Depth bias pushes shadow closer to camera to avoid z-fighting with terrain
        [enc setDepthBias:-1.0f slopeScale:-1.0f clamp:0.0f];
      } else if (depthMode == 2 && ctx->uiDepthState) {
        [enc setDepthStencilState:ctx->uiDepthState];
        [enc setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
      } else if (ctx->terrainDepthState) {
        [enc setDepthStencilState:ctx->terrainDepthState];
        [enc setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
      }
      currentDepthMode = depthMode;
    }

    // Switch pipeline: 
    // blendMode 0 (celestial) → additive blending
    // everything else (entities, shadows, UI) → standard alpha blending
    int wantPipeline = (cmd.blendMode == 0 && ctx->additivePipeline) ? 0 : 1;
    if (wantPipeline != currentPipelineMode) {
      if (wantPipeline == 0) {
        [enc setRenderPipelineState:ctx->additivePipeline];
      } else {
        [enc setRenderPipelineState:ctx->genericPipeline];
      }
      currentPipelineMode = wantPipeline;
    }

    // Set per-draw projection matrix
    GenericUniforms uniforms;
    if (cmd.hasModelView) {
      memcpy(uniforms.viewProj, cmd.modelView, sizeof(float) * 16);
    } else if (ctx->hasProjMatrix) {
      memcpy(uniforms.viewProj, ctx->projMatrix, sizeof(float) * 16);
    } else {
      memcpy(uniforms.viewProj, ctx->viewProj, sizeof(float) * 16);
    }
    uniforms.flags = 1; // has texture
    if (cmd.blendMode == 1) {
      uniforms.flags |= 2; // world phase — apply sky brightness via per-vertex lighting
    }
    // Shadow draws (blendMode 4): keep flags=1 only — no sky brightness modulation,
    // shadows are just darkening quads with correct alpha from MC's shadow system
    if (cmd.blendMode == 4) {
      uniforms.flags = 1;
    }
    uniforms.skyBrightness = ctx->skyBrightness;
    [enc setVertexBytes:&uniforms length:sizeof(uniforms) atIndex:1];
    [enc setFragmentBytes:&uniforms length:sizeof(uniforms) atIndex:1];

    // Bind texture for this draw
    NSNumber *texKey = @(cmd.textureId);
    id<MTLTexture> boundTex = ctx->boundTextures ? ctx->boundTextures[texKey] : nil;
    if (boundTex) {
      [enc setFragmentTexture:boundTex atIndex:0];
      // DIAGNOSTIC: Sample Metal texture pixel at draw time
      if (shouldSampleTex && cmd.blendMode == 2 && boundTex.storageMode == MTLStorageModeShared) {
        // Find a non-empty pixel by scanning corners and center of the texture
        NSUInteger tw = boundTex.width, th = boundTex.height;
        struct { NSUInteger x, y; } samplePts[] = {
          {tw/4, th/4}, {tw/2, th/2}, {tw/4, th/2}, {tw/2, th/4},
          {1, 1}, {tw/8, th/8}, {tw/4, th*3/4}
        };
        for (int sp = 0; sp < 7; sp++) {
          if (samplePts[sp].x >= tw || samplePts[sp].y >= th) continue;
          uint8_t px[4] = {0};
          [boundTex getBytes:px bytesPerRow:tw*4
                  fromRegion:MTLRegionMake2D(samplePts[sp].x, samplePts[sp].y, 1, 1)
                 mipmapLevel:0];
          if (px[0] || px[1] || px[2] || px[3]) {
            fprintf(stderr, "[MetalRender] DRAW-TEX-SAMPLE: texId=%u %lux%lu fmt=%lu "
                    "blend=%u pixel@(%lu,%lu)=(%u,%u,%u,%u)\n",
                    cmd.textureId, (unsigned long)tw, (unsigned long)th,
                    (unsigned long)boundTex.pixelFormat,
                    cmd.blendMode,
                    (unsigned long)samplePts[sp].x, (unsigned long)samplePts[sp].y,
                    px[0], px[1], px[2], px[3]);
            break;
          }
        }
        shouldSampleTex = false; // Only sample once per frame
      }
    } else if (ctx->atlasTexture) {
      [enc setFragmentTexture:ctx->atlasTexture atIndex:0];
      // DIAGNOSTIC: This draw fell back to the terrain atlas — log it
      if (shouldSampleTex && cmd.blendMode == 2) {
        fprintf(stderr, "[MetalRender] DRAW-TEX-FALLBACK: texId=%u NOT in boundTextures! "
                "Using terrain atlas instead. blendMode=%u\n",
                cmd.textureId, cmd.blendMode);
        shouldSampleTex = false;
      }
    } else {
      // No texture available — log once then skip
      static bool loggedNoTex = false;
      if (!loggedNoTex) {
        NSLog(@"[MetalRender] OVERLAY STEP 5 WARNING: no texture for draw (texId=%u, atlas=%p)", 
              cmd.textureId, ctx->atlasTexture);
        loggedNoTex = true;
      }
    }

    [enc drawPrimitives:MTLPrimitiveTypeTriangle
            vertexStart:cmd.vertexOffset / 32
            vertexCount:cmd.vertexCount];
    drawCount++;
  }

  [enc endEncoding];

  // Present and commit
  [cmdBuf presentDrawable:ctx->currentDrawable];
  ctx->previousCommandBuffer = cmdBuf;
  [cmdBuf commit];
  ctx->currentDrawable = nil;

  NSLog(@"[MetalRender] OVERLAY STEP 6 DONE: flushed %u draws (%zu bytes), atlas=%p", 
        drawCount, ctx->genericVertexCursor, ctx->atlasTexture);
  static uint64_t flushCount = 0;
  if ((flushCount++ % 300) == 0) {
    fprintf(stderr, "[MetalRender] nDrawOverlay: Flushed %u generic draws (%zu bytes), frame %llu\n",
            drawCount, ctx->genericVertexCursor, gFrameCount);
  }

  ctx->genericDraws.clear();
  ctx->genericVertexCursor = 0;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nOnWorldLoaded(JNIEnv *, jclass,
                                                              jlong) {}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nOnWorldUnloaded(JNIEnv *,
                                                                jclass, jlong) {
}

JNIEXPORT void JNICALL Java_com_metalrender_nativebridge_NativeBridge_nDestroy(
    JNIEnv *, jclass, jlong handle) {
  std::lock_guard<std::mutex> lock(gMutex);
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  destroyMetalFXResources(ctx);
  
  // Clean up CAMetalLayer (FEATURE_004)
  if (ctx->metalLayer) {
    ctx->metalLayer = nil;
    fprintf(stderr, "[MetalRender] CAMetalLayer released\n");
  }
  
  if (ctx->currentCommandBuffer) {
    ctx->currentCommandBuffer = nil;
  }
  
  if (ctx->currentRenderEncoder) {
    ctx->currentRenderEncoder = nil;
  }
  
  if (ctx->currentDrawable) {
    ctx->currentDrawable = nil;
  }
  
  // Clean up test triangle resources (FEATURE_005)
  if (ctx->testVertexBuffer) {
    ctx->testVertexBuffer = nil;
    fprintf(stderr, "[MetalRender] Test vertex buffer released\n");
  }
  
  if (ctx->testIndexBuffer) {
    ctx->testIndexBuffer = nil;
    fprintf(stderr, "[MetalRender] Test index buffer released\n");
  }
  
  if (ctx->aabbBuffer)
    ctx->aabbBuffer = nil;
  if (ctx->occlusionResultBuffer)
    ctx->occlusionResultBuffer = nil;
  if (ctx->occlusionConstants)
    ctx->occlusionConstants = nil;
  if (ctx->occlusionPipeline)
    ctx->occlusionPipeline = nil;
  if (ctx->library)
    ctx->library = nil;
  if (ctx->graphicsQueue)
    ctx->graphicsQueue = nil;
  if (ctx->computeQueue)
    ctx->computeQueue = nil;
  if (ctx->persistentBuffer)
    ctx->persistentBuffer = nil;
  if (ctx->indirectArgs)
    ctx->indirectArgs = nil;
  if (ctx->device)
    ctx->device = nil;
  delete ctx;
}

JNIEXPORT jstring JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetDeviceName(JNIEnv *env,
                                                              jclass,
                                                              jlong handle) {
  printf("[MetalRender Native] nGetDeviceName called with handle: %lld\n",
         (long long)handle);
  MetalContext *ctx = getContext(handle);
  if (!ctx) {
    printf("[MetalRender Native] getContext returned null.\n");
    return env->NewStringUTF("Unknown");
  }
  printf("[MetalRender Native] Device name: %s\n", ctx->deviceName.c_str());
  return env->NewStringUTF(ctx->deviceName.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetNativeVersion(JNIEnv *env,
                                                                  jclass) {
  return env->NewStringUTF(NATIVE_VERSION);
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSupportsIndirect(JNIEnv *,
                                                                 jclass) {
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSupportsMeshShaders(JNIEnv *,
                                                                    jclass) {
  id<MTLDevice> device = MTLCreateSystemDefaultDevice();
  bool supported = supportsMeshShaders(device);
  device = nil;
  return supported ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSupportsMetalFX(JNIEnv *,
                                                                jclass) {
  return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSupportsHiZ(JNIEnv *, jclass,
                                                            jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return JNI_FALSE;
  return ensureOcclusionPipeline(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetMetalFXEnabled(
    JNIEnv *, jclass, jlong handle, jboolean enabled) {}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nConfigureMetalFX(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jfloat scale) {}

JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nEnsureHiZ(JNIEnv *, jclass,
                                                          jlong handle,
                                                          jint width,
                                                          jint height) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || width <= 0 || height <= 0)
    return 0;
  HiZResources *hiz = new HiZResources();
  hiz->width = static_cast<uint32_t>(width);
  hiz->height = static_cast<uint32_t>(height);
  MTLTextureDescriptor *depthDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
                                   width:width
                                  height:height
                               mipmapped:YES];
  depthDesc.storageMode = MTLStorageModePrivate;
  depthDesc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
  hiz->depthTexture = [ctx->device newTextureWithDescriptor:depthDesc];
  MTLTextureDescriptor *pyramidDesc = [MTLTextureDescriptor
      texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float
                                   width:width
                                  height:height
                               mipmapped:YES];
  pyramidDesc.storageMode = MTLStorageModePrivate;
  pyramidDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
  hiz->pyramidTexture = [ctx->device newTextureWithDescriptor:pyramidDesc];
  return reinterpret_cast<jlong>(hiz);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyHiZ(JNIEnv *, jclass,
                                                           jlong,
                                                           jlong hizHandle) {
  HiZResources *hiz = getHiZ(hizHandle);
  destroyHiZImpl(hiz);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nOcclusionBegin(
    JNIEnv *env, jclass, jlong handle, jlong, jfloatArray matrixArray) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ensureOcclusionPipeline(ctx);
  ctx->hasViewProj = false;
  if (matrixArray && env->GetArrayLength(matrixArray) >= 16) {
    env->GetFloatArrayRegion(matrixArray, 0, 16, ctx->viewProj);
    ctx->hasViewProj = true;
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nOcclusionEvaluate(
    JNIEnv *env, jclass, jlong handle, jlong hizHandle, jobject aabbBuffer,
    jint queryCount, jobject resultBuffer) {
  MetalContext *ctx = getContext(handle);
  HiZResources *hiz = getHiZ(hizHandle);
  if (!ctx || queryCount <= 0) {
    return;
  }
  if (!ensureOcclusionPipeline(ctx)) {
    return;
  }
  void *aabbPtr = env->GetDirectBufferAddress(aabbBuffer);
  void *resultPtr = env->GetDirectBufferAddress(resultBuffer);
  if (!aabbPtr || !resultPtr) {
    return;
  }
  struct OcclusionConstants {
    uint32_t count;
    float hiZWidth;
    float hiZHeight;
  };

  size_t aabbBytes = static_cast<size_t>(queryCount) * sizeof(float) * 6;
  ctx->aabbBuffer =
      ensureBufferCapacity(ctx->device, ctx->aabbBuffer, aabbBytes);
  ctx->occlusionResultBuffer =
      ensureBufferCapacity(ctx->device, ctx->occlusionResultBuffer, queryCount);
  ctx->occlusionConstants = ensureBufferCapacity(
      ctx->device, ctx->occlusionConstants, sizeof(OcclusionConstants));

  memcpy([ctx->aabbBuffer contents], aabbPtr, aabbBytes);

  OcclusionConstants *constantPtr = reinterpret_cast<OcclusionConstants *>(
      [ctx->occlusionConstants contents]);
  constantPtr->count = static_cast<uint32_t>(queryCount);
  constantPtr->hiZWidth =
      hiz && hiz->pyramidTexture ? (float)[hiz->pyramidTexture width] : 1.0f;
  constantPtr->hiZHeight =
      hiz && hiz->pyramidTexture ? (float)[hiz->pyramidTexture height] : 1.0f;

  id<MTLCommandBuffer> commandBuffer = [ctx->computeQueue commandBuffer];
  if (!commandBuffer) {
    return;
  }
  id<MTLComputeCommandEncoder> encoder = [commandBuffer computeCommandEncoder];
  if (!encoder) {
    [commandBuffer commit];
    [commandBuffer waitUntilCompleted];
    return;
  }
  [encoder setComputePipelineState:ctx->occlusionPipeline];
  [encoder setBuffer:ctx->aabbBuffer offset:0 atIndex:0];
  [encoder setBuffer:ctx->occlusionConstants offset:0 atIndex:3];
  const float *matrixPtr = ctx->hasViewProj ? ctx->viewProj : nullptr;
  float fallback[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  if (!matrixPtr)
    matrixPtr = fallback;
  [encoder setBytes:matrixPtr length:sizeof(float) * 16 atIndex:1];
  [encoder setBuffer:ctx->occlusionResultBuffer offset:0 atIndex:2];

  if (hiz && hiz->pyramidTexture) {
    [encoder setTexture:hiz->pyramidTexture atIndex:0];
  }

  NSUInteger threadCount = ctx->occlusionPipeline.threadExecutionWidth;
  NSUInteger groups = (queryCount + threadCount - 1) / threadCount;
  MTLSize grid = MTLSizeMake(groups * threadCount, 1, 1);
  MTLSize threadsPerGroup = MTLSizeMake(threadCount, 1, 1);
  [encoder dispatchThreads:grid threadsPerThreadgroup:threadsPerGroup];
  [encoder endEncoding];

  [commandBuffer commit];
  [commandBuffer waitUntilCompleted];

  memcpy(resultPtr, [ctx->occlusionResultBuffer contents], queryCount);
}

JNIEXPORT jobject JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nMapPersistentBuffer(
    JNIEnv *env, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->persistentBuffer)
    return nullptr;
  return env->NewDirectByteBuffer([ctx->persistentBuffer contents],
                                  ctx -> persistentCapacity);
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPersistentCapacity(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return 0;
  return static_cast<jint>(ctx->persistentCapacity);
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPersistentAlign(JNIEnv *,
                                                                jclass,
                                                                jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return 256;
  return static_cast<jint>(ctx->persistentAlignment);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPersistentAdvance(JNIEnv *,
                                                                  jclass,
                                                                  jlong handle,
                                                                  jint bytes) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  size_t value = static_cast<size_t>(std::max(0, bytes));
  if (ctx->persistentCapacity == 0) {
    value = 0;
  } else if (value > ctx->persistentCapacity) {
    value = value % ctx->persistentCapacity;
  }
  ctx->persistentCursor = value;
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nClearIndirectCommands(
    JNIEnv *, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ctx->currentIndirectCount = 0;
  if (ctx->indirectArgs) {
    memset([ctx->indirectArgs contents], 0, [ctx->indirectArgs length]);
  }
  // Clear per-draw data buffer every frame to prevent ghost geometry
  if (ctx->drawDataBuffer) {
    memset([ctx->drawDataBuffer contents], 0, [ctx->drawDataBuffer length]);
  }
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nQueueIndirectDraw(
    JNIEnv *, jclass, jlong handle, jint commandIndex, jlong vertexOffset, jlong,
    jint vertexCount, jint originX, jint originY, jint instanceCount, jint originZ) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->indirectArgs || !ctx->drawDataBuffer)
    return;
  if (commandIndex < 0 ||
      commandIndex >= static_cast<jint>(ctx->maxIndirectCommands))
    return;

  // Indexed indirect args (MTLDrawIndexedPrimitivesIndirectArguments)
  struct IndexedIndirectArgs {
    uint32_t indexCount;
    uint32_t instanceCount;
    uint32_t indexStart;
    int32_t  baseVertex;
    uint32_t baseInstance;
  };

  // Compute baseVertex from byte offset and 20-byte Sodium COMPACT stride
  static constexpr uint32_t VERTEX_STRIDE = 20;
  uint32_t baseVertex = (vertexOffset > 0)
      ? static_cast<uint32_t>(vertexOffset / VERTEX_STRIDE)
      : 0u;

  // Sodium stores quads (4 verts each). Convert vertex count to index count.
  // Each quad = 4 vertices → 6 indices (2 triangles)
  uint32_t numQuads = static_cast<uint32_t>(vertexCount) / 4u;
  uint32_t indexCount = numQuads * 6u;

  IndexedIndirectArgs *args =
      reinterpret_cast<IndexedIndirectArgs *>([ctx->indirectArgs contents]);
  args[commandIndex].indexCount = indexCount;
  args[commandIndex].instanceCount =
      static_cast<uint32_t>(instanceCount > 0 ? instanceCount : 1);
  args[commandIndex].indexStart = 0;  // always start from index 0; baseVertex offsets into vertex buffer
  args[commandIndex].baseVertex = static_cast<int32_t>(baseVertex);
  args[commandIndex].baseInstance = static_cast<uint32_t>(commandIndex);

  // FEATURE_012: Store per-draw chunk origin for shader lookup
  struct DrawData {
    float originX;
    float originY;
    float originZ;
    float padding;
  };
  DrawData *drawData =
      reinterpret_cast<DrawData *>([ctx->drawDataBuffer contents]);
  drawData[commandIndex].originX = static_cast<float>(originX);
  drawData[commandIndex].originY = static_cast<float>(originY);
  drawData[commandIndex].originZ = static_cast<float>(originZ);
  drawData[commandIndex].padding = 0.0f;

  uint32_t nextCount = static_cast<uint32_t>(commandIndex + 1);
  ctx->currentIndirectCount = std::max(ctx->currentIndirectCount, nextCount);
}

JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nExecuteIndirect(JNIEnv *,
                                                                jclass,
                                                                jlong handle,
                                                                jint passIndex) {
  MetalContext *ctx = getContext(handle);

  // Validate context and resources
  if (!ctx || !ctx->currentRenderEncoder || !ctx->indirectArgs || !ctx->device) {
    fprintf(stderr, "[MetalRender] [nExecuteIndirect] ERROR: missing context/encoder/args/device\n");
    return 0;
  }

  uint32_t commandCount = ctx->currentIndirectCount;
  if (commandCount > ctx->maxIndirectCommands) {
    commandCount = ctx->maxIndirectCommands;
  }

  // ============================================================================
  // FEATURE_012: Execute indexed indirect draw commands (quad→triangle)
  // MTLDrawIndexedPrimitivesIndirectArguments: { indexCount, instanceCount, indexStart, baseVertex, baseInstance }
  // 5 x uint32 = 20 bytes per command
  // ============================================================================

  // DIAGNOSTIC: Dump first few frames' draw data
  if (gFrameCount <= 5 && commandCount > 0) {
    struct IndexedIndirectArgsD { uint32_t indexCount; uint32_t instanceCount; uint32_t indexStart; int32_t baseVertex; uint32_t baseInstance; };
    struct DrawDataD { float originX, originY, originZ, padding; };
    IndexedIndirectArgsD *argsD = reinterpret_cast<IndexedIndirectArgsD*>([ctx->indirectArgs contents]);
    DrawDataD *ddD = reinterpret_cast<DrawDataD*>([ctx->drawDataBuffer contents]);

    NSLog(@"[MetalRender] FRAME %llu: %u draws, persistentBuf=%p (%lu bytes)",
          gFrameCount, commandCount, ctx->persistentBuffer,
          (unsigned long)[ctx->persistentBuffer length]);
    uint32_t dumpCount = std::min(commandCount, 5u);
    for (uint32_t i = 0; i < dumpCount; ++i) {
      NSLog(@"  draw[%u]: idxCount=%u inst=%u idxStart=%u baseVtx=%d baseInst=%u origin=(%.1f,%.1f,%.1f)",
            i, argsD[i].indexCount, argsD[i].instanceCount, argsD[i].indexStart,
            argsD[i].baseVertex, argsD[i].baseInstance,
            ddD[i].originX, ddD[i].originY, ddD[i].originZ);
    }
    // Dump first draw's raw vertex data
    if (ctx->persistentBuffer && argsD[0].baseVertex >= 0) {
      uint32_t *vtx = reinterpret_cast<uint32_t*>([ctx->persistentBuffer contents]);
      uint32_t vbase = static_cast<uint32_t>(argsD[0].baseVertex) * 5u;
      NSLog(@"  vtx[0] @baseVtx=%d: posHi=0x%08x posLo=0x%08x color=0x%08x tex=0x%08x light=0x%08x",
            argsD[0].baseVertex, vtx[vbase], vtx[vbase+1], vtx[vbase+2], vtx[vbase+3], vtx[vbase+4]);
      if (argsD[0].indexCount >= 6) { // at least 1 quad = 4 verts
        NSLog(@"  vtx[1]: posHi=0x%08x posLo=0x%08x color=0x%08x",
              vtx[vbase+5], vtx[vbase+6], vtx[vbase+7]);
      }
    }
    NSLog(@"  viewProj[0-3]=(%.4f,%.4f,%.4f,%.4f) [12-15]=(%.4f,%.4f,%.4f,%.4f)",
          ctx->viewProj[0], ctx->viewProj[1], ctx->viewProj[2], ctx->viewProj[3],
          ctx->viewProj[12], ctx->viewProj[13], ctx->viewProj[14], ctx->viewProj[15]);
  }

  if (commandCount > 0) {
    const char *testModeEnv = getenv("TEST_TRIANGLE");
    bool testModeEnabled = testModeEnv != nullptr && strcmp(testModeEnv, "true") == 0;

    if (testModeEnabled && ctx->currentIndexBuffer) {
      // Test triangle path: use indexed drawing (legacy)
      struct IndexedIndirectArgs {
        uint32_t indexCount;
        uint32_t instanceCount;
        uint32_t indexStart;
        uint32_t baseVertex;
        uint32_t baseInstance;
      };
      // Test triangle writes its own indexed indirect command
      IndexedIndirectArgs cmd = {3, 1, 0, 0, 0};
      void *ptr = [ctx->indirectArgs contents];
      if (ptr) memcpy(ptr, &cmd, sizeof(cmd));

      [ctx->currentRenderEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                             indexType:MTLIndexTypeUInt32
                                           indexBuffer:ctx->currentIndexBuffer
                                     indexBufferOffset:0
                                        indirectBuffer:ctx->indirectArgs
                                  indirectBufferOffset:0];
    } else if (ctx->terrainPipeline && ctx->quadIndexBuffer) {
      // FEATURE_012: Indexed indirect draws (quad→triangle via shared index buffer)
      const uint32_t stride = sizeof(uint32_t) * 5;  // 20 bytes per indexed command

      for (uint32_t i = 0; i < commandCount; ++i) {
        [ctx->currentRenderEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                                              indexType:MTLIndexTypeUInt32
                                            indexBuffer:ctx->quadIndexBuffer
                                      indexBufferOffset:0
                                         indirectBuffer:ctx->indirectArgs
                                   indirectBufferOffset:i * stride];
      }
    } else {
      if (shouldLog()) {
        fprintf(stderr, "[MetalRender] [nExecuteIndirect] skipped %u draws: terrainPipeline is NULL\n", commandCount);
      }
    }

    if (shouldLog()) {
      NSLog(@"[MetalRender] [nExecuteIndirect] %u draws executed (pass=%d), terrainPipeline=%p, quadIndexBuffer=%p",
              commandCount, passIndex, ctx->terrainPipeline, ctx->quadIndexBuffer);
    }
  }

  // ============================================================================
  // END TERRAIN RENDER PASS (presentation deferred to nDrawOverlay)
  // ============================================================================
  switch (passIndex) {
    case 0:
    case 1:
      if (ctx->currentRenderEncoder) {
        [ctx->currentRenderEncoder endEncoding];
        ctx->currentRenderEncoder = nil;
      }
      if (ctx->currentCommandBuffer) {
        // Commit terrain draws to GPU but do NOT present yet.
        // The drawable stays alive for nDrawOverlay to composite GL UI on top.
        [ctx->currentCommandBuffer commit];
        ctx->currentCommandBuffer = nil;
      }
      // NOTE: currentDrawable is intentionally kept alive for nDrawOverlay
      break;
    default:
      fprintf(stderr, "[MetalRender] WARNING: Unknown pass index %d\n", passIndex);
      break;
  }

  return static_cast<jint>(commandCount);
}

JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nSetTemporalJitter(
    JNIEnv *, jclass, jlong handle, jfloat jitterX, jfloat jitterY,
    jfloat blendFactor) {
  MetalContext *ctx = getContext(handle);
  if (!ctx)
    return;
  ctx->temporalJitterX = jitterX;
  ctx->temporalJitterY = jitterY;
  ctx->temporalBlend = blendFactor;
#if METALRENDER_HAS_METALFX
  if (ctx->metalFxScaler) {
    ctx->metalFxScaler.jitterOffsetX = jitterX;
    ctx->metalFxScaler.jitterOffsetY = jitterY;
  }
#endif
}

// FEATURE_004: CAMetalLayer Integration - JNI Wrappers

// Initialize CAMetalLayer for direct window presentation
// windowPtr: Long value containing pointer to NSWindow (passed from Java via JNI)
// Returns: true if initialization successful, false otherwise
JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nInitializeCAMetalLayer(
    JNIEnv *env, jclass, jlong handle, jlong windowPtr) {
  (void)env;  // Unused parameter
  
  MetalContext *ctx = getContext(handle);
  if (!ctx) {
    fprintf(stderr, "[MetalRender] nInitializeCAMetalLayer: Invalid context handle\n");
    return JNI_FALSE;
  }

  // TODO: Implement full CAMetalLayer attachment
  fprintf(stderr, "[MetalRender] nInitializeCAMetalLayer: STUB - returning true\n");
  return JNI_TRUE;
}

// Handle window resize events
// width, height: New window dimensions in pixels
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nOnWindowResized(
    JNIEnv *env, jclass, jlong handle, jint width, jint height) {
  (void)env;  // Unused parameter
  
  MetalContext *ctx = getContext(handle);
  if (!ctx) {
    fprintf(stderr, "[MetalRender] nOnWindowResized: Invalid context handle\n");
    return;
  }

  if (width <= 0 || height <= 0) {
    fprintf(stderr, "[MetalRender] nOnWindowResized: Invalid dimensions (%d, %d)\n", width, height);
    return;
  }

  // FEATURE_010: Delegate to the same logic as nResize
  uint32_t newWidth = (uint32_t)width;
  uint32_t newHeight = (uint32_t)height;
  ctx->currentWidth = newWidth;
  ctx->currentHeight = newHeight;

  // Recreate depth texture at new size
  ensureDepthTexture(ctx, newWidth, newHeight);

  // Invalidate offscreen color texture
  ctx->offscreenColorTexture = nil;

  if (ctx->metalLayer) {
    NSView *overlay = ctx->metalOverlay;
    dispatch_async(dispatch_get_main_queue(), ^{
      ctx->metalLayer.drawableSize = CGSizeMake(newWidth, newHeight);
      if (overlay && overlay.superview) {
        overlay.frame = overlay.superview.bounds;
        ctx->metalLayer.frame = overlay.bounds;
      }
    });
  }

  fprintf(stderr, "[MetalRender] nOnWindowResized: updated to %dx%d (depthTexture=%p)\n", width, height, ctx->depthTexture);
}

// ============================================================================
// CRITICAL IMPLEMENTATIONS: CAMetalLayer Surface Management
// ============================================================================

// FEATURE_004/005: nAttachSurface - Attach CAMetalLayer to window
// THIS IS THE CRITICAL FIX for "Native library missing nAttachSurface" error
// Parameters:
//   handle: MetalContext pointer
//   cocoaWindow: NSWindow pointer (from Java via JNI)
// Returns: JNI_TRUE if successful, JNI_FALSE if failed
JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nAttachSurface(
    JNIEnv *, jclass, jlong handle, jlong cocoaWindow) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx) {
    fprintf(stderr, "[MetalRender] nAttachSurface: Invalid context handle\n");
    return JNI_FALSE;
  }
  
  if (cocoaWindow == 0) {
    fprintf(stderr, "[MetalRender] nAttachSurface: Invalid cocoa window pointer\n");
    return JNI_FALSE;
  }
  
  // Reinterpret the Java long as an NSWindow pointer (from LWJGL/Cocoa)
  // Use __bridge to safely handle ARC
  NSWindow *window = (__bridge NSWindow *)(void *)cocoaWindow;
  if (!window) {
    fprintf(stderr, "[MetalRender] nAttachSurface: Cannot cast cocoaWindow to NSWindow\n");
    return JNI_FALSE;
  }
  
  fprintf(stderr, "[MetalRender] nAttachSurface: window=%p, creating CAMetalLayer\n", window);
  
  // Create CAMetalLayer and attach to window manually
  // (initCAMetalLayer is not declared in this scope)
  CAMetalLayer *layer = [CAMetalLayer layer];
  if (!layer) {
    fprintf(stderr, "[MetalRender] nAttachSurface: Failed to create CAMetalLayer\n");
    return JNI_FALSE;
  }
  
  // Configure layer
  layer.device = ctx->device;
  layer.opaque = YES;  // Metal is the final opaque output
  
  // Set drawable size from window bounds
  NSRect windowFrame = [window frame];
  if (windowFrame.size.width <= 0 || windowFrame.size.height <= 0) {
    fprintf(stderr, "[MetalRender] nAttachSurface: Invalid window dimensions (%.0f x %.0f)\n", 
            windowFrame.size.width, windowFrame.size.height);
    return JNI_FALSE;
  }
  
  CGSize drawableSize = CGSizeMake(windowFrame.size.width, windowFrame.size.height);
  layer.drawableSize = drawableSize;
  ctx->currentWidth = static_cast<uint32_t>(drawableSize.width);
  ctx->currentHeight = static_cast<uint32_t>(drawableSize.height);
  
  // Add layer to window's content view
  NSView *contentView = [window contentView];
  if (!contentView) {
    fprintf(stderr, "[MetalRender] nAttachSurface: No content view for window\n");
    return JNI_FALSE;
  }
  
  // Create an overlay NSView to host the Metal layer ON TOP of GL content.
  // Using addSublayer: puts it behind the NSOpenGLContext framebuffer.
  // A subview sits above the parent view's OpenGL rendering.
  NSView *overlay = [[NSView alloc] initWithFrame:contentView.bounds];
  overlay.wantsLayer = YES;
  overlay.layerContentsRedrawPolicy = NSViewLayerContentsRedrawOnSetNeedsDisplay;
  layer.frame = overlay.bounds;
  layer.contentsScale = contentView.window.backingScaleFactor;
  [overlay setLayer:layer];
  overlay.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
  [contentView addSubview:overlay];
  
  // Store in context
  ctx->metalLayer = layer;
  ctx->metalOverlay = overlay;

  // FEATURE_010: Create initial depth texture
  ensureDepthTexture(ctx, ctx->currentWidth, ctx->currentHeight);
  
  fprintf(stderr, "[MetalRender] nAttachSurface: SUCCESS - CAMetalLayer in overlay NSView on top of GL (window size: %ux%u, depthTexture=%p)\n", 
          ctx->currentWidth, ctx->currentHeight, ctx->depthTexture);
  return JNI_TRUE;
}

// FEATURE_004/005: nDetachSurface - Detach and cleanup CAMetalLayer
// Removes the CAMetalLayer from the window and cleans up resources
// Parameters:
//   handle: MetalContext pointer
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDetachSurface(
    JNIEnv *, jclass, jlong handle) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);
  if (!ctx) return;
  
  fprintf(stderr, "[MetalRender] nDetachSurface: SIMPLIFIED IMPLEMENTATION\n");
  // TODO: Implement cleanup
}

// ============================================================================
// STUB IMPLEMENTATIONS: Synchronization and Memory Management
// These are placeholder implementations. Full functionality will be added
// in a future update once the rendering pipeline is validated.
// ============================================================================

// ============================================================================
// FEATURE_011: GPU Fence Synchronization via MTLSharedEvent
// MTLSharedEvent provides CPU/GPU synchronization with a monotonic counter.
// Each "fence" is a pair: (MTLSharedEvent*, signalValue).
// We pack them into a simple struct and return the pointer as jlong.
// ============================================================================

struct MetalFence {
  id<MTLSharedEvent> event;
  uint64_t signalValue;
};

// Monotonic counter for fence signal values
static std::atomic<uint64_t> gFenceCounter{1};

// nCreateFence - Create a GPU fence using MTLSharedEvent
// Returns a pointer to a MetalFence struct (cast to jlong), or 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nCreateFence(
    JNIEnv *, jclass, jlong handle) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);

  if (!ctx || !ctx->device) {
    fprintf(stderr, "[MetalRender] nCreateFence: Invalid context or device\n");
    return 0L;
  }

  id<MTLSharedEvent> event = [ctx->device newSharedEvent];
  if (!event) {
    fprintf(stderr, "[MetalRender] nCreateFence: Failed to create MTLSharedEvent\n");
    return 0L;
  }

  uint64_t signalVal = gFenceCounter.fetch_add(1, std::memory_order_relaxed);

  // Enqueue a signal on the next committed command buffer.
  // The caller is expected to create the fence during or just before
  // command buffer submission.  We signal on a fresh command buffer
  // so the fence is immediately "in flight".
  id<MTLCommandBuffer> cmdBuf = [ctx->graphicsQueue commandBuffer];
  if (cmdBuf) {
    [cmdBuf encodeSignalEvent:event value:signalVal];
    [cmdBuf commit];
  }

  MetalFence *fence = new MetalFence{event, signalVal};
  fprintf(stderr, "[MetalRender] nCreateFence: Created fence %p (value=%llu)\n", fence, signalVal);
  return reinterpret_cast<jlong>(fence);
}

// nPollFence - Non-blocking check: has GPU reached the fence value?
JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nPollFence(
    JNIEnv *, jclass, jlong handle, jlong fencePtr) {
  (void)handle;
  if (fencePtr == 0) return JNI_TRUE; // null fence = always ready

  auto *fence = reinterpret_cast<MetalFence *>(fencePtr);
  bool reached = (fence->event.signaledValue >= fence->signalValue);
  return reached ? JNI_TRUE : JNI_FALSE;
}

// nWaitFence - Block CPU until GPU signals the fence, with timeout.
// Returns: 1 = success, 0 = timeout/error
JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nWaitFence(
    JNIEnv *, jclass, jlong handle, jlong fencePtr, jlong timeoutNanos) {
  (void)handle;
  if (fencePtr == 0) return 1; // null fence = immediate success

  auto *fence = reinterpret_cast<MetalFence *>(fencePtr);

  // Fast path: already signaled
  if (fence->event.signaledValue >= fence->signalValue) {
    return 1;
  }

  // Use a dispatch semaphore to block-wait with timeout
  dispatch_semaphore_t sem = dispatch_semaphore_create(0);
  MTLSharedEventListener *listener =
      [[MTLSharedEventListener alloc] initWithDispatchQueue:
          dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0)];

  [fence->event notifyListener:listener
                       atValue:fence->signalValue
                         block:^(id<MTLSharedEvent>, uint64_t) {
    dispatch_semaphore_signal(sem);
  }];

  int64_t timeoutDispatch;
  if (timeoutNanos <= 0) {
    timeoutDispatch = DISPATCH_TIME_FOREVER;
  } else {
    timeoutDispatch = (int64_t)timeoutNanos;
  }

  long result = dispatch_semaphore_wait(sem,
      dispatch_time(DISPATCH_TIME_NOW, timeoutDispatch));

  return (result == 0) ? 1 : 0;
}

// nDestroyFence - Release GPU fence resources
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nDestroyFence(
    JNIEnv *, jclass, jlong handle, jlong fencePtr) {
  (void)handle;
  if (fencePtr == 0) return;

  auto *fence = reinterpret_cast<MetalFence *>(fencePtr);
  fprintf(stderr, "[MetalRender] nDestroyFence: Releasing fence %p (value=%llu)\n",
          fence, fence->signalValue);
  fence->event = nil;  // ARC releases the MTLSharedEvent
  delete fence;
}

// nGetDeviceMemory - Get recommended max working set size (bytes)
JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetDeviceMemory(
    JNIEnv *, jclass, jlong handle) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);

  if (!ctx || !ctx->device) {
    fprintf(stderr, "[MetalRender] nGetDeviceMemory: Invalid context or device\n");
    return 0L;
  }

  // recommendedMaxWorkingSetSize gives the practical VRAM budget on macOS
  return (jlong)[ctx->device recommendedMaxWorkingSetSize];
}

// nGetMemoryUsage - Get current GPU memory allocated by this process (bytes)
JNIEXPORT jlong JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nGetMemoryUsage(
    JNIEnv *, jclass, jlong handle) {
  auto *ctx = reinterpret_cast<MetalContext *>(handle);

  if (!ctx || !ctx->device) {
    fprintf(stderr, "[MetalRender] nGetMemoryUsage: Invalid context or device\n");
    return 0L;
  }

  // currentAllocatedSize tracks live Metal buffer/texture allocations
  return (jlong)[ctx->device currentAllocatedSize];
}

// ============================================================================
// FEATURE_009: Atlas Texture Upload
// Creates a Metal texture from BGRA pixel data and a sampler state for terrain
// rendering. Called from Java when the sprite atlas is captured/updated.
// ============================================================================
JNIEXPORT jboolean JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nUploadAtlas(
    JNIEnv *env, jclass, jlong handle, jobject pixelBuffer, jint width, jint height) {
  MetalContext *ctx = getContext(handle);
  if (!ctx || !ctx->device) {
    fprintf(stderr, "[MetalRender] nUploadAtlas: Invalid context or device\n");
    return JNI_FALSE;
  }

  if (!pixelBuffer) {
    fprintf(stderr, "[MetalRender] nUploadAtlas: pixelBuffer is null\n");
    return JNI_FALSE;
  }

  if (width <= 0 || height <= 0) {
    fprintf(stderr, "[MetalRender] nUploadAtlas: Invalid dimensions %dx%d\n", width, height);
    return JNI_FALSE;
  }

  // Get direct buffer address from Java ByteBuffer
  void *pixelData = env->GetDirectBufferAddress(pixelBuffer);
  jlong bufferCapacity = env->GetDirectBufferCapacity(pixelBuffer);
  jlong expectedSize = (jlong)width * (jlong)height * 4;  // BGRA = 4 bytes per pixel

  if (!pixelData) {
    fprintf(stderr, "[MetalRender] nUploadAtlas: Failed to get direct buffer address\n");
    return JNI_FALSE;
  }

  if (bufferCapacity < expectedSize) {
    fprintf(stderr, "[MetalRender] nUploadAtlas: Buffer too small (%ld < %ld)\n",
            bufferCapacity, expectedSize);
    return JNI_FALSE;
  }

  fprintf(stderr, "[MetalRender] nUploadAtlas: Uploading %dx%d atlas (%ld bytes)\n",
          width, height, expectedSize);

  // Create Metal texture descriptor
  MTLTextureDescriptor *texDesc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                    width:(NSUInteger)width
                                                                                   height:(NSUInteger)height
                                                                                mipmapped:NO];
  texDesc.usage = MTLTextureUsageShaderRead;
  texDesc.storageMode = MTLStorageModeShared;  // CPU-writable for upload

  // Create the texture
  id<MTLTexture> newTexture = [ctx->device newTextureWithDescriptor:texDesc];
  if (!newTexture) {
    fprintf(stderr, "[MetalRender] nUploadAtlas: Failed to create MTLTexture\n");
    return JNI_FALSE;
  }
  [newTexture setLabel:@"Terrain Atlas Texture"];

  // Upload BGRA pixel data to texture
  MTLRegion region = MTLRegionMake2D(0, 0, (NSUInteger)width, (NSUInteger)height);
  NSUInteger bytesPerRow = (NSUInteger)width * 4;
  [newTexture replaceRegion:region
                mipmapLevel:0
                  withBytes:pixelData
                bytesPerRow:bytesPerRow];

  fprintf(stderr, "[MetalRender] nUploadAtlas: Texture data uploaded (%dx%d, %lu bytes/row)\n",
          width, height, (unsigned long)bytesPerRow);

  // Create sampler state (nearest-neighbor for Minecraft's pixel art style)
  if (!ctx->atlasSampler) {
    MTLSamplerDescriptor *samplerDesc = [[MTLSamplerDescriptor alloc] init];
    samplerDesc.minFilter = MTLSamplerMinMagFilterNearest;
    samplerDesc.magFilter = MTLSamplerMinMagFilterNearest;
    samplerDesc.mipFilter = MTLSamplerMipFilterNotMipmapped;
    samplerDesc.sAddressMode = MTLSamplerAddressModeClampToEdge;
    samplerDesc.tAddressMode = MTLSamplerAddressModeClampToEdge;
    samplerDesc.label = @"Atlas Sampler (Nearest)";

    ctx->atlasSampler = [ctx->device newSamplerStateWithDescriptor:samplerDesc];
    if (!ctx->atlasSampler) {
      fprintf(stderr, "[MetalRender] nUploadAtlas: Failed to create sampler state\n");
      // Continue anyway — texture is still valid
    } else {
      fprintf(stderr, "[MetalRender] nUploadAtlas: Sampler state created (nearest filtering)\n");
    }
  }

  // Replace old atlas texture (if any) and store the new one
  ctx->atlasTexture = newTexture;

  fprintf(stderr, "[MetalRender] nUploadAtlas: SUCCESS - Atlas texture %dx%d uploaded to GPU\n",
          width, height);
  return JNI_TRUE;
}

// ============================================================================
// Native→Java logging bridge: routes fprintf output to Java's latest.log
// ============================================================================
JNIEXPORT void JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nLogToJava(
    JNIEnv *env, jclass, jlong handle, jint level, jstring message) {
  (void)handle;
  if (!message) return;
  const char *msg = env->GetStringUTFChars(message, nullptr);
  if (!msg) return;
  // Also echo to stderr for native-side debugging
  fprintf(stderr, "[MetalRender-Native] %s\n", msg);
  env->ReleaseStringUTFChars(message, msg);
}

// ============================================================================
// Verify atlas texture: check ctx->atlasTexture != nil, sample pixel (0,0),
// return the BGRA value as a jint for Java-side verification.
// ============================================================================
JNIEXPORT jint JNICALL
Java_com_metalrender_nativebridge_NativeBridge_nVerifyAtlasTexture(
    JNIEnv *env, jclass, jlong handle) {
  MetalContext *ctx = getContext(handle);
  if (!ctx) {
    fprintf(stderr, "[MetalRender] nVerifyAtlasTexture: null context\n");
    return -1;
  }
  if (!ctx->atlasTexture) {
    fprintf(stderr, "[MetalRender] nVerifyAtlasTexture: atlasTexture is NULL — atlas not uploaded!\n");
    return -2;
  }

  // Read pixel (0,0) from the atlas texture
  uint8_t pixel[4] = {0, 0, 0, 0};  // BGRA
  MTLRegion region = MTLRegionMake2D(0, 0, 1, 1);
  [ctx->atlasTexture getBytes:pixel
                  bytesPerRow:4
                   fromRegion:region
                  mipmapLevel:0];

  fprintf(stderr, "[MetalRender] nVerifyAtlasTexture: atlas=%lux%lu, pixel(0,0) BGRA=(%u,%u,%u,%u)\n",
          (unsigned long)[ctx->atlasTexture width],
          (unsigned long)[ctx->atlasTexture height],
          pixel[0], pixel[1], pixel[2], pixel[3]);

  // Pack as BGRA int (same layout as memory)
  jint result = (jint)(pixel[0] | (pixel[1] << 8) | (pixel[2] << 16) | (pixel[3] << 24));
  return result;
}

}  // extern "C"

