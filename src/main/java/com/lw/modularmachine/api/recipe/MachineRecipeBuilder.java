package com.lw.modularmachine.api.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** Builds a validated machine-recipe data-pack resource. */
public final class MachineRecipeBuilder {
  private record ItemRow(ItemStack stack, long amount, double chance, String selector, String match) {
  }

  private final ResourceLocation id;
  private final ResourceLocation machine;
  private final int duration;
  private final List<ItemRow> inputs = new ArrayList<>();
  private final List<ItemRow> outputs = new ArrayList<>();
  private final List<FluidStack> fluidInputs = new ArrayList<>();
  private final List<FluidStack> fluidOutputs = new ArrayList<>();
  private final List<SmartNumberRequirement> interfaceInputs = new ArrayList<>();
  private final List<RecipeModifier> modifiers = new ArrayList<>();
  private RecipeSelectors selectors = RecipeSelectors.NONE;
  private RecipeFailureAction failureAction;
  private Boolean parallelized;
  private int priority;
  private long energyPerTick, randomSeed;
  private FluidStack fluidPerTick = FluidStack.EMPTY;

  private MachineRecipeBuilder(ResourceLocation id, ResourceLocation machine, int duration) {
    this.id = Objects.requireNonNull(id, "id");
    this.machine = Objects.requireNonNull(machine, "machine");
    if (!id.getPath().startsWith("mmcece/")) {
      throw new IllegalArgumentException("Machine recipe IDs must use data/<namespace>/recipe/mmcece/");
    }
    if (duration < 1) {
      throw new IllegalArgumentException("Recipe duration must be positive");
    }
    this.duration = duration;
  }

  public static MachineRecipeBuilder create(ResourceLocation id, ResourceLocation machine, int duration) {
    return new MachineRecipeBuilder(id, machine, duration);
  }

  public ResourceLocation id() {
    return id;
  }

  public MachineRecipeBuilder priority(int priority) {
    this.priority = priority;
    return this;
  }

  public MachineRecipeBuilder energyPerTick(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("Energy per tick cannot be negative");
    }
    energyPerTick = amount;
    return this;
  }

  public MachineRecipeBuilder fluidPerTick(FluidStack fluid) {
    fluidPerTick = requireFluid(fluid);
    return this;
  }

  public MachineRecipeBuilder fluidInput(FluidStack fluid) {
    fluidInputs.add(requireFluid(fluid));
    return this;
  }

  public MachineRecipeBuilder fluidOutput(FluidStack fluid) {
    fluidOutputs.add(requireFluid(fluid));
    return this;
  }

  public MachineRecipeBuilder itemInput(ItemStack stack, long amount) {
    return itemInput(stack, amount, 1, "");
  }

  public MachineRecipeBuilder itemInput(ItemStack stack, long amount, double chance, String selector) {
    if (!inputs.isEmpty() && inputs.getFirst().match().equals("item")) {
      throw new IllegalArgumentException("Item-only matching requires a single item input row");
    }
    inputs.add(itemRow(stack, amount, chance, selector));
    return this;
  }

  /** One item input, matching its item identity while allowing any components. */
  public MachineRecipeBuilder itemInputIgnoringComponents(ItemStack stack, long amount) {
    if (!inputs.isEmpty()) {
      throw new IllegalArgumentException("Item-only matching requires a single item input row");
    }
    var row = itemRow(stack, amount, 1, "");
    inputs.add(new ItemRow(row.stack(), row.amount(), row.chance(), row.selector(), "item"));

    return this;
  }

  public MachineRecipeBuilder itemOutput(ItemStack stack, long amount) {
    return itemOutput(stack, amount, 1, "");
  }

  public MachineRecipeBuilder itemOutput(ItemStack stack, long amount, double chance, String selector) {
    outputs.add(itemRow(stack, amount, chance, selector));
    return this;
  }

  public MachineRecipeBuilder interfaceInput(SmartNumberRequirement requirement) {
    interfaceInputs.add(Objects.requireNonNull(requirement, "requirement"));
    return this;
  }

  public MachineRecipeBuilder selectors(RecipeSelectors selectors) {
    this.selectors = Objects.requireNonNull(selectors, "selectors");
    return this;
  }

  public MachineRecipeBuilder modifier(RecipeModifier modifier) {
    modifiers.add(Objects.requireNonNull(modifier, "modifier"));
    return this;
  }

  public MachineRecipeBuilder failureAction(RecipeFailureAction failureAction) {
    this.failureAction = Objects.requireNonNull(failureAction, "failureAction");
    return this;
  }

  public MachineRecipeBuilder parallelized(boolean enabled) {
    parallelized = enabled;
    return this;
  }

  public MachineRecipeBuilder randomSeed(long randomSeed) {
    if (randomSeed < 0) {
      throw new IllegalArgumentException("Random seed cannot be negative");
    }
    this.randomSeed = randomSeed;
    return this;
  }

  /**
   * Encodes and validates the recipe through the same native codec used by JSON
   * and KubeJS resources. The returned object is a fresh, mutable JSON tree and
   * is not registered into a running RecipeManager.
   */
  public JsonObject toJson(HolderLookup.Provider registries) {
    Objects.requireNonNull(registries, "registries");
    var json = new JsonObject();
    json.addProperty("type", MachineRecipeJson.TYPE);
    json.addProperty("machine", machine.toString());
    json.addProperty("priority", priority);
    json.addProperty("duration", duration);
    if (energyPerTick > 0) {
      json.addProperty("energy_per_tick", energyPerTick);
    }
    if (!fluidPerTick.isEmpty()) {
      json.add("fluid_per_tick", encode(FluidStack.CODEC, fluidPerTick, registries));
    }
    addFluids(json, "fluid_inputs", fluidInputs, registries);
    addFluids(json, "fluid_outputs", fluidOutputs, registries);
    if (failureAction != null) {
      json.addProperty("failure_action", failureAction.serialized());
    }
    if (parallelized != null) {
      json.addProperty("parallelized", parallelized);
    }
    if (randomSeed > 0) {
      json.addProperty("random_seed", randomSeed);
    }
    addItems(json, "inputs", inputs, registries);
    addItems(json, "outputs", outputs, registries);
    if (!interfaceInputs.isEmpty()) {
      json.add("interface_inputs", encode(Codec.list(SmartNumberRequirement.CODEC), interfaceInputs, registries));
    }
    if (!selectors.equals(RecipeSelectors.NONE)) {
      json.add("selectors", encode(RecipeSelectors.CODEC, selectors, registries));
    }
    if (!modifiers.isEmpty()) {
      json.add("modifiers", encode(Codec.list(RecipeModifier.CODEC), modifiers, registries));
    }

    MachineRecipeJson.validate(json);
    MachineRecipe.CODEC.codec().parse(registries.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow();

    return json;
  }

  private static ItemRow itemRow(ItemStack stack, long amount, double chance, String selector) {
    Objects.requireNonNull(stack, "stack");
    if (stack.isEmpty() || stack.getCount() != 1) {
      throw new IllegalArgumentException("Use a nonempty single-item prototype and pass its amount separately");
    }
    if (amount <= 0 || !Double.isFinite(chance) || chance < 0 || chance > 1) {
      throw new IllegalArgumentException("Invalid item amount or chance");
    }
    return new ItemRow(stack.copy(), amount, chance, ResourceSelector.require(selector), "exact");
  }

  private static FluidStack requireFluid(FluidStack fluid) {
    Objects.requireNonNull(fluid, "fluid");
    if (fluid.isEmpty()) {
      throw new IllegalArgumentException("Fluid requirement cannot be empty");
    }
    return fluid.copy();
  }

  private static void addItems(JsonObject target, String field, List<ItemRow> rows, HolderLookup.Provider registries) {
    var entries = new JsonArray();
    for (var row : rows) {
      var entry = new JsonObject();
      entry.add("stack", encode(ItemStack.SINGLE_ITEM_CODEC, row.stack(), registries));
      entry.addProperty("amount", row.amount());
      if (row.chance() != 1) {
        entry.addProperty("chance", row.chance());
      }
      if (!row.selector().isEmpty()) {
        entry.addProperty("selector", row.selector());
      }
      if (!row.match().equals("exact")) {
        entry.addProperty("match", row.match());
      }
      entries.add(entry);
    }
    target.add(field, entries);
  }

  private static void addFluids(JsonObject target, String field, List<FluidStack> fluids, HolderLookup.Provider registries) {
    if (!fluids.isEmpty()) {
      target.add(field, encode(Codec.list(FluidStack.CODEC), fluids, registries));
    }
  }

  private static <T> JsonElement encode(Codec<T> codec, T value, HolderLookup.Provider registries) {
    return codec.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), value).getOrThrow();
  }
}
