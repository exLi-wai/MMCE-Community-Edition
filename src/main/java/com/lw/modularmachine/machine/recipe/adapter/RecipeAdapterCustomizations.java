package com.lw.modularmachine.machine.recipe.adapter;

import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.ItemPaymentData;
import com.lw.modularmachine.api.recipe.MachineRecipeDeclaration;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.TriggeredResourceData;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization.Phase;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.api.recipe.ItemAmount;
import com.lw.modularmachine.machine.recipe.resource.EnergyRecipeData;
import com.lw.modularmachine.machine.recipe.resource.FluidRecipeData;
import com.lw.modularmachine.machine.recipe.resource.ItemRecipeData;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Applies machine-local customizations to original, unscaled resource
 * declarations.
 */
@ApiStatus.Internal
final class RecipeAdapterCustomizations {
  private final RecipeAdapterCustomization settings;
  RecipeAdapterCustomizations(RecipeAdapterCustomization settings) {
    this.settings = settings;
  }

  private static TriggeredResourceData.Row resolve(RecipeAdapterCustomization.Trigger trigger, RecipeSelectors selectors) {
    var row = trigger.row();
    if (!trigger.inheritsSelector()) {
      return row;
    }
    var selector = row.io() == RecipeModifier.Io.INPUT ? selectors.fluidInput() : selectors.fluidOutput();
    return new TriggeredResourceData.Row(
        row.resource(),
        row.io(),
        row.tick(),
        row.repeatable(),
        row.amount(),
        row.chance(),
        selector,
        row.prototype(),
        row.chemical(),
        row.parallelUnaffected(),
        row.match());
  }

  MachineRecipeDeclaration apply(MachineRecipeDeclaration source, ResourceLocation machine, int priority, int duration, long addedEnergy) {
    if (settings.equals(RecipeAdapterCustomization.NONE)
        && source.machineId().equals(machine)
        && priority == source.priority()
        && duration == source.duration()
        && addedEnergy == 0) {
      return source;
    }
    var extra = settings.resourceAdditions();
    var original = source.resources();
    var sourceItems = original.get(RecipeResourceDefinitions.ITEM);
    var inputs = append(sourceItems.inputs(), extra.inputs());
    var outputs = new ArrayList<>(sourceItems.outputs());
    for (var edit : settings.itemParallelism()) {
      var rows = edit.io() == RecipeModifier.Io.INPUT ? inputs : outputs;
      if (edit.row() >= rows.size()) {
        throw new IllegalArgumentException("Adapter parallel flag refers to a missing source item row");
      }
      var row = rows.get(edit.row());
      rows.set(edit.row(), row.withParallelUnaffected(edit.unaffected()));
    }
    outputs.addAll(settings.itemOutputs());
    var tools = new ArrayList<>(sourceItems.payments().tools());
    extra.payments().tools().forEach(
        tool -> tools
            .add(new ItemPaymentData.Tool(Math.addExact(sourceItems.inputs().size(), tool.row()), tool.durability())));
    var inputGroups = append(source.ingredientArrays().inputs(), extra.groups().inputs());
    var callbacks = itemCallbacks(source.scriptOptions(), sourceItems.outputs().size());
    if (!extra.inputs().isEmpty()
        && inputs.size() > 1
        && inputs.getFirst().ignoresComponents()
        && tools.stream().noneMatch(tool -> tool.row() == 0)) {
      convertItemOnlyInput(inputs, inputGroups, callbacks, tools);
    }
    var items = new ItemRecipeData(
        inputs,
        outputs,
        new ItemPaymentData(tools, append(sourceItems.payments().fuels(), extra.payments().fuels())));
    var ingredients = new IngredientArrayData(
        inputGroups,
        append(source.ingredientArrays().randomOutputs(), extra.groups().randomOutputs()));
    var previousScript = source.scriptOptions();
    var script = new RecipeScriptOptions(
        append(previousScript.tooltips(), settings.tooltips()),
        callbacks,
        extra.loadJei().orElse(previousScript.loadJei()));
    script.validateRows(inputs.size(), outputs.size());

    var selectors = selectors(source);
    var phases = new HashSet<>(source.declaredSelectorFields());
    settings.selectors().keySet().forEach(phase -> phases.add(phase.field()));
    var triggered = new ArrayList<>(source.triggeredResources().rows());
    extra.triggers().forEach(trigger -> triggered.add(resolve(trigger, selectors)));
    var fluid = original.get(RecipeResourceDefinitions.FLUID);
    var effectiveTicks = new ArrayList<>(fluid.perTickInputs());
    boolean legacy = source.legacyFluidPerTick();
    if (settings.fluidPerTick().isPresent()) {
      var addition = settings.fluidPerTick().orElseThrow();
      if (addition.chance() != 1
          || addition.parallelUnaffected()
          || addition.selectorSpecified()
          || addition.ignoreOutputCheck()) {
        throw new IllegalArgumentException("Legacy per-tick fluid must use an unmodified fluid stack");
      }
      if (legacy) {
        var previous = effectiveTicks.getFirst().stack();
        var next = addition.stack();
        if (!FluidStack.isSameFluidSameComponents(previous, next)) {
          throw new IllegalArgumentException("Adapter cannot add a different fluid-per-tick resource");
        }
        next.setAmount(Math.addExact(previous.getAmount(), next.getAmount()));
        effectiveTicks.set(0, new FluidRequirement(next, 1));
      } else {
        if (!effectiveTicks.isEmpty() && settings.fluidPerTickInputs().isEmpty()) {
          throw new IllegalArgumentException("Use either fluid_per_tick or fluid_per_tick_inputs");
        }
        effectiveTicks.add(addition);
        legacy = true;
      }
    }
    effectiveTicks.addAll(settings.fluidPerTickInputs());
    legacy &= settings.fluidPerTickInputs().isEmpty();
    var liquids = new FluidRecipeData(
        append(fluid.inputs(), settings.fluidInputs()),
        append(fluid.outputs(), settings.fluidOutputs()),
        effectiveTicks,
        append(fluid.perTickOutputs(), settings.fluidPerTickOutputs()));
    var chemical = original.get(RecipeResourceDefinitions.CHEMICAL);
    var chemicals = new ChemicalRecipeData(
        append(chemical.inputs(), settings.chemicalInputs()),
        append(chemical.outputs(), settings.chemicalOutputs()),
        append(chemical.perTick(), settings.chemicalPerTick()),
        append(chemical.perTickOutputs(), settings.chemicalPerTickOutputs()));
    var energy = original.get(RecipeResourceDefinitions.ENERGY);
    var costs = new EnergyRecipeData(
        Math.addExact(energy.inputPerTick(), addedEnergy),
        Math.addExact(energy.outputPerTick(), settings.energyOutputPerTick()));
    var entries = new LinkedHashMap<ResourceLocation, RecipeResources.Entry<?>>();
    original.entries().forEach(entry -> entries.put(entry.type().id(), entry));
    for (var entry : List.of(
        new RecipeResources.Entry<>(RecipeResourceDefinitions.ITEM, items),
        new RecipeResources.Entry<>(RecipeResourceDefinitions.FLUID, liquids),
        new RecipeResources.Entry<>(RecipeResourceDefinitions.CHEMICAL, chemicals),
        new RecipeResources.Entry<>(RecipeResourceDefinitions.ENERGY, costs))) {
      entries.put(entry.type().id(), entry);
    }
    for (var addition : extra.nativeResources().values()) {
      var entry = RecipeAdapterNativeResources.apply(original, addition);
      entries.put(entry.type().id(), entry);
    }
    var execution = source.executionOptions();
    return new MachineRecipeDeclaration(
        machine,
        priority,
        duration,
        new RecipeResources(List.copyOf(entries.values())),
        settings.failureAction().or(source::failureAction),
        settings.parallelized().or(source::parallelized),
        source.randomSeed(),
        append(source.interfaceInputs(), settings.interfaceInputs()),
        selectors,
        new RecipeExecutionOptions(
            settings.threadName().orElse(execution.threadName()),
            settings.maxThreads().orElse(execution.maxThreads())),
        script,
        ingredients,
        new TriggeredResourceData(triggered),
        append(source.modifiers(), settings.modifiers()),
        legacy,
        phases);
  }

  private RecipeSelectors selectors(MachineRecipeDeclaration source) {
    var previous = source.selectors();
    var values = new EnumMap<Phase, String>(Phase.class);
    values.put(Phase.FLUID_INPUT, previous.fluidInput());
    values.put(Phase.FLUID_OUTPUT, previous.fluidOutput());
    values.put(Phase.FLUID_PER_TICK, previous.fluidPerTick());
    values.put(Phase.ENERGY_INPUT, previous.energyInput());
    values.put(Phase.ENERGY_OUTPUT, previous.energyOutput());
    settings.selectors().forEach((phase, next) -> {
      if (source.declaredSelectorFields().contains(phase.field()) && !values.get(phase).equals(next)) {
        throw new IllegalArgumentException("Adapter customization conflicts with the source phase selector");
      }
      values.put(phase, next);
    });
    return new RecipeSelectors(
        values.get(Phase.FLUID_INPUT),
        values.get(Phase.FLUID_OUTPUT),
        values.get(Phase.ENERGY_INPUT),
        values.get(Phase.FLUID_PER_TICK),
        values.get(Phase.ENERGY_OUTPUT));
  }

  private List<RecipeScriptOptions.ItemCallbacks> itemCallbacks(RecipeScriptOptions source, int originalOutputRows) {
    record Row(RecipeModifier.Io io, int index) {
    }
    var bindings = new LinkedHashMap<Row, RecipeScriptOptions.ItemCallbacks>();
    source.itemCallbacks().forEach(binding -> bindings.put(new Row(binding.io(), binding.row()), binding));
    for (var addition : settings.itemCallbacks()) {
      var callback = addition.callback();
      int index = addition.addedOutput() ? Math.addExact(originalOutputRows, callback.row()) : callback.row();
      var key = new Row(callback.io(), index);
      var previous = bindings.get(key);
      var checker = callback.checker();
      var modifiers = callback.modifiers();
      if (previous != null) {
        if (previous.checker().isPresent() && checker.isPresent() && !previous.checker().equals(checker)) {
          throw new IllegalArgumentException("Adapter customization conflicts with a source item checker");
        }
        checker = checker.or(previous::checker);
        modifiers = append(previous.modifiers(), modifiers);
      }
      bindings.put(key, new RecipeScriptOptions.ItemCallbacks(callback.io(), index, checker, modifiers));
    }
    return new ArrayList<>(bindings.values());
  }

  private static void convertItemOnlyInput(List<ItemAmount> inputs, List<IngredientArrayData.Group> groups, List<RecipeScriptOptions.ItemCallbacks> callbacks, List<ItemPaymentData.Tool> tools) {
    var row = inputs.removeFirst();
    Optional<ResourceLocation> checker = Optional.empty();
    List<ResourceLocation> modifiers = List.of();
    for (var it = callbacks.listIterator(); it.hasNext();) {
      var callback = it.next();
      if (callback.io() != RecipeModifier.Io.INPUT) {
        continue;
      }
      if (callback.row() == 0) {
        checker = callback.checker();
        modifiers = callback.modifiers();
        it.remove();
      } else {
        it.set(
            new RecipeScriptOptions.ItemCallbacks(
                callback.io(),
                callback.row() - 1,
                callback.checker(),
                callback.modifiers()));
      }
    }
    tools.replaceAll(tool -> new ItemPaymentData.Tool(tool.row() - 1, tool.durability()));
    var choice = new IngredientArrayData.Choice(
        "item",
        Optional.of(row.ingredientStack()),
        Optional.empty(),
        Optional.empty(),
        row.amount(),
        row.chance(),
        checker,
        modifiers,
        row.minAmount(),
        row.maxAmount(),
        row.match());
    groups.add(new IngredientArrayData.Group(List.of(choice), 1, row.parallelUnaffected(), row.selector()));
  }

  private static <T> ArrayList<T> append(List<T> original, List<T> additions) {
    var result = new ArrayList<>(original);
    result.addAll(additions);
    return result;
  }
}
