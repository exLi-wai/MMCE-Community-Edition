package com.lw.modularmachine.config;

import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.ParallelControllerTier;
import com.lw.modularmachine.machine.UpgradeBusTier;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.machine.factory.FactoryThreadStateCodec;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class MachineConfig {
  public static final ModConfigSpec SPEC;
  public static final ModConfigSpec.BooleanValue CLEAN_CUSTOM_DATA_ON_STRUCTURE_CHECK_FAILED;
  public static final ModConfigSpec.BooleanValue ENABLE_DURATION_MULTIPLIER;
  public static final ModConfigSpec.BooleanValue ENABLE_SECURITY_SYSTEM;
  public static final ModConfigSpec.IntValue RECIPE_COMMAND_PERMISSION_LEVEL;
  public static final ModConfigSpec.ConfigValue<String> DEFAULT_FAILURE_ACTION;
  public static final ModConfigSpec.BooleanValue MACHINE_PARALLEL_DEFAULT;
  public static final ModConfigSpec.BooleanValue RECIPE_PARALLEL_DEFAULT;
  public static final ModConfigSpec.IntValue DEFAULT_MAX_MACHINE_PARALLELISM;
  public static final ModConfigSpec.IntValue DEFAULT_FACTORY_MAX_THREADS;
  public static final ModConfigSpec.BooleanValue CONSERVATIVE_COMMIT_CHECK;
  public static final ModConfigSpec.IntValue RECIPE_OUTPUT_WORK;
  public static final ModConfigSpec.IntValue FACTORY_THREADS_PER_TICK;
  public static final ModConfigSpec.IntValue FACTORY_SEARCH_WORK;
  public static final ModConfigSpec.IntValue FACTORY_OUTPUT_WORK;
  public static final ModConfigSpec.ConfigValue<String> ASSEMBLY_ITEM;
  public static final ModConfigSpec.IntValue ASSEMBLY_TIME_BUDGET_MILLIS;
  public static final ModConfigSpec.IntValue ASSEMBLY_WORK_PER_TICK;
  public static final ModConfigSpec.IntValue ASSEMBLY_SLICE_WORK;
  public static final Map<ParallelControllerTier, ModConfigSpec.IntValue> PARALLEL_CONTROLLER_MAXIMA;
  public static final Map<String, ModConfigSpec.IntValue> FLUID_HATCH_CAPACITY;
  public static final Map<String, ModConfigSpec.LongValue> CHEMICAL_HATCH_CAPACITY;
  public static final Map<String, ModConfigSpec.LongValue> ENERGY_HATCH_CAPACITY;
  public static final Map<String, ModConfigSpec.LongValue> ENERGY_HATCH_TRANSFER;
  public static final Map<String, ModConfigSpec.IntValue> UPGRADE_BUS_SLOTS;

  static {
    ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
    builder.translation("mmcece.config.general").push("general");
    RECIPE_COMMAND_PERMISSION_LEVEL = builder.translation("mmcece.config.general.recipeCommandPermissionLevel").comment(
        "Maximum command permission for registered recipe actions, between 0 and 2. Owner sources also retain the owner's actual permission ceiling.")
        .defineInRange("recipe-command-permission-level", 2, 0, 2);
    ENABLE_SECURITY_SYSTEM = builder.translation("mmcece.config.general.enableSecuritySystem").comment(
        "Prevent non-creative players from breaking another player's controller or placing a controller item owned by another player.",
        "Unowned controllers remain usable. This does not restrict GUI access or change paid recipe runs.")
        .define("enable-security-system", false);
    CLEAN_CUSTOM_DATA_ON_STRUCTURE_CHECK_FAILED = builder
        .translation("mmcece.config.general.cleanCustomDataOnStructureCheckFailed")
        .comment(
            "Clear only controller-owned script custom data and custom modifiers after a definite structure mismatch.",
            "Pending verification, unloaded chunks, rotation and definition reload do not clear script data.",
            "Inventories, upgrade-source data and paid recipe runs are preserved.")
        .define("clean-custom-data-on-structure-check-failed", false);
    ENABLE_DURATION_MULTIPLIER = builder.translation("mmcece.config.general.enableDurationMultiplier")
        .comment(
            "Scale per-tick energy by original duration / modified duration when compiling new recipe candidates.",
            "Paid runs retain their frozen duration and energy cost. Fluid costs do not use this multiplier.")
        .define("enable-duration-multiplier", false);
    DEFAULT_FAILURE_ACTION = builder.translation("mmcece.config.general.defaultFailureAction")
        .comment("Default progress policy for recipes without an explicit failure_action: reset, still or decrease.")
        .define(
            "default-failure-actions",
            "still",
            value -> value instanceof String action && Set.of("reset", "still", "decrease").contains(action));
    builder.pop();
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
    DEFAULT_FACTORY_MAX_THREADS = builder.translation("mmcece.config.factory_controller.defaultMaxThreads")
        .comment(
            "Default maximum number of recipe threads per factory, separate from the per-tick work budget.",
            "Machine factory.maximum_threads overrides this default; reload machine definitions to apply changes.")
        .defineInRange("default-factory-max-thread", 10, 1, FactoryThreadStateCodec.MAX_THREADS);
    FACTORY_THREADS_PER_TICK = builder.translation("mmcece.config.factory_controller.threadsPerTick")
        .comment("Maximum number of factory recipe threads visited per controller tick.")
        .defineInRange("threadsPerTick", 4, 1, 256);
    FACTORY_SEARCH_WORK = builder.translation("mmcece.config.factory_controller.searchWorkPerThreadTick")
        .comment("Maximum recipe search, parallel admission, and startup-check work for one factory thread per tick.")
        .defineInRange("searchWorkPerThreadTick", 65536, 1, 262144);
    FACTORY_OUTPUT_WORK = builder.translation("mmcece.config.factory_controller.outputWorkPerTick")
        .comment("Maximum pure output-planning work for one factory thread per tick.")
        .defineInRange("outputWorkPerTick", 65536, 1, 262144);
    builder.pop();
    builder.translation("mmcece.config.parallel_controller").push("parallel_controller");
    MACHINE_PARALLEL_DEFAULT = builder.translation("mmcece.config.parallel_controller.machineDefault")
        .comment(
            "Default machine parallelizable value; explicit machine declarations override it.",
            "Reload machine definitions after changing this default.")
        .define("machine-parallelize-enabled-bydefault", true);
    RECIPE_PARALLEL_DEFAULT = builder.translation("mmcece.config.parallel_controller.recipeDefault")
        .comment(
            "Default recipe parallelized value; explicit recipe declarations override it.",
            "New candidates use this default, while paid runs keep their frozen instance count.")
        .define("recipe-parallelize-enabled-bydefault", true);
    DEFAULT_MAX_MACHINE_PARALLELISM = builder.translation("mmcece.config.parallel_controller.defaultMaximum")
        .comment(
            "Default machine parallel.maximum; explicit machine declarations override it.",
            "Reload machine definitions after changing this default. Actual resources still limit admission.")
        .defineInRange("max-machine-parallelism", 2048, 1, Integer.MAX_VALUE);
    var maxima = new EnumMap<ParallelControllerTier, ModConfigSpec.IntValue>(ParallelControllerTier.class);
    for (var tier : ParallelControllerTier.values()) {
      if (!tier.configurable()) {
        continue;
      }
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
    ASSEMBLY_TIME_BUDGET_MILLIS = builder.translation("mmcece.config.auto_assembly.timeBudgetMillis").comment(
        "Shared server-thread time budget for survival material planning and construction each tick, in milliseconds.",
        "Individual capability and placement callbacks finish atomically and cannot be preempted.")
        .defineInRange("timeBudgetMillis", 2, 1, 20);
    ASSEMBLY_WORK_PER_TICK = builder.translation("mmcece.config.auto_assembly.workPerTick")
        .comment("Maximum shared material capture and construction work each tick; the time budget also applies.")
        .defineInRange("workPerTick", 4096, 1, 262144);
    ASSEMBLY_SLICE_WORK = builder.translation("mmcece.config.auto_assembly.sliceWork")
        .comment(
            "Maximum work for one player's construction slice before rotating to the next player.",
            "A player may receive more slices in the same tick while shared budget remains.")
        .defineInRange("sliceWork", 64, 1, 4096);
    builder.pop();

    FLUID_HATCH_CAPACITY = hatchValues(builder, "fluid_hatch", HatchTier.FLUIDS, false, Integer.MAX_VALUE);
    CHEMICAL_HATCH_CAPACITY = chemicalValues(builder);
    ENERGY_HATCH_CAPACITY = energyValues(builder, "energy_hatch", false, Long.MAX_VALUE);
    ENERGY_HATCH_TRANSFER = energyValues(builder, "energy_hatch_transfer", true, Long.MAX_VALUE - 1);
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

  public static boolean cleanCustomDataOnStructureFailure() {
    return SPEC.isLoaded() && CLEAN_CUSTOM_DATA_ON_STRUCTURE_CHECK_FAILED.get();
  }

  public static boolean durationMultiplierEnabled() {
    return SPEC.isLoaded() && ENABLE_DURATION_MULTIPLIER.get();
  }

  public static boolean securitySystemEnabled() {
    return SPEC.isLoaded() && ENABLE_SECURITY_SYSTEM.get();
  }

  public static int recipeCommandPermissionLevel() {
    return SPEC.isLoaded() ? RECIPE_COMMAND_PERMISSION_LEVEL.get() : 2;
  }

  public static int defaultCasingColor() {
    return MachineStartupConfig.SPEC.isLoaded() ? MachineStartupConfig.defaultCasingColorAtStartup() : 0xFFFFFF;
  }

  public static RecipeFailureAction defaultFailureAction() {
    return SPEC.isLoaded() ? RecipeFailureAction.parse(DEFAULT_FAILURE_ACTION.get()) : RecipeFailureAction.STILL;
  }

  public static boolean machineParallelDefault() {
    return !SPEC.isLoaded() || MACHINE_PARALLEL_DEFAULT.get();
  }

  public static boolean recipeParallelDefault() {
    return !SPEC.isLoaded() || RECIPE_PARALLEL_DEFAULT.get();
  }

  public static int defaultMaxMachineParallelism() {
    return SPEC.isLoaded() ? DEFAULT_MAX_MACHINE_PARALLELISM.get() : 2048;
  }

  public static int defaultFactoryMaxThreads() {
    return SPEC.isLoaded() ? DEFAULT_FACTORY_MAX_THREADS.get() : 10;
  }

  public static long assemblyTimeBudgetNanos() {
    long milliseconds = SPEC.isLoaded() ? ASSEMBLY_TIME_BUDGET_MILLIS.get() : 2;

    return milliseconds * 1_000_000L;
  }

  public static int assemblyWorkPerTick() {
    return SPEC.isLoaded() ? ASSEMBLY_WORK_PER_TICK.get() : 4096;
  }

  public static int assemblySliceWork() {
    return SPEC.isLoaded() ? ASSEMBLY_SLICE_WORK.get() : 64;
  }
  public static long fluidHatchCapacity(HatchTier tier) {
    return hatchValue(FLUID_HATCH_CAPACITY, tier.name(), tier.capacity());
  }

  public static long chemicalHatchCapacity(HatchTier tier) {
    var value = CHEMICAL_HATCH_CAPACITY.get(tier.name());

    return value == null || !SPEC.isLoaded() ? tier.capacity() : value.get();
  }

  public static long energyHatchCapacity(HatchTier tier) {
    return energyValue(ENERGY_HATCH_CAPACITY, tier, tier.capacity());
  }

  public static long energyHatchTransfer(HatchTier tier) {
    return energyValue(ENERGY_HATCH_TRANSFER, tier, tier.transferLimit());
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

  private static long energyValue(Map<String, ModConfigSpec.LongValue> values, HatchTier tier, long fallback) {
    var value = values.get(tier.name());

    return value == null || !SPEC.isLoaded() ? fallback : value.get();
  }

  private static Map<String, ModConfigSpec.LongValue> chemicalValues(ModConfigSpec.Builder builder) {
    builder.translation("mmcece.config.chemical_hatch").push("chemical_hatch");
    var values = new LinkedHashMap<String, ModConfigSpec.LongValue>();
    for (var tier : HatchTier.CHEMICALS) {
      values.put(
          tier.name(),
          builder.translation("mmcece.config.chemical_hatch." + tier.name()).comment(
              "Chemical tank capacity in mB, independent of fluid hatch settings. Re-enter the world after changing.")
              .worldRestart().defineInRange(tier.name(), tier.capacity(), 1L, Long.MAX_VALUE));
    }
    builder.pop();

    return Map.copyOf(values);
  }

  private static Map<String, ModConfigSpec.LongValue> energyValues(ModConfigSpec.Builder builder, String section, boolean transfer, long maximum) {
    builder.translation("mmcece.config." + section).push(section);
    var values = new LinkedHashMap<String, ModConfigSpec.LongValue>();
    for (var tier : HatchTier.ENERGY) {
      long fallback = transfer ? Math.max(1, tier.transferLimit()) : tier.capacity();
      values.put(
          tier.name(),
          builder.translation("mmcece.config." + section + "." + tier.name()).comment(
              "Internal FE amount; reload the world to rebuild existing block entity buffers after changing this value.")
              .worldRestart().defineInRange(tier.name(), fallback, 1L, maximum));
    }
    builder.pop();

    return Map.copyOf(values);
  }
}
