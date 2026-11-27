package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.ItemAmount;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.ItemPaymentData;
import com.lw.modularmachine.api.recipe.MachineRecipeDeclaration;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeModifier.Io;
import com.lw.modularmachine.api.recipe.RecipeModifier.Target;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.resource.EnergyRecipeData;
import com.lw.modularmachine.machine.recipe.resource.FluidRecipeData;
import com.lw.modularmachine.machine.recipe.resource.ItemRecipeData;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.config.MachineConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import org.jetbrains.annotations.ApiStatus;

/**
 * Common compilation of original declarations from data packs, Java and
 * scripts.
 */
@ApiStatus.Internal
public final class MachineRecipeCompiler {
  private MachineRecipeCompiler() {
  }

  /**
   * Captures component-bearing prototypes before a Java provider regains control.
   */
  public static MachineRecipeDeclaration capture(MachineRecipeDeclaration source, HolderLookup.Provider registries) {
    return RecipeDeclarationSnapshots.capture(source, registries);
  }

  record Defaults(boolean parallelized, RecipeFailureAction failureAction) {
  }

  static Defaults currentDefaults() {
    return new Defaults(MachineConfig.recipeParallelDefault(), MachineConfig.defaultFailureAction());
  }

  public static MachineRecipe compile(MachineRecipeDeclaration source) {
    return compile(source, List.of(), false, currentDefaults());
  }

  static MachineRecipe compile(MachineRecipeDeclaration source, List<RecipeModifier> inherited, boolean durationMultiplier, Defaults defaults) {
    var items = source.resources().get(RecipeResourceDefinitions.ITEM);
    for (int row = 0; row < items.inputs().size(); row++) {
      if (items.inputs().get(row).ignoresComponents()
          && !items.payments().allowsItemOnlyMatch(row, items.inputs().size())) {
        throw new IllegalArgumentException("Item-only matching requires a single item input row");
      }
    }
    return compileResolved(source, inherited, durationMultiplier, defaults);
  }

  static MachineRecipe compileResolved(MachineRecipeDeclaration source, List<RecipeModifier> inherited, boolean durationMultiplier, Defaults defaults) {
    var resources = canonicalResources(source.resources());
    var groups = source.ingredientArrays();
    var triggers = source.triggeredResources();
    int duration = source.duration();
    var fluid = resources.get(RecipeResourceDefinitions.FLUID);
    var chemical = resources.get(RecipeResourceDefinitions.CHEMICAL);
    validateSource(source, fluid, chemical);
    if (!source.modifiers().isEmpty() || !inherited.isEmpty()) {
      var all = new ArrayList<>(source.modifiers());
      all.addAll(inherited);
      var modifiers = new RecipeModifiers(all);
      duration = modifiers.duration(source.duration());
      var energy = resources.get(RecipeResourceDefinitions.ENERGY);
      var scaledEnergy = new EnergyRecipeData(
          modifiers.amount(
              Target.ENERGY,
              Io.INPUT,
              energy.inputPerTick(),
              0,
              Long.MAX_VALUE,
              durationMultiplier ? source.duration() : 1,
              durationMultiplier ? duration : 1),
          modifiers.amount(Target.ENERGY, Io.OUTPUT, energy.outputPerTick(), 0, Long.MAX_VALUE));
      var scaledFluid = new FluidRecipeData(
          fluids(fluid.inputs(), Io.INPUT, modifiers),
          fluids(fluid.outputs(), Io.OUTPUT, modifiers),
          source.legacyFluidPerTick() ? legacyFluid(
              fluid,
              modifiers) : fluids(fluid.perTickInputs(), Io.INPUT, modifiers),
          fluids(fluid.perTickOutputs(), Io.OUTPUT, modifiers));
      var scaledChemical = new ChemicalRecipeData(
          chemicals(chemical.inputs(), Io.INPUT, modifiers),
          chemicals(chemical.outputs(), Io.OUTPUT, modifiers),
          chemicals(chemical.perTick(), Io.INPUT, modifiers),
          chemicals(chemical.perTickOutputs(), Io.OUTPUT, modifiers));
      var item = resources.get(RecipeResourceDefinitions.ITEM);
      var fuels = item.payments().fuels().stream()
          .map(
              row -> new ItemPaymentData.Fuel(
                  modifiers.amount(Target.ITEM, Io.INPUT, row.burnTime(), 1, Long.MAX_VALUE),
                  modifiers.chance(Target.ITEM, Io.INPUT, row.chance()),
                  row.parallelUnaffected(),
                  row.selector()))
          .toList();
      var scaledItem = new ItemRecipeData(
          items(item.inputs(), Io.INPUT, modifiers),
          items(item.outputs(), Io.OUTPUT, modifiers),
          new ItemPaymentData(item.payments().tools(), fuels));
      var entries = new ArrayList<RecipeResources.Entry<?>>();
      for (var entry : resources.entries()) {
        if (entry.type() == RecipeResourceDefinitions.ITEM) {
          entries.add(new RecipeResources.Entry<>(RecipeResourceDefinitions.ITEM, scaledItem));
        } else if (entry.type() == RecipeResourceDefinitions.FLUID) {
          entries.add(new RecipeResources.Entry<>(RecipeResourceDefinitions.FLUID, scaledFluid));
        } else if (entry.type() == RecipeResourceDefinitions.ENERGY) {
          entries.add(new RecipeResources.Entry<>(RecipeResourceDefinitions.ENERGY, scaledEnergy));
        } else if (entry.type() == RecipeResourceDefinitions.CHEMICAL) {
          entries.add(new RecipeResources.Entry<>(RecipeResourceDefinitions.CHEMICAL, scaledChemical));
        } else {
          entries.add(entry);
        }
      }
      resources = new RecipeResources(entries);
      groups = new IngredientArrayData(
          groups(groups.inputs(), Io.INPUT, modifiers),
          groups(groups.randomOutputs(), Io.OUTPUT, modifiers));
      triggers = triggers(triggers, modifiers, source.duration(), duration);
      RecipeTriggeredResources.validate(triggers, duration, scaledFluid, scaledChemical);
    }
    return MachineRecipe.effective(
        source,
        duration,
        resources,
        source.failureAction().orElse(defaults.failureAction()),
        source.parallelized().orElse(defaults.parallelized()),
        groups,
        triggers);
  }

  private static void validateSource(MachineRecipeDeclaration source, FluidRecipeData fluid, ChemicalRecipeData chemical) {
    if (fluid.perTickInputs().stream().anyMatch(FluidRequirement::parallelUnaffected)
        || fluid.perTickOutputs().stream().anyMatch(FluidRequirement::parallelUnaffected)) {
      throw new IllegalArgumentException("Per-tick fluids scale with every parallel instance");
    }
    if (source.legacyFluidPerTick()
        && (fluid.perTickInputs().size() != 1
            || fluid.perTickInputs().getFirst().chance() != 1
            || fluid.perTickInputs().getFirst().selectorSpecified()
            || fluid.perTickInputs().getFirst().ignoreOutputCheck())) {
      throw new IllegalArgumentException("Legacy per-tick fluid requires one unqualified input");
    }
    if (source.resources().get(RecipeResourceDefinitions.AURA).output() != 0
        || source.resources().get(RecipeResourceDefinitions.AURA_WORLD).input().isPresent()) {
      throw new IllegalArgumentException("Aura storage supports input and world aura supports output");
    }
    for (var group : source.ingredientArrays().inputs()) {
      if (!group.recipeModifiers().isEmpty()) {
        new RecipeModifiers(group.recipeModifiers());
      }
    }
    RecipeTriggeredResources.validate(source.triggeredResources(), source.duration(), fluid, chemical);
  }

  /**
   * Keep descriptor order and empty entries identical across all providers and
   * network decoding.
   */
  private static RecipeResources canonicalResources(RecipeResources source) {
    var types = RecipeResourceDefinitions.snapshot();
    for (var entry : source.entries()) {
      RecipeResourceDefinitions.requireRegistered(entry.type());
    }
    var entries = new ArrayList<RecipeResources.Entry<?>>(types.size());
    for (var type : types) {
      entries.add(entry(source, type));
    }
    return new RecipeResources(entries);
  }

  private static <D> RecipeResources.Entry<D> entry(RecipeResources source, RecipeResourceType<D> type) {
    return new RecipeResources.Entry<>(type, source.get(type));
  }

  private static List<ItemAmount> items(List<ItemAmount> rows, Io io, RecipeModifiers modifiers) {
    return rows.stream().map(row -> {
      long amount = modifiers.amount(Target.ITEM, io, row.amount(), 1, Long.MAX_VALUE);
      long minimum = row.maxAmount() == 0 ? 0 : modifiers.amount(Target.ITEM, io, row.minAmount(), 0, Long.MAX_VALUE);
      return row.withAmountsAndChance(
          amount,
          modifiers.chance(Target.ITEM, io, row.chance()),
          Math.min(minimum, amount),
          row.maxAmount() == 0 ? 0 : amount);
    }).toList();
  }

  private static List<FluidRequirement> fluids(List<FluidRequirement> rows, Io io, RecipeModifiers modifiers) {
    return rows.stream()
        .map(
            row -> row.withAmountAndChance(
                (int) modifiers.amount(Target.FLUID, io, row.stack().getAmount(), 1, Integer.MAX_VALUE),
                modifiers.chance(Target.FLUID, io, row.chance())))
        .toList();
  }

  private static List<FluidRequirement> legacyFluid(FluidRecipeData data, RecipeModifiers modifiers) {
    var row = data.perTickInputs().getFirst();
    int amount = (int) modifiers.amount(Target.FLUID, Io.INPUT, row.stack().getAmount(), 0, Integer.MAX_VALUE);
    return amount == 0 ? List.of() : List.of(row.withAmountAndChance(amount, row.chance()));
  }

  private static List<ChemicalRequirement> chemicals(List<ChemicalRequirement> rows, Io io, RecipeModifiers modifiers) {
    return rows.stream()
        .map(
            row -> new ChemicalRequirement(
                row.chemical(),
                modifiers.amount(Target.CHEMICAL, io, row.amount(), 1, Long.MAX_VALUE),
                row.selector(),
                modifiers.chance(Target.CHEMICAL, io, row.chance()),
                row.ignoreOutputCheck()))
        .toList();
  }

  private static List<IngredientArrayData.Group> groups(List<IngredientArrayData.Group> groups, Io io, RecipeModifiers modifiers) {
    return groups.stream().map(group -> {
      var target = Target.parse(group.resource());
      long limit = target == Target.FLUID ? Integer.MAX_VALUE : Long.MAX_VALUE;
      var choices = group.alternatives().stream().map(choice -> {
        long amount = modifiers.amount(target, io, choice.amount(), 1, limit);
        long minimum = choice.minAmount() == choice.amount() ? amount : modifiers
            .amount(target, io, choice.minAmount(), 0, limit);
        double chance = io == Io.INPUT ? modifiers.chance(target, io, group.chance() * choice.chance()) : choice
            .chance();
        return new IngredientArrayData.Choice(
            choice.resource(),
            choice.prototype(),
            choice.chemical(),
            amount,
            chance,
            choice.checker(),
            choice.modifiers(),
            minimum,
            amount,
            choice.match());
      }).toList();
      return new IngredientArrayData.Group(
          choices,
          io == Io.INPUT ? 1 : modifiers.chance(Target.ITEM, io, group.chance()),
          group.parallelUnaffected(),
          group.selector(),
          group.optional(),
          group.tooltips(),
          group.recipeModifiers());
    }).toList();
  }

  private static TriggeredResourceData triggers(TriggeredResourceData data, RecipeModifiers modifiers, int before, int after) {
    return new TriggeredResourceData(data.rows().stream().map(row -> {
      var target = Target.parse(row.resource());
      long amount = modifiers
          .amount(target, row.io(), row.amount(), 1, target == Target.FLUID ? Integer.MAX_VALUE : Long.MAX_VALUE);
      return new TriggeredResourceData.Row(
          row.resource(),
          row.io(),
          RecipeTriggeredResources.scaledTick(row.tick(), before, after),
          row.repeatable(),
          amount,
          modifiers.chance(target, row.io(), row.chance()),
          row.selector(),
          row.prototype(),
          row.chemical(),
          row.parallelUnaffected(),
          row.match());
    }).toList());
  }
}
