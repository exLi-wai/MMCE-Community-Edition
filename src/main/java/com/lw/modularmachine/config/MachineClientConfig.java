package com.lw.modularmachine.config;

import com.lw.modularmachine.util.minecraft.energy.EnergyDisplay.Unit;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Local visual preferences; never used to authorize construction. */
public final class MachineClientConfig {
  public static final ModConfigSpec SPEC;
  public static final ModConfigSpec.IntValue PROJECTION_CANCEL_DISTANCE;
  public static final ModConfigSpec.BooleanValue DISPLAY_FE_TOOLTIP;
  public static final ModConfigSpec.BooleanValue DISPLAY_GT_EU_TOOLTIP;
  public static final ModConfigSpec.EnumValue<Unit> ENERGY_DISPLAY_TYPE;

  static {
    var builder = new ModConfigSpec.Builder();
    builder.translation("mmcece.config.projection").push("projection");
    PROJECTION_CANCEL_DISTANCE = builder.translation("mmcece.config.projection.autoCancelDistance").comment(
        "Cancel the local world projection when the player is farther than this many blocks from the controller center.")
        .defineInRange("autoCancelDistance", 100, 1, 4096);
    builder.pop();
    builder.translation("mmcece.config.display").push("display");
    builder.translation("mmcece.config.energy_display").push("energy");
    DISPLAY_FE_TOOLTIP = builder.translation("mmcece.config.energy_display.feTooltip")
        .comment("Show the FE/RF capacity and transfer limits in energy hatch item tooltips.")
        .define("FE_RF_Tooltip", true);
    DISPLAY_GT_EU_TOOLTIP = builder.translation("mmcece.config.energy_display.gtTooltip").comment(
        "Show additional GT EU equivalents when GregTech is present. Display conversion does not add an energy bridge.")
        .define("GT_EU_Tooltip", true);
    ENERGY_DISPLAY_TYPE = builder.translation("mmcece.config.energy_display.type").comment(
        "Energy display unit for GUI and recipe text: FE, RF or GT_EU. Four FE are displayed as one EU; actual storage and transfer remain FE.")
        .defineEnum("Display_Energy_Type", Unit.FE);
    builder.pop(2);
    SPEC = builder.build();
  }

  private MachineClientConfig() {
  }
}
