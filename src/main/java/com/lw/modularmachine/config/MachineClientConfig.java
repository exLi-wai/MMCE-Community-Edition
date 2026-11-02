package com.lw.modularmachine.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Local visual preferences; never used to authorize construction. */
public final class MachineClientConfig {
  public static final ModConfigSpec SPEC;
  public static final ModConfigSpec.IntValue PROJECTION_CANCEL_DISTANCE;

  static {
    var builder = new ModConfigSpec.Builder();
    builder.translation("mmcece.config.projection").push("projection");
    PROJECTION_CANCEL_DISTANCE = builder.translation("mmcece.config.projection.autoCancelDistance").comment(
        "Cancel the local world projection when the player is farther than this many blocks from the controller center.")
        .defineInRange("autoCancelDistance", 100, 1, 4096);
    builder.pop();
    SPEC = builder.build();
  }

  private MachineClientConfig() {
  }
}
