package com.metalrender.sodium.mixins.accessor;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
  @Accessor("handler")
  ScreenHandler metalrender$getHandler();

  @Accessor("x")
  int metalrender$getX();

  @Accessor("y")
  int metalrender$getY();
}
