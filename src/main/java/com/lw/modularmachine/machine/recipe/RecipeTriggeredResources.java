package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.resource.FluidRecipeData;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;

/**
 * Internal scheduled IO rules: declaration normalization and arithmetic checks
 * operate on detached values. Runtime expansion combines due rows with native
 * tick demands; resource participants retain all world reads and writes.
 */
@ApiStatus.Internal
public final class RecipeTriggeredResources {
  private static final Set<String> ROW_FIELDS = Set.of(
      "resource",
      "io",
      "tick",
      "repeatable",
      "amount",
      "chance",
      "selector",
      "item",
      "fluid",
      "chemical",
      "parallel_unaffected",
      "match");

  private RecipeTriggeredResources() {
  }

  private record Position(String resource, RecipeModifier.Io io, int tick) {
  }

  private sealed interface ResourceIdentity {
  }

  private record ItemIdentity(Item item, DataComponentMap components) implements ResourceIdentity {
  }

  private record FluidIdentity(Fluid fluid, DataComponentMap components) implements ResourceIdentity {
  }

  private record ChemicalIdentity(ResourceLocation chemical) implements ResourceIdentity {
  }

  private record QuantityKey(RecipeModifier.Io io, String selector, ResourceIdentity identity) {
  }

  private record Bound(BigInteger fixed, BigInteger scaled) {
    static final Bound ZERO = new Bound(BigInteger.ZERO, BigInteger.ZERO);

    Bound plus(long amount, boolean fixedQuantity) {
      var value = BigInteger.valueOf(amount);
      return fixedQuantity ? new Bound(fixed.add(value), scaled) : new Bound(fixed, scaled.add(value));
    }

    Bound plus(Bound other) {
      return new Bound(fixed.add(other.fixed), scaled.add(other.scaled));
    }

    int limit(int current, long maximum) {
      var available = BigInteger.valueOf(maximum).subtract(fixed);
      if (available.signum() < 0) {
        return 0;
      }
      return scaled.signum() == 0 ? current : available.divide(scaled).min(BigInteger.valueOf(current)).intValueExact();
    }
  }

  static void validate(JsonObject root, int duration) {
    if (!root.has("triggered_resources")) {
      return;
    }
    var rows = root.getAsJsonArray("triggered_resources");
    if (rows.size() > TriggeredResourceData.MAX_ROWS) {
      throw new IllegalArgumentException("Too many triggered resource rows");
    }
    var combinedCounts = new HashMap<Position, Integer>();
    for (var value : rows) {
      var position = validateRow(value.getAsJsonObject(), duration);
      int combined = combinedCounts
          .merge(position, 1, Integer::sum) + normalRows(root, position.resource(), position.io());
      if (combined > MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many combined trigger and per-tick rows at one progress position");
      }
    }
  }

  static void validate(TriggeredResourceData data, int duration, FluidRecipeData fluids, ChemicalRecipeData chemicals) {
    var counts = new HashMap<Position, Integer>();
    for (var row : data.rows()) {
      if (row.tick() > duration) {
        throw new IllegalArgumentException("Trigger position outside recipe duration");
      }
      int ordinary = switch (row.resource()) {
        case "fluid" ->
          row.io() == RecipeModifier.Io.INPUT ? fluids.perTickInputs().size() : fluids.perTickOutputs().size();
        case "chemical" ->
          row.io() == RecipeModifier.Io.INPUT ? chemicals.perTick().size() : chemicals.perTickOutputs().size();
        default -> 0;
      };
      int count = counts.merge(new Position(row.resource(), row.io(), row.tick()), 1, Integer::sum);
      if (ordinary + count > MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Too many combined trigger and per-tick rows at one progress position");
      }
    }
  }

  /**
   * Structural validation also serves NBT reads before registry-backed codecs.
   */
  private static Position validateRow(JsonObject row, int duration) {
    if (!ROW_FIELDS.containsAll(row.keySet())
        || !row.keySet().containsAll(Set.of("resource", "io", "tick", "amount"))) {
      throw new IllegalArgumentException("Missing or unsupported trigger declaration fields");
    }
    int tick = row.get("tick").getAsBigDecimal().intValueExact();
    long amount = row.get("amount").getAsBigDecimal().longValueExact();
    String resource = row.get("resource").getAsString();
    var io = RecipeModifier.Io.parse(row.get("io").getAsString());
    if (tick < 1 || tick > duration || amount < 1 || !List.of("item", "fluid", "chemical").contains(resource)) {
      throw new IllegalArgumentException("Invalid trigger declaration or position outside duration");
    }
    double chance = row.has("chance") ? row.get("chance").getAsDouble() : 1;
    if (!Double.isFinite(chance)
        || chance < 0
        || chance > 1
        || row.has("repeatable") && !row.get("repeatable").getAsJsonPrimitive().isBoolean()
        || row.has("parallel_unaffected") && !row.get("parallel_unaffected").getAsJsonPrimitive().isBoolean()) {
      throw new IllegalArgumentException("Invalid trigger probability or flags");
    }
    String match = row.has("match") ? row.get("match").getAsString() : "exact";
    if (!row.has(resource)
        || List.of("item", "fluid", "chemical").stream().anyMatch(other -> !other.equals(resource) && row.has(other))
        || resource.equals("fluid") && amount > Integer.MAX_VALUE
        || !(match.equals("exact") || resource.equals("item") && io == RecipeModifier.Io.INPUT && match.equals("item"))
        || resource.equals("chemical")
            && row.has("parallel_unaffected")
            && row.get("parallel_unaffected").getAsBoolean()) {
      throw new IllegalArgumentException("Invalid trigger resource prototype, quantity or match rule");
    }
    ResourceSelector.require(row.has("selector") ? row.get("selector").getAsString() : "");

    return new Position(resource, io, tick);
  }

  private static int normalRows(JsonObject root, String resource, RecipeModifier.Io io) {
    if (resource.equals("fluid")) {
      String field = io == RecipeModifier.Io.INPUT ? "fluid_per_tick_inputs" : "fluid_per_tick_outputs";
      return (root.has(field) ? root.getAsJsonArray(field).size() : 0) + (io == RecipeModifier.Io.INPUT
          && root.has("fluid_per_tick") ? 1 : 0);
    }
    if (resource.equals("chemical") && root.has("chemicals")) {
      var chemicals = root.getAsJsonObject("chemicals");
      String field = io == RecipeModifier.Io.INPUT ? "per_tick" : "per_tick_outputs";
      return chemicals.has(field) ? chemicals.getAsJsonArray(field).size() : 0;
    }
    return 0;
  }

  static void validateRegistry(TriggeredResourceData data) {
    for (var row : data.rows()) {
      if (row.resource().equals("chemical")
          && (!ChemicalPlatform.available() || !ChemicalPlatform.contains(row.chemical().orElseThrow()))) {
        throw new IllegalArgumentException("Triggered Chemical IO requires a known chemical and its integration");
      }
    }
  }

  static boolean has(JsonObject root, RecipeModifier.Io io) {
    return root.has("triggered_resources")
        && root.getAsJsonArray("triggered_resources").asList().stream()
            .anyMatch(value -> value.getAsJsonObject().get("io").getAsString().equals(io.serialized()));
  }

  static void upgrade(CompoundTag definition, int originalDuration, int duration) {
    for (var value : definition.getList("triggered_resources", Tag.TAG_COMPOUND)) {
      var row = (CompoundTag) value;
      row.putInt("tick", scaledTick(row.getInt("tick"), originalDuration, duration));
    }
  }

  static void scale(CompoundTag definition, int instances) {
    for (var value : definition.getList("triggered_resources", Tag.TAG_COMPOUND)) {
      var row = (CompoundTag) value;
      if (row.getBoolean("parallel_unaffected")) {
        continue;
      }
      long amount = Math.multiplyExact(row.getLong("amount"), instances);
      if (row.getString("resource").equals("fluid")) {
        Math.toIntExact(amount);
      }
      row.putLong("amount", amount);
    }
  }

  static int scaledTick(int tick, int before, int after) {
    return Math.toIntExact(Math.min(after, Math.max(1, ((long) tick * after + before - 1) / before)));
  }

  static int arithmeticLimit(MachineRecipe recipe, int limit, boolean aggregate, UpgradeSnapshot upgrades) {
    if (recipe.triggeredResources().isEmpty()) {
      return limit;
    }
    var normal = new HashMap<QuantityKey, Bound>();
    var events = new HashMap<Integer, Map<QuantityKey, Bound>>();
    try {
      int duration = upgrades.durationFor(recipe.duration());
      addNormalChemicals(normal, recipe.chemicals().perTick(), RecipeModifier.Io.INPUT);
      addNormalChemicals(normal, recipe.chemicals().perTickOutputs(), RecipeModifier.Io.OUTPUT);
      addNormalFluids(
          normal,
          recipe.fluidPerTickInputs(),
          RecipeModifier.Io.INPUT,
          recipe.selectors().fluidPerTick(),
          upgrades);
      addNormalFluids(normal, recipe.fluidPerTickOutputs(), RecipeModifier.Io.OUTPUT, "", upgrades);
      for (var row : recipe.triggeredResources().rows()) {
        long maximum = row.resource().equals("fluid") ? Integer.MAX_VALUE : Long.MAX_VALUE;
        boolean fixed = aggregate && row.parallelUnaffected();
        limit = Bound.ZERO.plus(row.amount(), fixed).limit(limit, maximum);
        var at = events
            .computeIfAbsent(scaledTick(row.tick(), recipe.duration(), duration), ignored -> new HashMap<>());
        var key = new QuantityKey(row.io(), row.selector(), identity(row));
        at.put(key, at.getOrDefault(key, Bound.ZERO).plus(row.amount(), fixed));
      }
      for (var quantities : events.values()) {
        for (var entry : normal.entrySet()) {
          quantities.merge(entry.getKey(), entry.getValue(), Bound::plus);
        }
        for (var bound : quantities.values()) {
          limit = bound.limit(limit, Long.MAX_VALUE);
        }
      }
    } catch (ArithmeticException invalid) {
      return 0;
    }
    return limit;
  }

  private static void addNormalChemicals(Map<QuantityKey, Bound> quantities, List<ChemicalRequirement> rows, RecipeModifier.Io io) {
    for (var row : rows) {
      var key = new QuantityKey(io, row.selector(), new ChemicalIdentity(row.chemical()));
      quantities.put(key, quantities.getOrDefault(key, Bound.ZERO).plus(row.amount(), false));
    }
  }

  private static void addNormalFluids(Map<QuantityKey, Bound> quantities, List<FluidRequirement> rows, RecipeModifier.Io io, String fallback, UpgradeSnapshot upgrades) {
    for (var row : rows) {
      var fluid = row.stack();
      var key = new QuantityKey(io, row.selector(fallback), new FluidIdentity(fluid.getFluid(), fluid.getComponents()));
      long amount = io == RecipeModifier.Io.INPUT ? upgrades.fluidPerTick(fluid.getAmount()) : fluid.getAmount();
      quantities.put(key, quantities.getOrDefault(key, Bound.ZERO).plus(amount, false));
    }
  }

  private static ResourceIdentity identity(TriggeredResourceData.Row row) {
    return switch (row.resource()) {
      case "chemical" -> new ChemicalIdentity(row.chemical().orElseThrow());
      case "item" -> {
        var item = row.item().orElseThrow();
        yield new ItemIdentity(item.getItem(), item.getComponents());
      }
      case "fluid" -> {
        var fluid = row.fluid().orElseThrow();
        yield new FluidIdentity(fluid.getFluid(), fluid.getComponents());
      }
      default -> throw new IllegalArgumentException("Unsupported trigger resource");
    };
  }

  static boolean due(FrozenRecipeRun run, TriggeredResourceData.Row row) {
    int next = run.progress() + 1;
    return next <= run.duration() && row.tick() == next && (row.repeatable() || next > run.triggerProgress());
  }

  public static PortKind kind(TriggeredResourceData.Row row) {
    boolean input = row.io() == RecipeModifier.Io.INPUT;

    return switch (row.resource()) {
      case "item" -> input ? PortKind.ITEM_INPUT : PortKind.ITEM_OUTPUT;
      case "fluid" -> input ? PortKind.FLUID_INPUT : PortKind.FLUID_OUTPUT;
      case "chemical" -> input ? PortKind.CHEMICAL_INPUT : PortKind.CHEMICAL_OUTPUT;
      default -> throw new IllegalArgumentException("Unsupported trigger resource");
    };
  }

  static List<TriggeredResourceData.Row> dueRows(FrozenRecipeRun run, String resource) {
    return run.triggeredResources().rows().stream().filter(row -> row.resource().equals(resource) && due(run, row))
        .toList();
  }

  /**
   * Combines normal tick fees and due trigger rows before shared physical
   * planning.
   */
  public static RecipeResources effective(FrozenRecipeRun run, RecipeResources original) {
    var entries = new ArrayList<RecipeResources.Entry<?>>();
    for (var entry : original.entries()) {
      if (entry.type() == RecipeResourceDefinitions.FLUID) {
        entries.add(
            new RecipeResources.Entry<>(
                RecipeResourceDefinitions.FLUID,
                effectiveFluids(run, original.get(RecipeResourceDefinitions.FLUID))));
      } else if (entry.type() == RecipeResourceDefinitions.CHEMICAL) {
        entries.add(
            new RecipeResources.Entry<>(
                RecipeResourceDefinitions.CHEMICAL,
                effectiveChemicals(run, original.get(RecipeResourceDefinitions.CHEMICAL))));
      } else {
        entries.add(entry);
      }
    }
    return new RecipeResources(entries);
  }

  private static FluidRecipeData effectiveFluids(FrozenRecipeRun run, FluidRecipeData data) {
    var inputs = new ArrayList<FluidRequirement>();
    for (var row : data.perTickInputs()) {
      String selector = row.selector(run.selectors().fluidPerTick());
      inputs.add(new FluidRequirement(row.stack(), row.chance(), row.parallelUnaffected(), selector));
    }
    // Output fences apply only to ordinary tick rows. Due repeatable IO keeps
    // its explicit right to execute when a resource failure revisits its tick.
    var outputs = new ArrayList<>(run.canEmitTickOutputs() ? data.perTickOutputs() : List.<FluidRequirement>of());
    for (var row : dueRows(run, "fluid")) {
      var requirement = new FluidRequirement(
          row.fluid().orElseThrow().copyWithAmount(Math.toIntExact(row.amount())),
          row.chance(),
          row.parallelUnaffected(),
          row.selector());
      (row.io() == RecipeModifier.Io.INPUT ? inputs : outputs).add(requirement);
    }

    return new FluidRecipeData(data.inputs(), data.outputs(), inputs, outputs);
  }

  private static ChemicalRecipeData effectiveChemicals(FrozenRecipeRun run, ChemicalRecipeData data) {
    var inputs = new ArrayList<>(data.perTick());
    var outputs = new ArrayList<>(run.canEmitTickOutputs() ? data.perTickOutputs() : List.<ChemicalRequirement>of());
    for (var row : dueRows(run, "chemical")) {
      var requirement = new ChemicalRequirement(
          row.chemical().orElseThrow(),
          row.amount(),
          row.selector(),
          row.chance());
      (row.io() == RecipeModifier.Io.INPUT ? inputs : outputs).add(requirement);
    }

    return new ChemicalRecipeData(data.inputs(), data.outputs(), inputs, outputs);
  }

  static long sampledAmount(FrozenRecipeRun run, TriggeredResourceData.Row row, int index) {
    int instances = row.parallelUnaffected() ? 1 : run.parallelInstances();
    if (row.amount() % instances != 0) {
      throw new IllegalArgumentException("Invalid frozen trigger quantity");
    }
    var random = new Random(run.randomSeed() ^ 0x5AF1B012A357FC11L ^ ((long) row.tick() << 32) ^ index);
    int count = row.chance() == 1 ? instances : 0;
    if (row.chance() > 0 && row.chance() < 1) {
      for (int i = 0; i < instances; i++) {
        if (random.nextDouble() < row.chance()) {
          count++;
        }
      }
    }
    return Math.multiplyExact(row.amount() / instances, count);
  }
}
