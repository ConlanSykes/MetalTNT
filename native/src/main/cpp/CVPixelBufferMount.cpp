

// Make a CVPixelBuffer to render both OpenGL and Metal

#define GL_SILENCE_DEPRECATION
#include <AppKit/AppKit.h>
#include <CoreVideo/CVMetalTextureCache.h>
#include <CoreVideo/CoreVideo.h>
#include <IOSurface/IOSurface.h>
#include <Metal/Metal.h>
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl.h>
#include <OpenGL/glext.h>
#include <QuartzCore/QuartzCore.h>
#include <cstdlib>

#ifndef kCGLPFASupportsIOSurface
#define kCGLPFASupportsIOSurface 317
#endif

#ifndef METALTNT_DEBUG_LOGGING
#define METALTNT_DEBUG_LOGGING 1
#endif

#if METALTNT_DEBUG_LOGGING
#define DEBUG_LOG(fmt, ...) fprintf(stderr, fmt, ##__VA_ARGS__)
#else
#define DEBUG_LOG(fmt, ...)                                                    \
  do {                                                                         \
  } while (0)
#endif

// Make a CVPixelBuffer to render both OpenGL and Metal use IOSurface to create
// a shared memory buffer that can be used by both APIs.

// written in C++

#include <jni.h> // declares JNI types and macros
// keep native state for later use by attachToGameWindow/renderFrame

namespace {
// this is used to store the native state of the Java object
struct MetalState {
  CVPixelBufferRef pixelBuffer;
  CAMetalLayer *metalLayer;
  GLuint openglTexture;
  id<MTLDevice> device;
  id<MTLCommandQueue> commandQueue;
  CVMetalTextureCacheRef metalTextureCache;
  CVMetalTextureRef metalTexture;

  // Dual renderer support
  bool useMetalRenderer;
  id<MTLRenderPipelineState> metalRenderPipeline;
  id<MTLBuffer> metalVertexBuffer;
  id<MTLBuffer> metalIndexBuffer;
  id<MTLTexture> metalRenderTexture;
  MTLViewport metalViewport;

  bool initialized;

  MetalState()
      : pixelBuffer(nullptr), metalLayer(nullptr), openglTexture(0),
        device(nil), commandQueue(nil), metalTextureCache(nullptr),
        metalTexture(nullptr), useMetalRenderer(true), metalRenderPipeline(nil),
        metalVertexBuffer(nil), metalIndexBuffer(nil), metalRenderTexture(nil),
        metalViewport{0, 0, 0, 0, 0, 1}, initialized(YES) {}

  ~MetalState() {
    if (pixelBuffer) {
      CVPixelBufferRelease(pixelBuffer);
    }
    if (openglTexture) {
      // Only delete texture if we have a valid OpenGL context
      CGLContextObj cglContext = CGLGetCurrentContext();
      if (cglContext) {
        glDeleteTextures(1, &openglTexture);
        GLenum glError = glGetError();
        if (glError != GL_NO_ERROR) {
          fprintf(stderr, "OpenGL error during texture deletion: 0x%x\n",
                  glError);
        }
      } else {
        fprintf(stderr,
                "Warning: No OpenGL context available for texture cleanup\n");
      }
    }
    // Note: CAMetalLayer is managed by ARC, no manual release needed
    if (metalTexture) {
      CFRelease(metalTexture);
    }
    if (metalTextureCache) {
      CFRelease(metalTextureCache);
    }
    commandQueue = nil;
    device = nil;
  }
};

// Global state (in a real app, you'd want better state management)
static MetalState g_metalState;

} // namespace

// Forward declaration since we are using it in the initLayer function
// but it is defined after the initLayer function
static CVPixelBufferRef createIOSurfaceBackedPixelBuffer(size_t width,
                                                         size_t height);
static GLuint createOpenGLTextureFromIOSurface(CVPixelBufferRef pixelBuffer);
static void initializeMetalRenderer(id<MTLDevice> device, size_t width,
                                    size_t height);
static void renderWithMetal();

#ifdef __cplusplus
// if C++ compiler, use C linkage
extern "C" {
// use C linkage for this so that the C++ compiler can see it.

#endif
// end C++

// ------------------------------------------------------------------
// JNI = Java Native Interface
// JVM = Java Virtual Machine
// JRE = Java Runtime Environment
// Java = Object-Oriented Programming language
// C++ = Native language we’re compiling into a dylib
//
// • JNIEXPORT – export the function so the JVM can find it
// • JNICALL – use the JVM’s calling convention
// • JNIEnv* env – pointer to the JVM interface
// • jclass / jobject – the calling class or instance
// • jint/jfloat/jlong – Java primitive types mapped to C types
// ------------------------------------------------------------------

// initLayer(width, height, scale)
JNIEXPORT void JNICALL
Java_org_sykes_metaltnt_client_Metal_CVPixelBufferMount_initLayer(
    JNIEnv *env, jclass /*clazz*/, jint width, jint height, jfloat scale) {
  // TODO: initialize native resources using width, height, scale
  (void)env;
  (void)width;
  (void)height;
  (void)scale;

  // Debug output
  DEBUG_LOG("initLayer called: %dx%d scale=%.1f\n", width, height, scale);

  // Initialize the CVPixelBuffer
  CVPixelBufferRef pixelBuffer =
      createIOSurfaceBackedPixelBuffer(width, height);

  // Check if pixel buffer creation succeeded
  if (!pixelBuffer) {
    // TODO: Log error - pixel buffer creation failed
    DEBUG_LOG("ERROR: Failed to create pixel buffer\n");
    abort();
  }

  DEBUG_LOG("Pixel buffer created successfully\n");

  // Create a metal layer using CAMetalLayer
  CAMetalLayer *metalLayer = [CAMetalLayer layer];
  id<MTLDevice> device = MTLCreateSystemDefaultDevice();
  if (!device) {
    DEBUG_LOG("ERROR: Failed to create Metal device\n");
    CVPixelBufferRelease(pixelBuffer);
    abort();
  }
  metalLayer.device = device;
  metalLayer.pixelFormat =
      MTLPixelFormatBGRA8Unorm;    // Match our pixel buffer format
  metalLayer.framebufferOnly = NO; // Allow reading from the layer
  metalLayer.drawableSize = CGSizeMake(width, height); // Set the size
  metalLayer.contentsScale = scale;                    // Handle Retina displays
  metalLayer.opaque = YES; // Optimize for opaque content

  id<MTLCommandQueue> commandQueue = [device newCommandQueue];
  if (!commandQueue) {
    DEBUG_LOG("ERROR: Failed to create Metal command queue\n");
    CVPixelBufferRelease(pixelBuffer);
    abort();
  }

  // ========================================
  // STEP 4: Create OpenGL texture from IOSurface
  // ========================================
  GLuint openglTexture = createOpenGLTextureFromIOSurface(pixelBuffer);

  if (openglTexture == 0) {
    // Failed to create OpenGL texture
    DEBUG_LOG("ERROR: Failed to create OpenGL texture\n");
    CVPixelBufferRelease(pixelBuffer);
    abort();
  }

  DEBUG_LOG("OpenGL texture created successfully: %u\n", openglTexture);

  // Store our objects for later use
  g_metalState.pixelBuffer = pixelBuffer;
  g_metalState.metalLayer = metalLayer;
  g_metalState.openglTexture = openglTexture;
  g_metalState.device = device;
  g_metalState.commandQueue = commandQueue;
  g_metalState.initialized = true;

  DEBUG_LOG("initLayer completed successfully!\n");
}

// attachToGameWindow(windowHandle, width, height, scale)
JNIEXPORT void JNICALL
Java_org_sykes_metaltnt_client_Metal_CVPixelBufferMount_attachToGameWindow(
    JNIEnv *env, jclass /*clazz*/, jlong windowHandle, jint width, jint height,
    jfloat scale) {
  (void)env;

  DEBUG_LOG("attachToGameWindow called: %dx%d scale=%.1f\n", width, height,
            scale);

  // Check if we're initialized
  if (!g_metalState.initialized) {
    // Not initialized yet, can't attach
    DEBUG_LOG("ERROR: Not initialized, cannot attach Metal layer\n");
    abort();
  }

  // ========================================
  // STEP 1: Convert windowHandle to NSWindow
  // ========================================
  NSWindow *window = (__bridge NSWindow *)(void *)windowHandle;

  if (!window) {
    // Invalid window handle
    DEBUG_LOG("ERROR: Invalid window handle\n");
    abort();
  }

  // ========================================
  // STEP 2: Get the Metal layer
  // ========================================
  CAMetalLayer *metalLayer = g_metalState.metalLayer;

  if (!metalLayer) {
    // No Metal layer available
    DEBUG_LOG("ERROR: No Metal layer available\n");
    abort();
  }

  // ========================================
  // STEP 3: Update Metal layer properties
  // ========================================
  // Update the layer size to match the window
  metalLayer.drawableSize = CGSizeMake(width, height);
  metalLayer.contentsScale = scale;

  // ========================================
  // STEP 4: Attach Metal layer to window
  // ========================================
  // Get the window's content view
  NSView *contentView = window.contentView;

  if (!contentView) {

    DEBUG_LOG("ERROR: No content view available\n");
    abort();
  }

  // Set the Metal layer frame to fill the content view
  metalLayer.frame = contentView.bounds;

  // Add the Metal layer as a sublayer
  [contentView.layer addSublayer:metalLayer];

  // ========================================
  // STEP 5: Configure layer properties
  // ========================================
  // Make sure the layer is positioned correctly
  metalLayer.position = CGPointMake(contentView.bounds.size.width / 2,
                                    contentView.bounds.size.height / 2);

  // Set anchor point to center
  metalLayer.anchorPoint = CGPointMake(0.5, 0.5);

  // Ensure the layer is visible
  metalLayer.hidden = NO;

  DEBUG_LOG("Metal layer attached successfully to window!\n");
}

// renderFrame() - Hybrid OpenGL + Metal approach
JNIEXPORT void JNICALL
Java_org_sykes_metaltnt_client_Metal_CVPixelBufferMount_renderFrame(
    JNIEnv *env, jclass /*clazz*/) {
  (void)env;

  static int frameCount = 0;
  frameCount++;

  // Only print every 60 frames to avoid spam
  if (frameCount % 60 == 0) {
    DEBUG_LOG("renderFrame called (frame %d) - Renderer: %s\n", frameCount,
              g_metalState.useMetalRenderer ? "Metal" : "OpenGL");
  }

  // Check if we're initialized
  if (!g_metalState.initialized) {
    DEBUG_LOG("ERROR: renderFrame called before initialization\n");
    abort();
  }

  // ========================================
  // STEP 1: Choose rendering path (OpenGL or Metal)
  // ========================================
  if (g_metalState.useMetalRenderer) {
    // Use Metal renderer
    renderWithMetal();
    return;
  }

  // ========================================
  // STEP 2: Copy from OpenGL texture to IOSurface
  // ========================================
  CVPixelBufferRef pixelBuffer = g_metalState.pixelBuffer;
  IOSurfaceRef ioSurface = CVPixelBufferGetIOSurface(pixelBuffer);

  if (!ioSurface) {
    DEBUG_LOG("ERROR: No IOSurface available\n");
    abort();
  }

  // Lock IOSurface for writing
  IOSurfaceLock(ioSurface, kIOSurfaceLockAvoidSync, NULL);
  void *ioSurfaceBaseAddress = IOSurfaceGetBaseAddress(ioSurface);

  if (!ioSurfaceBaseAddress) {
    DEBUG_LOG("ERROR: Could not get IOSurface base address\n");
    IOSurfaceUnlock(ioSurface, kIOSurfaceLockAvoidSync, NULL);
    abort();
  }

  // Read pixels from OpenGL texture to IOSurface
  glBindTexture(GL_TEXTURE_2D, g_metalState.openglTexture);
  glGetTexImage(GL_TEXTURE_2D, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV,
                ioSurfaceBaseAddress);
  glBindTexture(GL_TEXTURE_2D, 0);

  // Check for OpenGL errors
  GLenum glError = glGetError();
  if (glError != GL_NO_ERROR) {
    DEBUG_LOG("ERROR: OpenGL error during texture read: 0x%x\n", glError);
    IOSurfaceUnlock(ioSurface, kIOSurfaceLockAvoidSync, NULL);
    abort();
  }

  IOSurfaceUnlock(ioSurface, kIOSurfaceLockAvoidSync, NULL);

  // ========================================
  // STEP 2: Get Metal layer drawable
  // ========================================
  CAMetalLayer *metalLayer = g_metalState.metalLayer;
  id<MTLCommandQueue> commandQueue = g_metalState.commandQueue;
  id<CAMetalDrawable> drawable = [metalLayer nextDrawable];

  if (!drawable) {
    DEBUG_LOG("ERROR: No drawable available\n");
    abort();
  }

  // ========================================
  // STEP 3: Use pre-created Metal texture from IOSurface
  // ========================================
  id<MTLTexture> metalTexture =
      CVMetalTextureGetTexture(g_metalState.metalTexture);

  if (!metalTexture) {
    DEBUG_LOG("ERROR: Failed to get Metal texture from CVMetalTexture\n");
    abort();
  }

  // ========================================
  // STEP 4: Copy from IOSurface Metal texture to drawable
  // ========================================
  if (!commandQueue) {
    DEBUG_LOG("ERROR: No Metal command queue available\n");
    abort();
  }

  id<MTLCommandBuffer> commandBuffer = [commandQueue commandBuffer];
  id<MTLBlitCommandEncoder> blitEncoder = [commandBuffer blitCommandEncoder];

  // Copy from IOSurface Metal texture to the drawable texture
  [blitEncoder
        copyFromTexture:metalTexture
            sourceSlice:0
            sourceLevel:0
           sourceOrigin:MTLOriginMake(0, 0, 0)
             sourceSize:MTLSizeMake(metalTexture.width, metalTexture.height, 1)
              toTexture:drawable.texture
       destinationSlice:0
       destinationLevel:0
      destinationOrigin:MTLOriginMake(0, 0, 0)];

  [blitEncoder endEncoding];

  // ========================================
  // STEP 5: Present the drawable
  // ========================================
  [commandBuffer presentDrawable:drawable];
  [commandBuffer commit];

  // ========================================
  // STEP 6: Clean up
  // ========================================
  // Note: ARC handles memory management for Metal objects
}

//  Get OpenGL Texture
JNIEXPORT jint JNICALL
Java_org_sykes_metaltnt_client_Metal_CVPixelBufferMount_getOpenGLTexture(
    JNIEnv *env, jclass /*clazz*/) {

  if (!g_metalState.initialized) {

    // Create error log
    DEBUG_LOG("CVPixelBufferMount not initialized");
    abort();
  }

  // Return Green light for success

  return (jint)g_metalState.openglTexture;
}

// ------------------------------------------------------------------
// ConvertToBGRA - CVPixelBufferMount.cpp
// ------------------------------------------------------------------
// convert all incoming formats to BGRA within the pixel buffer
// because Metals most widly used format is BGRA

// Size_t used to prevent integer overflow

//(__bridge NSString *) C string to an Objective-C string

static CVPixelBufferRef createIOSurfaceBackedPixelBuffer(size_t width,
                                                         size_t height) {
  // ========================================
  // STEP 1: Define the attribute keys
  // ========================================
  // These are the "names" of the properties we want to set
  CFStringRef keys[] = {
      kCVPixelBufferOpenGLCompatibilityKey, // "Can OpenGL use this buffer?"
      kCVPixelBufferMetalCompatibilityKey,  // "Can Metal use this buffer?"
      kCVPixelBufferPixelFormatTypeKey,     // "What pixel format?"
      kCVPixelBufferWidthKey,               // "How wide?"
      kCVPixelBufferHeightKey,              // "How tall?"
      kCVPixelBufferIOSurfacePropertiesKey  // "Use IOSurface for sharing?"
  };

  // ========================================
  // STEP 2: Prepare the values for each key
  // ========================================
  // Convert our parameters to the right types
  int32_t pixelFormat =
      kCVPixelFormatType_32BGRA;       // BGRA = Blue, Green, Red, Alpha
  int32_t widthInt = (int32_t)width;   // Convert size_t to int32_t
  int32_t heightInt = (int32_t)height; // Convert size_t to int32_t

  // Create Core Foundation number objects from our integers
  CFNumberRef formatNumber =
      CFNumberCreate(NULL, kCFNumberSInt32Type, &pixelFormat);
  CFNumberRef widthNumber =
      CFNumberCreate(NULL, kCFNumberSInt32Type, &widthInt);
  CFNumberRef heightNumber =
      CFNumberCreate(NULL, kCFNumberSInt32Type, &heightInt);

  // Create an empty dictionary for IOSurface properties (means "use defaults")
  CFDictionaryRef emptyDict =
      CFDictionaryCreate(NULL, NULL, NULL, 0, &kCFTypeDictionaryKeyCallBacks,
                         &kCFTypeDictionaryValueCallBacks);

  // ========================================
  // STEP 3: Match keys with their values
  // ========================================
  // Each value corresponds to the key at the same position
  CFTypeRef values[] = {
      kCFBooleanTrue, // OpenGL compatibility = YES
      kCFBooleanTrue, // Metal compatibility = YES
      formatNumber,   // Pixel format = BGRA
      widthNumber,    // Width = our width parameter
      heightNumber,   // Height = our height parameter
      emptyDict       // IOSurface properties = empty (use defaults)
  };

  // ========================================
  // STEP 4: Create the attributes dictionary
  // ========================================
  // This dictionary tells CVPixelBufferCreate what properties to set
  CFDictionaryRef attrs = CFDictionaryCreate(
      NULL,                            // Use default allocator
      (const void **)keys,             // Array of keys (property names)
      (const void **)values,           // Array of values (property values)
      6,                               // Number of key-value pairs
      &kCFTypeDictionaryKeyCallBacks,  // How to handle keys
      &kCFTypeDictionaryValueCallBacks // How to handle values
  );

  // ========================================
  // STEP 5: Create the actual pixel buffer
  // ========================================
  CVPixelBufferRef pixelBuffer = NULL;
  CVReturn result =
      CVPixelBufferCreate(kCFAllocatorDefault, // Use default memory allocator
                          width,               // Buffer width
                          height,              // Buffer height
                          kCVPixelFormatType_32BGRA, // Pixel format (BGRA)
                          attrs,       // Our attributes dictionary
                          &pixelBuffer // Where to store the created buffer
      );

  // ========================================
  // STEP 6: Clean up temporary objects
  // ========================================
  // Core Foundation uses manual memory management - we must release what we
  // create
  CFRelease(formatNumber); // Release the format number
  CFRelease(widthNumber);  // Release the width number
  CFRelease(heightNumber); // Release the height number
  CFRelease(emptyDict);    // Release the empty dictionary
  CFRelease(attrs);        // Release the attributes dictionary

  // ========================================
  // STEP 7: Check for errors and return
  // ========================================
  if (result != kCVReturnSuccess) {
    // Something went wrong creating the pixel buffer
    return NULL;
  }

  // Success! Return the created pixel buffer
  return pixelBuffer;
}

// ========================================
// Create OpenGL Texture from IOSurface
// ========================================
static GLuint createOpenGLTextureFromIOSurface(CVPixelBufferRef pixelBuffer) {
  DEBUG_LOG("createOpenGLTextureFromIOSurface called\n");

  // ========================================
  // STEP 1: Get IOSurface from pixel buffer
  // ========================================
  IOSurfaceRef ioSurface = CVPixelBufferGetIOSurface(pixelBuffer);

  if (!ioSurface) {
    // No IOSurface available
    DEBUG_LOG("ERROR: No IOSurface available\n");
    abort();
  }

  DEBUG_LOG("IOSurface obtained successfully\n");

  // ========================================
  // STEP 2: Get IOSurface dimensions and properties
  // ========================================
  size_t width = IOSurfaceGetWidth(ioSurface);
  size_t height = IOSurfaceGetHeight(ioSurface);

  // Get IOSurface properties for debugging
  uint32_t pixelFormat = IOSurfaceGetPixelFormat(ioSurface);
  size_t bytesPerRow = IOSurfaceGetBytesPerRow(ioSurface);

  DEBUG_LOG("IOSurface properties: %zux%zu, format=0x%x, bytesPerRow=%zu\n",
            width, height, pixelFormat, bytesPerRow);

  // ========================================
  // STEP 3: Create OpenGL texture
  // ========================================
  GLuint texture;
  glGenTextures(1, &texture);

  if (texture == 0) {
    // Failed to generate texture
    DEBUG_LOG("ERROR: Failed to generate OpenGL texture\n");
    abort();
  }

  DEBUG_LOG("OpenGL texture generated: %u\n", texture);

  // ========================================
  // STEP 4: Bind texture and configure
  // ========================================
  glBindTexture(GL_TEXTURE_2D, texture);

  // Set texture parameters
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

  // ========================================
  // STEP 5: Bind IOSurface to OpenGL texture
  // ========================================
  // Get the current OpenGL context
  CGLContextObj cglContext = CGLGetCurrentContext();

  if (!cglContext) {
    // No OpenGL context available
    DEBUG_LOG("ERROR: No OpenGL context available\n");
    glDeleteTextures(1, &texture);
    abort();
  }

  DEBUG_LOG("OpenGL context obtained successfully\n");

  // Log OpenGL info from existing context
  DEBUG_LOG("Existing context OpenGL info:\n");
  DEBUG_LOG("  VENDOR: %s\n", glGetString(GL_VENDOR));
  DEBUG_LOG("  RENDERER: %s\n", glGetString(GL_RENDERER));
  DEBUG_LOG("  VERSION: %s\n", glGetString(GL_VERSION));

  // Assume cglContextCurrent is the existing context from
  // CGLGetCurrentContext() If none, handle that earlier.
  CGLContextObj existingCtx = CGLGetCurrentContext();
  if (!existingCtx) {
    DEBUG_LOG("ERROR: no existing context to share with\n");
    glDeleteTextures(1, &texture);
    abort();
  }

  // ---------- Apple Silicon Hybrid Approach: OpenGL + Metal Bridge ----------
  // On Apple Silicon, OpenGL ↔ IOSurface is blocked, so we use Metal for
  // IOSurface
  DEBUG_LOG("Using hybrid OpenGL + Metal approach for Apple Silicon\n");

  // Create a regular OpenGL texture (no IOSurface binding)
  glBindTexture(GL_TEXTURE_2D, texture);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)width, (GLsizei)height, 0,
               GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, NULL);

  // Create Metal texture from IOSurface using CVMetalTextureCache
  id<MTLDevice> metalDevice = MTLCreateSystemDefaultDevice();
  if (!metalDevice) {
    DEBUG_LOG("FATAL: Failed to create Metal device for IOSurface bridge\n");
    glDeleteTextures(1, &texture);
    abort();
  }

  CVMetalTextureCacheRef metalTextureCache = NULL;
  CVReturn cacheResult = CVMetalTextureCacheCreate(
      kCFAllocatorDefault, NULL, metalDevice, NULL, &metalTextureCache);

  if (cacheResult != kCVReturnSuccess || !metalTextureCache) {
    DEBUG_LOG("FATAL: Failed to create Metal texture cache (err=%d)\n",
              cacheResult);
    glDeleteTextures(1, &texture);
    abort();
  }

  // Create Metal texture from IOSurface
  CVMetalTextureRef metalTexture = NULL;
  CVReturn textureResult = CVMetalTextureCacheCreateTextureFromImage(
      kCFAllocatorDefault, metalTextureCache, pixelBuffer, NULL,
      MTLPixelFormatBGRA8Unorm, (size_t)width, (size_t)height, 0,
      &metalTexture);

  if (textureResult != kCVReturnSuccess || !metalTexture) {
    DEBUG_LOG("FATAL: Failed to create Metal texture from IOSurface (err=%d)\n",
              textureResult);
    CFRelease(metalTextureCache);
    glDeleteTextures(1, &texture);
    abort();
  }

  DEBUG_LOG("Metal texture created successfully from IOSurface\n");

  // Store Metal resources for later use in renderFrame
  g_metalState.device = metalDevice;
  g_metalState.metalTextureCache = metalTextureCache;
  g_metalState.metalTexture = metalTexture;

  // Initialize Metal renderer for dual rendering
  initializeMetalRenderer(metalDevice, width, height);

  // IOSurface binding completed successfully using Metal bridge

  // IOSurface binding already completed above in the shared context

  // ========================================
  // STEP 6: Unbind texture and restore state
  // ========================================
  glBindTexture(GL_TEXTURE_2D, 0);

  // Check for OpenGL errors
  GLenum glError = glGetError();
  if (glError != GL_NO_ERROR) {
    DEBUG_LOG("OpenGL error after texture creation: 0x%x\n", glError);
    abort();
  }

  DEBUG_LOG("OpenGL texture creation completed successfully: %u\n", texture);
  return texture;
}

// end C++
#ifdef __cplusplus
}
#endif
// end C

// ========================================
// Metal Renderer Implementation
// ========================================

static void initializeMetalRenderer(id<MTLDevice> device, size_t width,
                                    size_t height) {
  DEBUG_LOG("Initializing Metal renderer for dual rendering\n");

  // Create Metal shader source
  NSString *vertexShaderSource =
      @""
       "#include <metal_stdlib>\n"
       "using namespace metal;\n"
       "\n"
       "struct Vertex {\n"
       "    float4 position [[position]];\n"
       "    float4 color;\n"
       "};\n"
       "\n"
       "vertex Vertex vertex_main(uint vertexID [[vertex_id]]) {\n"
       "    Vertex out;\n"
       "    \n"
       "    // Simple triangle for testing\n"
       "    float2 positions[3] = {\n"
       "        float2( 0.0,  0.5),\n"
       "        float2(-0.5, -0.5),\n"
       "        float2( 0.5, -0.5)\n"
       "    };\n"
       "    \n"
       "    float4 colors[3] = {\n"
       "        float4(1.0, 0.0, 0.0, 1.0), // Red\n"
       "        float4(0.0, 1.0, 0.0, 1.0), // Green\n"
       "        float4(0.0, 0.0, 1.0, 1.0)  // Blue\n"
       "    };\n"
       "    \n"
       "    out.position = float4(positions[vertexID], 0.0, 1.0);\n"
       "    out.color = colors[vertexID];\n"
       "    \n"
       "    return out;\n"
       "}\n";

  NSString *fragmentShaderSource =
      @""
       "fragment float4 fragment_main(Vertex in [[stage_in]]) {\n"
       "    return in.color;\n"
       "}\n";

  // Create Metal library with combined shader source
  NSString *combinedShaderSource = [NSString
      stringWithFormat:@"%@\n%@", vertexShaderSource, fragmentShaderSource];

  NSError *error = nil;
  id<MTLLibrary> library = [device newLibraryWithSource:combinedShaderSource
                                                options:nil
                                                  error:&error];
  if (!library) {
    DEBUG_LOG("FATAL: Failed to create Metal library: %s\n",
              error.localizedDescription.UTF8String);
    abort();
  }

  // Create vertex function
  id<MTLFunction> vertexFunction = [library newFunctionWithName:@"vertex_main"];
  if (!vertexFunction) {
    DEBUG_LOG("FATAL: Failed to create vertex function\n");
    abort();
  }

  // Create fragment function
  id<MTLFunction> fragmentFunction =
      [library newFunctionWithName:@"fragment_main"];
  if (!fragmentFunction) {
    DEBUG_LOG("FATAL: Failed to create fragment function\n");
    abort();
  }

  // Create render pipeline descriptor
  MTLRenderPipelineDescriptor *pipelineDescriptor =
      [[MTLRenderPipelineDescriptor alloc] init];
  pipelineDescriptor.vertexFunction = vertexFunction;
  pipelineDescriptor.fragmentFunction = fragmentFunction;
  pipelineDescriptor.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;

  // Create render pipeline state
  id<MTLRenderPipelineState> renderPipeline =
      [device newRenderPipelineStateWithDescriptor:pipelineDescriptor
                                             error:&error];
  if (!renderPipeline) {
    DEBUG_LOG("FATAL: Failed to create render pipeline: %s\n",
              error.localizedDescription.UTF8String);
    abort();
  }

  // Store Metal renderer state
  g_metalState.metalRenderPipeline = renderPipeline;
  g_metalState.metalViewport =
      (MTLViewport){0, 0, (double)width, (double)height, 0, 1};

  DEBUG_LOG("Metal renderer initialized successfully\n");
}

static void renderWithMetal() {
  if (!g_metalState.useMetalRenderer) {
    return;
  }

  DEBUG_LOG("Rendering with Metal renderer\n");

  // Get Metal layer drawable
  CAMetalLayer *metalLayer = g_metalState.metalLayer;
  id<CAMetalDrawable> drawable = [metalLayer nextDrawable];

  if (!drawable) {
    DEBUG_LOG("ERROR: No Metal drawable available\n");
    return;
  }

  // Create command buffer
  id<MTLCommandBuffer> commandBuffer =
      [g_metalState.commandQueue commandBuffer];

  // Create render pass descriptor
  MTLRenderPassDescriptor *renderPassDescriptor =
      [MTLRenderPassDescriptor renderPassDescriptor];
  renderPassDescriptor.colorAttachments[0].texture = drawable.texture;
  renderPassDescriptor.colorAttachments[0].loadAction = MTLLoadActionClear;
  renderPassDescriptor.colorAttachments[0].clearColor =
      MTLClearColorMake(0.2, 0.2, 0.2, 1.0); // Dark gray background

  // Create render command encoder
  id<MTLRenderCommandEncoder> renderEncoder =
      [commandBuffer renderCommandEncoderWithDescriptor:renderPassDescriptor];

  // Set viewport
  [renderEncoder setViewport:g_metalState.metalViewport];

  // Set render pipeline state
  [renderEncoder setRenderPipelineState:g_metalState.metalRenderPipeline];

  // Draw triangle (3 vertices, no vertex buffer needed for this simple example)
  [renderEncoder drawPrimitives:MTLPrimitiveTypeTriangle
                    vertexStart:0
                    vertexCount:3];

  // End encoding
  [renderEncoder endEncoding];

  // Present drawable
  [commandBuffer presentDrawable:drawable];
  [commandBuffer commit];

  DEBUG_LOG("Metal rendering completed\n");
}

// JNI function to toggle between OpenGL and Metal rendering
JNIEXPORT void JNICALL
Java_org_sykes_metaltnt_client_Metal_CVPixelBufferMount_toggleRenderer(
    JNIEnv *env, jclass /*clazz*/) {
  (void)env;

  g_metalState.useMetalRenderer = !g_metalState.useMetalRenderer;
  DEBUG_LOG("Renderer toggled to: %s\n",
            g_metalState.useMetalRenderer ? "Metal" : "OpenGL");
}

// TODO implement
/*
    Replace Minecraft's Framebuffer attachments (tighter integration)
      also called a eOSurfaceBackedPixelBuffer which is a CVPixelBuffer that is
   backed by an IOSurface

        Minecraft uses a Framebuffer (Yarn: net.minecraft.client.gl.Framebuffer,
   Mojang: RenderTarget) as its main render target. You can swap its color
   attachment with your IOSurface-backed GL texture so all game draws go there
   automatically.

        What to hook
            •	Framebuffer#createFramebuffers(int width, int height, boolean
   getError): after it creates the FBO, rebind the color attachment to your
   texture. •	Framebuffer#resize(...): recreate your IOSurface/GL resources
   and reattach. •	Framebuffer#beginWrite()/endWrite(): you can leave these
   as-is; they bind the same FBO id.

*/