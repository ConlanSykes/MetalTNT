

// Make a CVPixelBuffer to render both OpenGL and Metal

#include <Metal/Metal.h>
#include <QuartzCore/QuartzCore.h>
#include <CoreVideo/CoreVideo.h>
#include <OpenGL/OpenGL.h>
#include <IOSurface/IOSurface.h>

// Make a CVPixelBuffer to render both OpenGL and Metal use IOSurface to create
// a shared memory buffer that can be used by both APIs.

// written in C++

#include <jni.h>  // declares JNI types and macros
//keep native state for later use by attachToGameWindow/renderFrame

namespace {
 // this is used to store the native state of the Java object

}

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
Java_org_sykes_metaltnt_client_Metal_CVPixelBufferMount_initLayer
  (JNIEnv* env, jclass /*clazz*/, jint width, jint height, jfloat scale) {
    // TODO: initialize native resources using width, height, scale
    (void)env; (void)width; (void)height; (void)scale;
}

// attachToGameWindow(windowHandle, width, height, scale)
JNIEXPORT void JNICALL
Java_org_sykes_metaltnt_client_Metal_CVPixelBufferMount_attachToGameWindow
  (JNIEnv* env, jclass /*clazz*/, jlong windowHandle, jint width, jint height, jfloat scale) {
    // TODO: attach to native window using windowHandle; use width/height/scale for sizing
    (void)env; (void)windowHandle; (void)width; (void)height; (void)scale;
}

// renderFrame()
JNIEXPORT void JNICALL
Java_org_sykes_metaltnt_client_Metal_CVPixelBufferMount_renderFrame
  (JNIEnv* env, jclass /*clazz*/) {
    // TODO: render a frame
    (void)env;
}


// end C++
#ifdef __cplusplus
}
#endif
// end C


//TODO implement
/*
    Replace Minecraft’s Framebuffer attachments (tighter integration)

        Minecraft uses a Framebuffer (Yarn: net.minecraft.client.gl.Framebuffer, Mojang: RenderTarget) as its main render target. You can swap its color attachment with your IOSurface-backed GL texture so all game draws go there automatically.

        What to hook
	    •	Framebuffer#createFramebuffers(int width, int height, boolean getError): after it creates the FBO, rebind the color attachment to your texture.
	    •	Framebuffer#resize(...): recreate your IOSurface/GL resources and reattach.
	    •	Framebuffer#beginWrite()/endWrite(): you can leave these as-is; they bind the same FBO id.

*/