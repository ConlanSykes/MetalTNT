package org.sykes.metaltnt.client.Metal;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sykes.metaltnt.client.checker.CheckifMac;

public final class CVPixelBufferMount {
    private static final Logger LOGGER = LoggerFactory.getLogger(CVPixelBufferMount.class);
    static {
        try {

            // check macOS system
            if (!CheckifMac.isMac()) {

                LOGGER.info("Metal/CVPixelBuffer native not loaded: non-macOS detected (os.name={})",
                        System.getProperty("os.name"));
                throw new UnsupportedOperationException("Metal/CVPixelBuffer is only supported on macOS");
            } else {
                // find libnative-lib.dylib file and print it for testing
                String path = System.getProperty("java.library.path");
                LOGGER.info("Java library path: {}", path);

                System.loadLibrary("native-lib");
                LOGGER.info("✅ native-lib loaded successfully");
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("❌ Failed to load native-lib: {}", e.getMessage());
            throw new RuntimeException("Native library loading failed", e);
        }
    }

    // What is absolutely required to mount the CVPixelBuffer

    /*
     * The absolute minimum to get basic functionality is:
     * Working native library compilation
     * Successful initLayer() call
     * Valid attachToGameWindow() with NSWindow pointer
     * renderFrame() being called from the mixin
     * If any of these fail, the system won't work.
     */

    /**
     * Initialize the Metal layer and CVPixelBuffer system
     *
     * @param width  Initial width
     * @param height Initial height
     * @param scale  Display scale factor
     */
    public static native void initLayer(int width, int height, float scale);

    /**
     * Attach Metal layer to Cocoa window
     *
     * @param windowHandle Native NSWindow pointer
     * @param width        Window width
     * @param height       Window height
     * @param scale        Display scale factor
     */
    public static native void attachToGameWindow(long windowHandle, int width, int height, float scale);

    /**
     * Render a frame using OpenGL -> Metal conversion
     */
    public static native void renderFrame();

    /**
     * Initialize the CVPixelBuffer system
     * This should be called during mod initialization
     */
    public static void mount() {
        LOGGER.debug("🔍 CVPixelBufferMount.mount() called - DEBUG ENABLED: {}", LOGGER.isDebugEnabled());

        // define minecraft and its instance
        var minecraft = net.minecraft.client.MinecraftClient.getInstance();
        // if Minecraft does not load give runtime error and throw logger
        if (minecraft == null) {
            LOGGER.error("Minecraft instance is null. Cannot mount CVPixelBuffer.");
            throw new RuntimeException("Minecraft instance is null. Cannot mount CVPixelBuffer.");
        }

        // note to self for future reference:
        /*
         * window.getWidth() returns the raw pixel width of the window, while
         * minecraft.getWindow().getScaledWidth() returns the width adjusted for
         * Minecraft's GUI scale setting.
         * Use getScaledWidth() if you want dimensions that match how Minecraft renders
         * its UI; use getWidth() for the actual window size in pixels.
         */

        // get window dimensions actual
        // window core is needed Runtime Error if not found
        var window = minecraft.getWindow();
        if (minecraft.getWindow() == null) {
            LOGGER.error("Minecraft window instance is null. Cannot mount CVPixelBuffer.");
            throw new RuntimeException("Minecraft window instance is null. Cannot mount CVPixelBuffer.");
        }

        // use getFramebuffer instead of width() to account for Mac Rentia Displays
        // same for getScaleFactor instead of getgui Scale

        int width = window.getFramebufferWidth();
        int height = window.getFramebufferHeight();
        float scale = (float) window.getScaleFactor();


        /*  Analogy: Apartment manager
	    •	You live in an apartment building.
	    •	GLFWwindow* = the apartment manager’s office record about your unit. It has your name, rent, complaints, etc.
	    •	NSWindow* = the actual apartment unit itself. Where the furniture is, where you can knock down a wall, install a new light.
        */

        //get the pointer to the window this is cross device
        long glfwWindow = MinecraftClient.getInstance().getWindow().getHandle();

        // get the NSWindow pointer using GLFWNativeCocoa
        //this is the MacOS specific window pointer
       long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow);


        // Onetime log of current window dimensions if running in debug mode
        LOGGER.debug("Window Dimensions: {}x{}", width, height);
        LOGGER.debug("Window Scale: {}", scale);

        // make initLayer to make CVPixelBuffer

        try {
            initLayer(width, height, scale);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("❌ JNI link error in initLayer: {}", e.getMessage(), e);
            throw new RuntimeException("CVPixelBuffer JNI binding missing/mismatched", e);
        } catch (Throwable t) { // optional broader safety net
            LOGGER.error("❌ Unexpected failure in initLayer", t);
            throw new RuntimeException("CVPixelBuffer initialization failed", t);
        }



        // make attachToGameWindow to attach CVPixelBuffer to game window

        try {
            if( nsWindow != 0) {
                attachToGameWindow(nsWindow, width, height, scale);
            } else {
                LOGGER.error("NSWindow pointer is null. Cannot mount CVPixelBuffer.");
                throw new RuntimeException("NSWindow pointer is null. Cannot mount CVPixelBuffer.");
            }
        } catch (Exception e) {
            LOGGER.error("❌ Failed to mount CVPixelBuffer system: {}", e.getMessage());
            throw new RuntimeException("CVPixelBuffer mounting failed", e);
        }
        // make renderFrame to render the frame
        renderFrame();
    }
}
