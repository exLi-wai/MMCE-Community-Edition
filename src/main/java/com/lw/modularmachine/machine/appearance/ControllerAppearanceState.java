package com.lw.modularmachine.machine.appearance;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal world-model state, independent from production and paid-run data.
 */
@ApiStatus.Internal
public enum ControllerAppearanceState implements StringRepresentable {
  UNFORMED("unformed"),
  FORMED("formed"),
  RUNNING("running"),
  PAUSED("paused"),
  ERROR("error");

  private final String name;

  ControllerAppearanceState(String name) {
    this.name = name;
  }

  @Override
  public String getSerializedName() {
    return name;
  }
}
