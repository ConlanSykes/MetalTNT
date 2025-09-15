package org.sykes.metaltnt.client.Metal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CVPixelBufferMount {
    private static final Logger LOGGER = LoggerFactory.getLogger(CVPixelBufferMount.class);
    static {
        try {
            System.loadLibrary("native-lib");
            LOGGER.info("✅ native-lib loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("❌ Failed to load native-lib: {}", e.getMessage());
            throw new RuntimeException("Native library loading failed", e);
        }
    }

    //What is absolutely required to mount the CVPixelBuffer

    /*
    The absolute minimum to get basic functionality is:
    Working native library compilation
    Successful initLayer() call
    Valid attachToGameWindow() with NSWindow pointer
    renderFrame() being called from the mixin
    If any of these fail, the system won't work.
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
     * @param width       Window width
     * @param height      Window height
     * @param scale       Display scale factor
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

        //define minecraft and its instance
        var minecraft = net.minecraft.client.MinecraftClient.getInstance();
        //if Minecraft does not load give runtime error and throw logger
        if (minecraft == null) {
            LOGGER.error("Minecraft instance is null. Cannot mount CVPixelBuffer.");
            throw new RuntimeException("Minecraft instance is null. Cannot mount CVPixelBuffer.");
        }


        //note to self for future reference:
        /*
        * window.getWidth() returns the raw pixel width of the window, while minecraft.getWindow().getScaledWidth() returns the width adjusted for Minecraft's GUI scale setting.
        Use getScaledWidth() if you want dimensions that match how Minecraft renders its UI; use getWidth() for the actual window size in pixels. */

        //get window dimensions actual
        //window core is needed Runtime Error if not found
        var window = minecraft.getWindow();
        if (minecraft.getWindow() == null) {
            LOGGER.error("Minecraft window instance is null. Cannot mount CVPixelBuffer.");
            throw new RuntimeException("Minecraft window instance is null. Cannot mount CVPixelBuffer.");
        }

        //use getFramebuffer instead of width() to account for Mac Rentia Displays
        // same for getScaleFactor instead of getgui Scale

        int width = window.getFramebufferWidth();
        int height = window.getFramebufferHeight();
        float scale = (float) window.getScaleFactor();


        //define window handle
       // long windowHandle = minecraft.getWindow().getHandle();


        //Onetime log of current window dimensions if running in debug mode
        if (LOGGER.isDebugEnabled()) {
            LOGGER.info("Window Dimensions: {}x{}", width, height);
            LOGGER.info("Window Scale: {}", scale);
        }
        LOGGER.info("Window Dimensions: {}x{}", width, height);
        LOGGER.info("Window Scale: {}", scale);







    }
}

