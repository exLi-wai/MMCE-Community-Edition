package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.ItemAmount;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.MachineRecipeDeclaration;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.resource.FluidRecipeData;
import com.lw.modularmachine.machine.recipe.resource.ItemRecipeData;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceBuilderDeclarations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.ApiStatus;

/**
 * Isolates mutable component values when capturing declarations and exposing
 * adapter settings.
 */
@ApiStatus.Internal
public final class RecipeDeclarationSnapshots {
  private final HolderLookup.Provider registries;

  private RecipeDeclarationSnapshots(HolderLookup.Provider registries) {
    this.registries = registries;
  }

  static MachineRecipeDeclaration capture(MachineRecipeDeclaration source, HolderLookup.Provider registries) {
    return new RecipeDeclarationSnapshots(registries).capture(source);
  }

  public static IngredientArrayData captureGroups(IngredientArrayData groups, HolderLookup.Provider registries) {
    return new RecipeDeclarationSnapshots(registries).groups(groups);
  }

  public static List<RecipeHolder<?>> adapterSources(List<RecipeHolder<?>> sources, HolderLookup.Provider registries) {
    var snapshots = new RecipeDeclarationSnapshots(registries);
    return List.copyOf(map(sources, snapshots::adapterSource));
  }

  private RecipeHolder<?> adapterSource(RecipeHolder<?> holder) {
    if (!(holder.value() instanceof MachineRecipe recipe)) {
      return holder;
    }
    var view = recipe.withResourceData(
        resources(recipe.resourceData()),
        groups(recipe.ingredientArrays()),
        triggers(recipe.triggeredResources()));
    return view == recipe ? holder : new RecipeHolder<>(holder.id(), view);
  }

  public static RecipeAdapterImport adapterSettings(RecipeAdapterImport settings, HolderLookup.Provider registries) {
    if (settings.customization() == RecipeAdapterCustomization.NONE) {
      return settings;
    }
    return new RecipeAdapterImport(
        settings.id(),
        settings.adapter(),
        settings.recipes(),
        settings.allRecipes(),
        settings.duration(),
        settings.energyPerTick(),
        settings.priority(),
        new RecipeDeclarationSnapshots(registries).customization(settings.customization()),
        settings.prioritySpecified());
  }

  private RecipeAdapterCustomization customization(RecipeAdapterCustomization source) {
    return new RecipeAdapterCustomization(
        source.modifiers(),
        source.interfaceInputs(),
        map(source.fluidInputs(), this::fluid),
        map(source.fluidOutputs(), this::fluid),
        source.fluidPerTick().map(this::fluid),
        source.chemicalInputs(),
        source.chemicalOutputs(),
        source.chemicalPerTick(),
        map(source.itemOutputs(), this::item),
        source.selectors(),
        source.parallelized(),
        source.failureAction(),
        source.energyOutputPerTick(),
        source.threadName(),
        source.maxThreads(),
        source.tooltips(),
        source.itemCallbacks(),
        source.itemParallelism(),
        source.chemicalPerTickOutputs(),
        map(source.fluidPerTickInputs(), this::fluid),
        map(source.fluidPerTickOutputs(), this::fluid),
        additions(source.resourceAdditions()));
  }

  private RecipeAdapterCustomization.ResourceAdditions additions(RecipeAdapterCustomization.ResourceAdditions source) {
    if (source == RecipeAdapterCustomization.ResourceAdditions.NONE) {
      return source;
    }
    var nativeResources = new LinkedHashMap<ResourceLocation, RecipeAdapterCustomization.NativeResource>();
    source.nativeResources().forEach(
        (id, value) -> nativeResources
            .put(id, new RecipeAdapterCustomization.NativeResource(resource(value.entry()), value.replace())));
    return new RecipeAdapterCustomization.ResourceAdditions(
        map(source.inputs(), this::item),
        groups(source.groups()),
        source.payments(),
        map(
            source.triggers(),
            value -> new RecipeAdapterCustomization.Trigger(trigger(value.row()), value.inheritsSelector())),
        source.loadJei(),
        nativeResources);
  }

  private MachineRecipeDeclaration capture(MachineRecipeDeclaration source) {
    var resources = resources(source.resources());
    var groups = groups(source.ingredientArrays());
    var triggers = triggers(source.triggeredResources());
    if (resources == source.resources()
        && groups == source.ingredientArrays()
        && triggers == source.triggeredResources()) {
      return source;
    }
    return new MachineRecipeDeclaration(
        source.machineId(),
        source.priority(),
        source.duration(),
        resources,
        source.failureAction(),
        source.parallelized(),
        source.randomSeed(),
        source.interfaceInputs(),
        source.selectors(),
        source.executionOptions(),
        source.scriptOptions(),
        groups,
        triggers,
        source.modifiers(),
        source.legacyFluidPerTick(),
        source.declaredSelectorFields());
  }

  private RecipeResources resources(RecipeResources source) {
    var entries = map(source.entries(), this::resource);
    return entries == source.entries() ? source : new RecipeResources(entries);
  }

  private TriggeredResourceData triggers(TriggeredResourceData source) {
    var rows = map(source.rows(), this::trigger);
    return rows == source.rows() ? source : new TriggeredResourceData(rows);
  }

  private RecipeResources.Entry<?> resource(RecipeResources.Entry<?> entry) {
    if (entry.type() == RecipeResourceDefinitions.ITEM) {
      var data = (ItemRecipeData) entry.data();
      var inputs = map(data.inputs(), this::item);
      var outputs = map(data.outputs(), this::item);
      if (inputs != data.inputs() || outputs != data.outputs()) {
        return new RecipeResources.Entry<>(
            RecipeResourceDefinitions.ITEM,
            new ItemRecipeData(inputs, outputs, data.payments()));
      }
    } else if (entry.type() == RecipeResourceDefinitions.FLUID) {
      var data = (FluidRecipeData) entry.data();
      var inputs = map(data.inputs(), this::fluid);
      var outputs = map(data.outputs(), this::fluid);
      var tickInputs = map(data.perTickInputs(), this::fluid);
      var tickOutputs = map(data.perTickOutputs(), this::fluid);
      if (inputs != data.inputs()
          || outputs != data.outputs()
          || tickInputs != data.perTickInputs()
          || tickOutputs != data.perTickOutputs()) {
        return new RecipeResources.Entry<>(
            RecipeResourceDefinitions.FLUID,
            new FluidRecipeData(inputs, outputs, tickInputs, tickOutputs));
      }
    }
    if (!RecipeResourceDefinitions.isBuiltin(entry.type())) {
      RecipeResourceDefinitions.requireRegistered(entry.type());
      return captureExtension(entry);
    }
    return entry;
  }

  private <D> RecipeResources.Entry<D> captureExtension(RecipeResources.Entry<D> entry) {
    if (entry.type().isEmpty(entry.data())) {
      return new RecipeResources.Entry<>(entry.type(), entry.type().empty());
    }
    return RecipeResourceBuilderDeclarations.snapshot(entry, registries);
  }

  private ItemAmount item(ItemAmount row) {
    return row.capture(registries);
  }

  private FluidRequirement fluid(FluidRequirement row) {
    return row.capture(registries);
  }

  private IngredientArrayData groups(IngredientArrayData groups) {
    var inputs = map(groups.inputs(), this::group);
    var outputs = map(groups.randomOutputs(), this::group);
    if (inputs == groups.inputs() && outputs == groups.randomOutputs()) {
      return groups;
    }
    return new IngredientArrayData(inputs, outputs);
  }

  private IngredientArrayData.Group group(IngredientArrayData.Group group) {
    var values = map(group.alternatives(), this::choice);
    return values == group.alternatives() ? group : new IngredientArrayData.Group(
        values,
        group.chance(),
        group.parallelUnaffected(),
        group.selector(),
        group.optional(),
        group.tooltips(),
        group.recipeModifiers());
  }

  private IngredientArrayData.Choice choice(IngredientArrayData.Choice choice) {
    return choice.capture(registries);
  }

  private TriggeredResourceData.Row trigger(TriggeredResourceData.Row row) {
    return row.capture(registries);
  }

  private static <T> List<T> map(List<T> source, UnaryOperator<T> detach) {
    List<T> result = null;
    for (int index = 0; index < source.size(); index++) {
      var entry = source.get(index);
      var copy = detach.apply(entry);
      if (copy != entry) {
        if (result == null) {
          result = new ArrayList<>(source);
        }
        result.set(index, copy);
      }
    }
    return result == null ? source : result;
  }
}
