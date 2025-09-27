package org.sykes.metaltnt.client.Metal;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetalRendererToggle {
  private static final Logger LOGGER = LoggerFactory.getLogger(MetalRendererToggle.class);

  private static KeyBinding toggleKey;

  public static void register() {
    toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
        "metaltnt.toggle_renderer",
        InputUtil.Type.KEYSYM,
        GLFW.GLFW_KEY_M, // Press 'M' to toggle
        "MetalTNT"));

    LOGGER.info("Metal renderer toggle key registered (Press 'M' to toggle)");
  }

  public static void tick() {
    if (toggleKey.wasPressed()) {
      CVPixelBufferMount.toggleRenderer();
      LOGGER.info("Renderer toggled! Check console for current renderer mode.");
    }
  }
}
