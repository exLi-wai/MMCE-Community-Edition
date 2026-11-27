package com.lw.modularmachine.api.recipe;

import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.recipe.MachineRecipeCompiler;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.resource.ItemRecipeData;
import com.lw.modularmachine.machine.recipe.resource.FluidRecipeData;
import com.lw.modularmachine.machine.recipe.resource.EnergyRecipeData;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceBuilderDeclarations;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.Optional;

/**
 * Builds original machine-recipe declarations for Java registration, adapters
 * and data generation.
 */
public final class MachineRecipeBuilder {
  private final ResourceLocation id;
  private final ResourceLocation machine;
  private final int duration;
  private final List<ItemAmount> inputs = new ArrayList<>();
  private final List<ItemAmount> outputs = new ArrayList<>();
  private final List<FluidRequirement> fluidInputs = new ArrayList<>();
  private final List<FluidRequirement> fluidOutputs = new ArrayList<>();
  private final List<ChemicalRequirement> chemicalInputs = new ArrayList<>();
  private final List<ChemicalRequirement> chemicalOutputs = new ArrayList<>();
  private final List<ChemicalRequirement> chemicalPerTick = new ArrayList<>();
  private final List<ChemicalRequirement> chemicalPerTickOutputs = new ArrayList<>();
  private final List<SmartNumberRequirement> interfaceInputs = new ArrayList<>();
  private final List<RecipeModifier> modifiers = new ArrayList<>();
  private final Map<ResourceLocation, RecipeResources.Entry<?>> resourceDeclarations = new LinkedHashMap<>();
  private RecipeSelectors selectors = RecipeSelectors.NONE;
  private RecipeFailureAction failureAction;
  private Boolean parallelized;
  private int priority;
  private long energyPerTick, energyOutputPerTick, randomSeed;
  private RecipeExecutionOptions executionOptions = RecipeExecutionOptions.NONE;
  private final List<FluidRequirement> fluidPerTickInputs = new ArrayList<>();
  private final List<FluidRequirement> fluidPerTickOutputs = new ArrayList<>();

  private MachineRecipeBuilder(ResourceLocation id, ResourceLocation machine, int duration) {
    this.id = Objects.requireNonNull(id, "id");
    this.machine = Objects.requireNonNull(machine, "machine");
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

  /**
   * Replaces this registered resource's complete native fields when the recipe is
   * encoded.
   */
  public <D> MachineRecipeBuilder setResource(RecipeResourceType<D> type, D data) {
    var entry = RecipeResourceBuilderDeclarations.capture(type, data);
    resourceDeclarations.put(type.id(), entry);

    return this;
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
    return fluidPerTickInput(fluid);
  }

  public MachineRecipeBuilder fluidPerTickInput(FluidStack fluid) {
    fluidPerTickInputs.add(new FluidRequirement(requireFluid(fluid), 1));
    return this;
  }

  public MachineRecipeBuilder fluidPerTickInput(FluidStack fluid, double chance, String selector) {
    fluidPerTickInputs.add(new FluidRequirement(requireFluid(fluid), chance, false, selector));
    return this;
  }

  public MachineRecipeBuilder fluidPerTickOutput(FluidStack fluid) {
    fluidPerTickOutputs.add(new FluidRequirement(requireFluid(fluid), 1));
    return this;
  }

  public MachineRecipeBuilder fluidPerTickOutput(FluidStack fluid, double chance, String selector) {
    fluidPerTickOutputs.add(new FluidRequirement(requireFluid(fluid), chance, false, selector));
    return this;
  }

  public MachineRecipeBuilder fluidInput(FluidStack fluid) {
    return fluidInput(fluid, 1);
  }

  public MachineRecipeBuilder fluidInput(FluidStack fluid, double chance) {
    fluidInputs.add(new FluidRequirement(requireFluid(fluid), chance));
    return this;
  }

  public MachineRecipeBuilder fluidInput(FluidStack fluid, double chance, String selector) {
    fluidInputs.add(new FluidRequirement(requireFluid(fluid), chance, false, selector));
    return this;
  }

  public MachineRecipeBuilder fluidOutput(FluidStack fluid) {
    return fluidOutput(fluid, 1);
  }

  public MachineRecipeBuilder fluidOutput(FluidStack fluid, double chance) {
    fluidOutputs.add(new FluidRequirement(requireFluid(fluid), chance));
    return this;
  }

  public MachineRecipeBuilder fluidOutput(FluidStack fluid, double chance, String selector) {
    fluidOutputs.add(new FluidRequirement(requireFluid(fluid), chance, false, selector));
    return this;
  }

  public MachineRecipeBuilder energyOutputPerTick(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("Energy output per tick cannot be negative");
    }
    energyOutputPerTick = amount;
    return this;
  }

  public MachineRecipeBuilder executionOptions(RecipeExecutionOptions options) {
    executionOptions = Objects.requireNonNull(options, "options");
    return this;
  }

  public MachineRecipeBuilder chemicalInput(ResourceLocation chemical, long amount) {
    return chemicalInput(chemical, amount, "");
  }

  public MachineRecipeBuilder chemicalInput(ResourceLocation chemical, long amount, String selector) {
    return chemicalInput(chemical, amount, 1, selector);
  }

  public MachineRecipeBuilder chemicalInput(ResourceLocation chemical, long amount, double chance, String selector) {
    chemicalInputs.add(new ChemicalRequirement(chemical, amount, selector, chance));
    return this;
  }

  public MachineRecipeBuilder chemicalOutput(ResourceLocation chemical, long amount) {
    return chemicalOutput(chemical, amount, "");
  }

  public MachineRecipeBuilder chemicalOutput(ResourceLocation chemical, long amount, String selector) {
    return chemicalOutput(chemical, amount, 1, selector);
  }

  public MachineRecipeBuilder chemicalOutput(ResourceLocation chemical, long amount, double chance, String selector) {
    chemicalOutputs.add(new ChemicalRequirement(chemical, amount, selector, chance));
    return this;
  }

  public MachineRecipeBuilder chemicalPerTick(ResourceLocation chemical, long amount) {
    return chemicalPerTick(chemical, amount, "");
  }

  public MachineRecipeBuilder chemicalPerTick(ResourceLocation chemical, long amount, String selector) {
    return chemicalPerTick(chemical, amount, 1, selector);
  }

  public MachineRecipeBuilder chemicalPerTick(ResourceLocation chemical, long amount, double chance, String selector) {
    chemicalPerTick.add(new ChemicalRequirement(chemical, amount, selector, chance));
    return this;
  }

  public MachineRecipeBuilder chemicalPerTickOutput(ResourceLocation chemical, long amount) {
    return chemicalPerTickOutput(chemical, amount, 1, "");
  }

  public MachineRecipeBuilder chemicalPerTickOutput(ResourceLocation chemical, long amount, String selector) {
    return chemicalPerTickOutput(chemical, amount, 1, selector);
  }

  public MachineRecipeBuilder chemicalPerTickOutput(ResourceLocation chemical, long amount, double chance, String selector) {
    chemicalPerTickOutputs.add(new ChemicalRequirement(chemical, amount, selector, chance));
    return this;
  }

  public MachineRecipeBuilder itemInput(ItemStack stack, long amount) {
    return itemInput(stack, amount, 1, "");
  }

  public MachineRecipeBuilder itemInput(ItemStack stack, long amount, double chance, String selector) {
    if (!inputs.isEmpty() && inputs.getFirst().match().equals("item")) {
      throw new IllegalArgumentException("Item-only matching requires a single item input row");
    }
    inputs.add(itemRow(stack, amount, chance, selector, "exact"));
    return this;
  }

  /** One item input, matching its item identity while allowing any components. */
  public MachineRecipeBuilder itemInputIgnoringComponents(ItemStack stack, long amount) {
    if (!inputs.isEmpty()) {
      throw new IllegalArgumentException("Item-only matching requires a single item input row");
    }
    inputs.add(itemRow(stack, amount, 1, "", "item"));

    return this;
  }

  public MachineRecipeBuilder itemOutput(ItemStack stack, long amount) {
    return itemOutput(stack, amount, 1, "");
  }

  public MachineRecipeBuilder itemOutput(ItemStack stack, long amount, double chance, String selector) {
    outputs.add(itemRow(stack, amount, chance, selector, "exact"));
    return this;
  }

  public MachineRecipeBuilder interfaceInput(SmartNumberRequirement requirement) {
    interfaceInputs.add(Objects.requireNonNull(requirement, "requirement"));
    return this;
  }

  public MachineRecipeBuilder environment(EnvironmentRequirement... requirements) {
    return setResource(RecipeResourceDefinitions.ENVIRONMENT, new EnvironmentRecipeData(List.of(requirements)));
  }

  public MachineRecipeBuilder laserInput(long amount) {
    return setResource(RecipeResourceDefinitions.LASER, new LaserRecipeData(amount));
  }

  public MachineRecipeBuilder emberInput(EmberRecipeData amount) {
    return setResource(RecipeResourceDefinitions.EMBER, amount);
  }

  public MachineRecipeBuilder mana(long input, long output) {
    return setResource(RecipeResourceDefinitions.MANA, new BufferedAmountRecipeData(input, output));
  }

  public MachineRecipeBuilder heat(long inputMilliheat, long outputMilliheat) {
    return setResource(RecipeResourceDefinitions.HEAT, new BufferedAmountRecipeData(inputMilliheat, outputMilliheat));
  }

  public MachineRecipeBuilder auraInput(long input) {
    return setResource(RecipeResourceDefinitions.AURA, new BufferedAmountRecipeData(input, 0));
  }

  public MachineRecipeBuilder auraOutput(WorldPoolRecipeData.Claim output) {
    return setResource(
        RecipeResourceDefinitions.AURA_WORLD,
        new WorldPoolRecipeData(Optional.empty(), Optional.of(output)));
  }

  public MachineRecipeBuilder will(WorldPoolRecipeData declaration) {
    return setResource(RecipeResourceDefinitions.WILL, declaration);
  }

  public MachineRecipeBuilder vis(WorldPoolRecipeData declaration) {
    return setResource(RecipeResourceDefinitions.VIS, declaration);
  }

  public MachineRecipeBuilder flux(WorldPoolRecipeData declaration) {
    return setResource(RecipeResourceDefinitions.FLUX, declaration);
  }

  public MachineRecipeBuilder compressedAir(CompressedAirRecipeData declaration) {
    return setResource(RecipeResourceDefinitions.COMPRESSED_AIR, declaration);
  }

  public MachineRecipeBuilder lifeEssence(long input, long output) {
    return setResource(RecipeResourceDefinitions.LIFE_ESSENCE, new BufferedAmountRecipeData(input, output));
  }

  public MachineRecipeBuilder meteorOutput(ResourceLocation catalyst, int count) {
    return setResource(
        RecipeResourceDefinitions.METEOR,
        new MeteorRecipeData(Optional.of(new MeteorRecipeData.Launch(catalyst, count))));
  }

  public MachineRecipeBuilder essentia(KeyedAmountRecipeData declaration) {
    return setResource(RecipeResourceDefinitions.ESSENTIA, declaration);
  }

  public MachineRecipeBuilder starlight(KeyedAmountRecipeData declaration) {
    return setResource(RecipeResourceDefinitions.STARLIGHT, declaration);
  }

  public MachineRecipeBuilder constellations(ConstellationRecipeData declaration) {
    return setResource(RecipeResourceDefinitions.CONSTELLATION, declaration);
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
   * Captures the original, unmodified definition for registration or adapter
   * import.
   */
  public MachineRecipeDeclaration build(HolderLookup.Provider registries) {
    Objects.requireNonNull(registries, "registries");
    var resources = new LinkedHashMap<ResourceLocation, RecipeResources.Entry<?>>();
    putResource(resources, RecipeResourceDefinitions.ITEM, () -> new ItemRecipeData(inputs, outputs));
    putResource(
        resources,
        RecipeResourceDefinitions.FLUID,
        () -> new FluidRecipeData(fluidInputs, fluidOutputs, fluidPerTickInputs, fluidPerTickOutputs));
    putResource(
        resources,
        RecipeResourceDefinitions.ENERGY,
        () -> new EnergyRecipeData(energyPerTick, energyOutputPerTick));
    putResource(
        resources,
        RecipeResourceDefinitions.CHEMICAL,
        () -> new ChemicalRecipeData(chemicalInputs, chemicalOutputs, chemicalPerTick, chemicalPerTickOutputs));
    resources.putAll(resourceDeclarations);
    var declaration = new MachineRecipeDeclaration(
        machine,
        priority,
        duration,
        new RecipeResources(List.copyOf(resources.values())),
        Optional.ofNullable(failureAction),
        Optional.ofNullable(parallelized),
        randomSeed,
        interfaceInputs,
        selectors,
        executionOptions,
        RecipeScriptOptions.NONE,
        IngredientArrayData.NONE,
        TriggeredResourceData.NONE,
        modifiers,
        false);
    return MachineRecipeCompiler.capture(declaration, registries);
  }

  /**
   * Exports a validated data-pack resource with its original modifiers and
   * optional defaults.
   */
  public JsonObject toJson(HolderLookup.Provider registries) {
    if (!id.getPath().startsWith("mmcece/")) {
      throw new IllegalArgumentException("Machine recipe data-pack IDs must use data/<namespace>/recipe/mmcece/");
    }
    var declaration = build(registries);
    MachineRecipeCompiler.compile(declaration);
    return MachineRecipeJson.encode(declaration, registries);
  }

  private <D> void putResource(Map<ResourceLocation, RecipeResources.Entry<?>> resources, RecipeResourceType<D> type, Supplier<D> fallback) {
    var replacement = resourceDeclarations.get(type.id());
    resources.put(type.id(), replacement != null ? replacement : new RecipeResources.Entry<>(type, fallback.get()));
  }

  private static ItemAmount itemRow(ItemStack stack, long amount, double chance, String selector, String match) {
    return new ItemAmount(
        Objects.requireNonNull(stack, "stack"),
        amount,
        chance,
        selector,
        match,
        ItemStack.EMPTY,
        false,
        0,
        0,
        false);
  }

  private static FluidStack requireFluid(FluidStack fluid) {
    Objects.requireNonNull(fluid, "fluid");
    if (fluid.isEmpty()) {
      throw new IllegalArgumentException("Fluid requirement cannot be empty");
    }
    return fluid.copy();
  }

}
