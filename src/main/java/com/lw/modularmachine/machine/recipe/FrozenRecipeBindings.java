package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.CompiledStructure;
import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.Locale;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Frozen logical routing; never resolves a live inventory or changes a paid
 * run.
 */
@ApiStatus.Internal
final class FrozenRecipeBindings {
  private final Map<String, PortKind> logicalPorts;
  private final Map<String, String> logicalSelectors;
  private final int inputGroup;
  private final Map<String, Integer> logicalInputGroups;
  private final Map<String, Integer> dynamicLengths;
  private final PatternRunSources.Binding patternSource;
  private final Set<PortKind> patternInputs;
  private volatile WeakReference<CompiledStructure> verifiedPattern;

  FrozenRecipeBindings(CompoundTag source) {
    if (source.contains("dynamic_lengths") && !source.contains("dynamic_lengths", Tag.TAG_COMPOUND)) {
      throw new IllegalArgumentException("Invalid frozen dynamic lengths");
    }
    var frozenLengths = source.getCompound("dynamic_lengths");
    var lengths = new LinkedHashMap<String, Integer>();
    if (frozenLengths.size() > 64) {
      throw new IllegalArgumentException("Too many frozen dynamic segments");
    }
    for (String id : frozenLengths.getAllKeys()) {
      if (id.isBlank()
          || id.length() > 128
          || !frozenLengths.contains(id, Tag.TAG_INT)
          || frozenLengths.getInt(id) < 0) {
        throw new IllegalArgumentException("Invalid frozen dynamic segment length");
      }
      lengths.put(id, frozenLengths.getInt(id));
    }
    dynamicLengths = Map.copyOf(lengths);
    if (source.contains("input_group") && !source.contains("input_group", Tag.TAG_INT)) {
      throw new IllegalArgumentException("Invalid frozen input group");
    }
    inputGroup = InputGroup.require(source.contains("input_group") ? source.getInt("input_group") : InputGroup.SHARED);
    var patternKinds = EnumSet.noneOf(PortKind.class);
    if (source.contains("pattern_inputs")) {
      if (!source.contains("pattern_inputs", Tag.TAG_COMPOUND)) {
        throw new IllegalArgumentException("Invalid frozen pattern inputs");
      }
      var pattern = source.getCompound("pattern_inputs");
      patternSource = PatternRunSources.Binding.load(pattern);
      if (!(pattern.get("kinds") instanceof ListTag kinds)
          || kinds.isEmpty()
          || kinds.getElementType() != Tag.TAG_STRING
          || kinds.size() > 3) {
        throw new IllegalArgumentException("Invalid frozen pattern input roles");
      }
      for (var value : kinds) {
        var kind = PortKind.valueOf(value.getAsString());
        if (kind != PortKind.ITEM_INPUT && kind != PortKind.FLUID_INPUT && kind != PortKind.CHEMICAL_INPUT
            || !patternKinds.add(kind)) {
          throw new IllegalArgumentException("Invalid or duplicate frozen pattern input role");
        }
      }
    } else {
      patternSource = null;
    }
    patternInputs = Set.copyOf(patternKinds);
    var ports = source.getList("ports", Tag.TAG_COMPOUND);
    var mapped = new LinkedHashMap<String, PortKind>();
    var mappedSelectors = new LinkedHashMap<String, String>();
    var mappedGroups = new LinkedHashMap<String, Integer>();
    if (ports.isEmpty() && patternInputs.isEmpty() || ports.size() > 4096) {
      throw new IllegalArgumentException("Invalid run port map");
    }
    for (int i = 0; i < ports.size(); i++) {
      var port = ports.getCompound(i);
      String id = port.getString("id");
      if (!Set.of("id", "kind", "selector", "input_group").containsAll(port.getAllKeys())
          || !port.contains("id", Tag.TAG_STRING)
          || !port.contains("kind", Tag.TAG_STRING)
          || port.contains("selector") && !port.contains("selector", Tag.TAG_STRING)
          || port.contains("input_group") && !port.contains("input_group", Tag.TAG_INT)
          || id.isBlank()
          || id.length() > 128
          || mapped.put(id, PortKind.valueOf(port.getString("kind"))) != null) {
        throw new IllegalArgumentException("Invalid or duplicate run port identity");
      }
      mappedSelectors.put(id, ResourceSelector.require(port.getString("selector")));
      int group = InputGroup.require(port.contains("input_group") ? port.getInt("input_group") : InputGroup.SHARED);
      var kind = mapped.get(id);
      if (group != InputGroup.SHARED
          && kind != PortKind.ITEM_INPUT
          && kind != PortKind.FLUID_INPUT
          && kind != PortKind.CHEMICAL_INPUT) {
        throw new IllegalArgumentException("Non-groupable frozen port");
      }
      mappedGroups.put(id, group);
    }
    logicalPorts = Map.copyOf(mapped);
    logicalSelectors = Map.copyOf(mappedSelectors);
    logicalInputGroups = Map.copyOf(mappedGroups);
    if (inputGroup > 0 && !mappedGroups.containsValue(inputGroup)) {
      throw new IllegalArgumentException("Frozen input group has no mapped input");
    }
  }

  Map<String, PortKind> logicalPorts() {
    return logicalPorts;
  }

  int inputGroup() {
    return inputGroup;
  }

  boolean matchesInputGroups(RecipeExecutionTicket ticket) {
    return ticket.inputGroupsMatch(logicalInputGroups);
  }

  void validateResources(JsonObject declaration, RecipeSelectors selectors, ChemicalRecipeData chemicals) {
    var available = availableSelectors();
    if (declaration.has("triggered_resources")) {
      for (var value : declaration.getAsJsonArray("triggered_resources")) {
        var row = value.getAsJsonObject();
        requirePort(
            available,
            PortKind.valueOf(
                row.get("resource").getAsString().toUpperCase(Locale.ROOT) + "_" + row.get("io").getAsString()
                    .toUpperCase(Locale.ROOT)),
            row.has("selector") ? row.get("selector").getAsString() : "");
      }
    }
    if (!declaration.getAsJsonArray("inputs").isEmpty() && !available.containsKey(PortKind.ITEM_INPUT)
        || hasRequiredOutputs(declaration, "outputs") && !available.containsKey(PortKind.ITEM_OUTPUT)) {
      throw new IllegalArgumentException("Item requirements lack logical item ports");
    }
    chemicals.inputs().forEach(value -> requirePort(available, PortKind.CHEMICAL_INPUT, value.selector()));
    chemicals.perTick().forEach(value -> requirePort(available, PortKind.CHEMICAL_INPUT, value.selector()));
    chemicals.outputs().stream().filter(value -> !value.ignoreOutputCheck())
        .forEach(value -> requirePort(available, PortKind.CHEMICAL_OUTPUT, value.selector()));
    chemicals.perTickOutputs().forEach(value -> requirePort(available, PortKind.CHEMICAL_OUTPUT, value.selector()));
    requireFluidPorts(available, declaration, "fluid_inputs", PortKind.FLUID_INPUT, selectors.fluidInput());
    requireFluidPorts(available, declaration, "fluid_outputs", PortKind.FLUID_OUTPUT, selectors.fluidOutput());
    if (declaration.has("fluid_per_tick")) {
      requirePort(available, PortKind.FLUID_INPUT, selectors.fluidPerTick());
    }
    requireFluidPorts(available, declaration, "fluid_per_tick_inputs", PortKind.FLUID_INPUT, selectors.fluidPerTick());
    requireFluidPorts(available, declaration, "fluid_per_tick_outputs", PortKind.FLUID_OUTPUT, "");
    if (declaration.has("energy_per_tick") && declaration.get("energy_per_tick").getAsLong() > 0) {
      requirePort(available, PortKind.ENERGY_INPUT, selectors.energyInput());
    }
    if (declaration.has("energy_output_per_tick") && declaration.get("energy_output_per_tick").getAsLong() > 0) {
      requirePort(available, PortKind.ENERGY_OUTPUT, selectors.energyOutput());
    }
  }

  private Map<PortKind, Set<String>> availableSelectors() {
    var available = new EnumMap<PortKind, Set<String>>(PortKind.class);
    logicalPorts.forEach((id, kind) -> {
      // Item presence checks accept any group; routed requirements use only eligible
      // selectors.
      var selectors = available.computeIfAbsent(kind, ignored -> new HashSet<>());
      int group = logicalInputGroups.get(id);
      if (!kind.isInput() || group == InputGroup.SHARED || group == inputGroup) {
        selectors.add(logicalSelectors.get(id));
      }
    });
    for (var kind : patternInputs) {
      available.computeIfAbsent(kind, ignored -> new HashSet<>()).add(patternSource.selector());
    }
    return available;
  }

  private static boolean hasRequiredOutputs(JsonObject declaration, String field) {
    if (!declaration.has(field)) {
      return false;
    }
    for (var value : declaration.getAsJsonArray(field)) {
      var row = value.getAsJsonObject();
      if (!row.has("ignore_output_check") || !row.get("ignore_output_check").getAsBoolean()) {
        return true;
      }
    }

    return false;
  }

  private static void requireFluidPorts(Map<PortKind, Set<String>> available, JsonObject declaration, String field, PortKind kind, String defaultSelector) {
    if (declaration.has(field)) {
      for (var value : declaration.getAsJsonArray(field)) {
        var row = value.getAsJsonObject();
        if (row.has("ignore_output_check") && row.get("ignore_output_check").getAsBoolean()) {
          continue;
        }
        requirePort(available, kind, row.has("selector") ? row.get("selector").getAsString() : defaultSelector);
      }
    }
  }

  private static void requirePort(Map<PortKind, Set<String>> available, PortKind kind, String selector) {
    if (!available.getOrDefault(kind, Set.of()).contains(selector)) {
      throw new IllegalArgumentException("Frozen recipe lacks a matching " + kind.path() + " port");
    }
  }

  boolean matchesDefinition(MachineDefinition definition, @Nullable List<Integer> recipePartIndices) {
    if (!dynamicLengths.equals(definition.dynamicLengths())) {
      return false;
    }
    var parts = definition.parts();
    int size = recipePartIndices == null ? parts.size() : recipePartIndices.size();
    int ports = 0;
    for (int i = 0; i < size; i++) {
      var part = parts.get(recipePartIndices == null ? i : recipePartIndices.get(i));
      var kind = part.rule().port();
      if (kind == null) {
        continue;
      }
      if (logicalPorts.get(part.id()) != kind || !part.selector().equals(logicalSelectors.get(part.id()))) {
        return false;
      }
      ports++;
    }
    return ports == logicalPorts.size();
  }

  boolean matchesPattern(CompiledStructure pattern) {
    var previous = verifiedPattern;
    if (previous != null && previous.get() == pattern) {
      return true;
    }
    if (!matchesDefinition(pattern.definition, pattern.recipePartIndices())) {
      return false;
    }
    verifiedPattern = new WeakReference<>(pattern);
    return true;
  }

  boolean matchesPatternSource(PatternRunSources.Binding binding) {
    return Objects.equals(patternSource, binding);
  }

  boolean sameLayout(FrozenRecipeBindings other) {
    return dynamicLengths.equals(other.dynamicLengths)
        && logicalPorts.equals(other.logicalPorts)
        && logicalSelectors.equals(other.logicalSelectors)
        && Objects.equals(patternSource, other.patternSource)
        && patternInputs.equals(other.patternInputs);
  }
}
