package com.lw.modularmachine.config;

import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.ParallelControllerTier;
import com.lw.modularmachine.machine.UpgradeBusTier;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class MachineConfig {
  public static final ModConfigSpec SPEC;
  public static final ModConfigSpec.BooleanValue CONSERVATIVE_COMMIT_CHECK;
  public static final ModConfigSpec.IntValue RECIPE_OUTPUT_WORK;
  public static final ModConfigSpec.IntValue FACTORY_THREADS_PER_TICK;
  public static final ModConfigSpec.IntValue FACTORY_SEARCH_WORK;
  public static final ModConfigSpec.IntValue FACTORY_OUTPUT_WORK;
  public static final ModConfigSpec.ConfigValue<String> ASSEMBLY_ITEM;
  public static final Map<ParallelControllerTier, ModConfigSpec.IntValue> PARALLEL_CONTROLLER_MAXIMA;
  public static final Map<String, ModConfigSpec.IntValue> FLUID_HATCH_CAPACITY;
  public static final Map<String, ModConfigSpec.IntValue> ENERGY_HATCH_CAPACITY;
  public static final Map<String, ModConfigSpec.IntValue> ENERGY_HATCH_TRANSFER;
  public static final Map<String, ModConfigSpec.IntValue> UPGRADE_BUS_SLOTS;

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
    FACTORY_SEARCH_WORK = builder.translation("mmcece.config.factory_controller.searchWorkPerThreadTick")
        .comment("Maximum recipe search, parallel admission, and startup-check work for one factory thread per tick.")
        .defineInRange("searchWorkPerThreadTick", 65536, 1, 262144);
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

    FLUID_HATCH_CAPACITY = hatchValues(builder, "fluid_hatch", HatchTier.FLUIDS, false, Integer.MAX_VALUE);
    ENERGY_HATCH_CAPACITY = hatchValues(builder, "energy_hatch", HatchTier.ENERGY, false, Integer.MAX_VALUE);
    ENERGY_HATCH_TRANSFER = hatchValues(builder, "energy_hatch_transfer", HatchTier.ENERGY, true, Integer.MAX_VALUE);
    builder.translation("mmcece.config.upgrade_bus").push("upgrade_bus");
    var busSlots = new LinkedHashMap<String, ModConfigSpec.IntValue>();
    for (var tier : UpgradeBusTier.values()) {
      busSlots.put(
          tier.path(),
          builder.translation("mmcece.config.upgrade_bus." + tier.path())
              .defineInRange(tier.path(), tier.slots(), 1, 18));
    }
    UPGRADE_BUS_SLOTS = Map.copyOf(busSlots);
    builder.pop();
    SPEC = builder.build();
  }

  private MachineConfig() {
  }
  public static long fluidHatchCapacity(HatchTier tier) {
    return hatchValue(FLUID_HATCH_CAPACITY, tier.name(), tier.capacity());
  }

  public static long energyHatchCapacity(HatchTier tier) {
    return hatchValue(ENERGY_HATCH_CAPACITY, tier.name(), tier.capacity());
  }

  public static long energyHatchTransfer(HatchTier tier) {
    return hatchValue(ENERGY_HATCH_TRANSFER, tier.name(), tier.transferLimit());
  }

  public static int upgradeBusSlots(UpgradeBusTier tier) {
    var value = UPGRADE_BUS_SLOTS.get(tier.path());
    int fallback = tier.slots();
    if (value == null || !SPEC.isLoaded()) {
      return fallback;
    }
    return Math.max(1, Math.min(18, value.get()));
  }

  private static long hatchValue(Map<String, ModConfigSpec.IntValue> values, String tier, long fallback) {
    var value = values.get(tier);
    if (value == null || !SPEC.isLoaded()) {
      return fallback;
    }
    long configured = value.get();
    return configured <= 0 ? fallback : configured;
  }

  private static Map<String, ModConfigSpec.IntValue> hatchValues(ModConfigSpec.Builder builder, String section, List<HatchTier> tiers, boolean transfer, int max) {
    builder.translation("mmcece.config." + section).push(section);
    var values = new LinkedHashMap<String, ModConfigSpec.IntValue>();
    for (var tier : tiers) {
      long fallback = transfer ? Math.max(1, tier.transferLimit()) : tier.capacity();
      int value = (int) Math.min(Integer.MAX_VALUE, fallback);
      values.put(
          tier.name(),
          builder.translation("mmcece.config." + section + "." + tier.name())
              .defineInRange(tier.name(), value, 1, max));
    }
    builder.pop();
    return Map.copyOf(values);
  }
}
