package com.metalrender.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class MetalLogger {
  private static final Logger LOGGER = LogUtils.getLogger();

  private static String format(String msg, Object... args) {
    if (args == null || args.length == 0)
      return msg;
    // Always use SLF4J-style {} substitution — never String.format
    StringBuilder sb = new StringBuilder();
    int argIdx = 0;
    int i = 0;
    while (i < msg.length()) {
      if (i + 1 < msg.length() && msg.charAt(i) == '{' && msg.charAt(i + 1) == '}' && argIdx < args.length) {
        sb.append(args[argIdx++]);
        i += 2;
      } else {
        sb.append(msg.charAt(i));
        i++;
      }
    }
    // Append any remaining args that didn't have {} placeholders
    while (argIdx < args.length) {
      sb.append(' ').append(args[argIdx++]);
    }
    return sb.toString();
  }

  public static void info(String msg, Object... args) {
    LOGGER.info("[MetalRender] {}", format(msg, args));
  }

  public static void debug(String msg, Object... args) {
    LOGGER.debug("[MetalRender] {}", format(msg, args));
  }

  public static void warn(String msg, Object... args) {
    LOGGER.warn("[MetalRender] {}", format(msg, args));
  }

  public static void error(String msg, Object... args) {
    LOGGER.error("[MetalRender] {}", format(msg, args));
  }

  public static boolean isDebugEnabled() {
    return LOGGER.isDebugEnabled();
  }
}
