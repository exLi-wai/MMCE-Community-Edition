package com.lw.modularmachine.config;

import com.lw.modularmachine.machine.ParallelControllerTier;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class MachineConfig {
  public static final ModConfigSpec SPEC;
  public static final ModConfigSpec.BooleanValue CONSERVATIVE_COMMIT_CHECK;
  public static final ModConfigSpec.IntValue RECIPE_OUTPUT_WORK;
  public static final ModConfigSpec.IntValue FACTORY_THREADS_PER_TICK;
  public static final ModConfigSpec.IntValue FACTORY_OUTPUT_WORK;
  public static final ModConfigSpec.ConfigValue<String> ASSEMBLY_ITEM;
  public static final Map<ParallelControllerTier, ModConfigSpec.IntValue> PARALLEL_CONTROLLER_MAXIMA;

  static {
    ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
    builder.translation("mmcece.config.structure_detection").push("structure_detection");
    CONSERVATIVE_COMMIT_CHECK = builder.translation("mmcece.config.structure_detection.conservativeProductionCheck")
        .comment(
            "Keep enabled until all external mutation paths are audited; a full check precedes actual production, not idle ticks.")
        .define("conservativeProductionCheck", true);
    builder.pop();
    builder.translation("mmcece.config.item_recipe").push("item_recipe");
    RECIPE_OUTPUT_WORK = builder.translation("mmcece.config.item_recipe.outputWorkPerControllerTick")
        .comment("Pure output-placement work per controller tick; snapshots and individual codecs are not preempted.")
        .defineInRange("outputWorkPerControllerTick", 256, 1, 65536);
    builder.pop();
    builder.translation("mmcece.config.factory_controller").push("factory_controller");
    FACTORY_THREADS_PER_TICK = builder.translation("mmcece.config.factory_controller.threadsPerTick")
        .comment("Maximum number of factory recipe channels visited per controller tick.")
        .defineInRange("threadsPerTick", 4, 1, 256);
    FACTORY_OUTPUT_WORK = builder.translation("mmcece.config.factory_controller.outputWorkPerTick")
        .comment("Maximum pure output-planning work for one factory thread per tick.")
        .defineInRange("outputWorkPerTick", 65536, 1, 262144);
    builder.pop();
    builder.translation("mmcece.config.parallel_controller").push("parallel_controller");
    var maxima = new EnumMap<ParallelControllerTier, ModConfigSpec.IntValue>(ParallelControllerTier.class);
    for (var tier : ParallelControllerTier.values()) {
      maxima.put(
          tier,
          builder.translation("mmcece.config.parallel_controller." + tier.path())
              .defineInRange(tier.path(), tier.defaultMaximum(), 1, Integer.MAX_VALUE));
    }
    PARALLEL_CONTROLLER_MAXIMA = Map.copyOf(maxima);
    builder.pop();
    builder.translation("mmcece.config.auto_assembly").push("auto_assembly");
    ASSEMBLY_ITEM = builder.translation("mmcece.config.auto_assembly.itemName")
        .comment(
            "Item used in the main hand to right-click a controller and start construction without projection.",
            "Legacy itemName binding; unknown item IDs fall back to minecraft:stick. The item is not consumed.")
        .define(
            "itemName",
            "minecraft:stick",
            value -> value instanceof String name && ResourceLocation.tryParse(name) != null);
    builder.pop();
    SPEC = builder.build();
  }

  private MachineConfig() {
  }
}
