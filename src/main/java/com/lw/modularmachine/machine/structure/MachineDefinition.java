package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.block.SmartInterfaceBlock;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.appearance.MachineComponentType;
import com.lw.modularmachine.machine.factory.FactoryThreadPreset;
import com.lw.modularmachine.machine.factory.FactoryThreadStateCodec;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.orientation.OrientationPolicy;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterDefinitions;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.smart.SmartInterfaceAssignment;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;

/**
 * Immutable machine declarations and resolved dynamic layouts. The declared
 * hasFactory flag affects startup registrations; reload does not revoke
 * registered identities or paid recipe runs.
 */
public record MachineDefinition(
    ResourceLocation id,
    String translationKey,
    List<Part> parts,
    long energyPerTick,
    OrientationPolicy orientationPolicy,
    OrientationCompilation orientationCompilation,
    FactoryThreadPreset factoryPreset,
    List<RecipeModifier> recipeModifiers,
    List<StructureModifierDefinition> structureModifiers,
    Map<String, SmartInterfaceType> interfaceTypes,
    int internalParallelism,
    int maxParallelism,
    boolean requiresBlueprint,
    boolean factoryOnly,
    int color,
    boolean hideComponentsWhenFormed,
    AABB controllerBoundingBox,
    List<DynamicPatternDefinition> dynamicPatterns,
    Map<String, Integer> dynamicLengths,
    MachineDefinition source,
    Set<MachineComponentType> hiddenComponentTypes,
    boolean hasFactory,
    boolean parallelizable,
    int maximumFactoryThreads,
    List<RecipeAdapterImport> recipeAdapters,
    String prefix,
    RecipeFailureAction failureAction) {

  public static final int MAX_PARTS = Integer.MAX_VALUE;

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes, int internalParallelism, int maxParallelism, boolean requiresBlueprint, boolean factoryOnly, int color, boolean hideComponentsWhenFormed, AABB controllerBoundingBox, List<DynamicPatternDefinition> dynamicPatterns, Map<String, Integer> dynamicLengths, MachineDefinition source, Set<MachineComponentType> hiddenComponentTypes, boolean hasFactory, boolean parallelizable, int maximumFactoryThreads, List<RecipeAdapterImport> recipeAdapters) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, internalParallelism, maxParallelism, requiresBlueprint,
        factoryOnly, color, hideComponentsWhenFormed, controllerBoundingBox, dynamicPatterns, dynamicLengths, source,
        hiddenComponentTypes, hasFactory, parallelizable, maximumFactoryThreads, recipeAdapters, "", null);
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes, int internalParallelism, int maxParallelism, boolean requiresBlueprint, boolean factoryOnly, int color, boolean hideComponentsWhenFormed, AABB controllerBoundingBox, List<DynamicPatternDefinition> dynamicPatterns, Map<String, Integer> dynamicLengths, MachineDefinition source, Set<MachineComponentType> hiddenComponentTypes, boolean hasFactory, boolean parallelizable, int maximumFactoryThreads) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, internalParallelism, maxParallelism, requiresBlueprint,
        factoryOnly, color, hideComponentsWhenFormed, controllerBoundingBox, dynamicPatterns, dynamicLengths, source,
        hiddenComponentTypes, hasFactory, parallelizable, maximumFactoryThreads, List.of());
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes, int internalParallelism, int maxParallelism, boolean requiresBlueprint, boolean factoryOnly, int color, boolean hideComponentsWhenFormed, AABB controllerBoundingBox, List<DynamicPatternDefinition> dynamicPatterns, Map<String, Integer> dynamicLengths, MachineDefinition source, Set<MachineComponentType> hiddenComponentTypes, boolean hasFactory) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, internalParallelism, maxParallelism, requiresBlueprint,
        factoryOnly, color, hideComponentsWhenFormed, controllerBoundingBox, dynamicPatterns, dynamicLengths, source,
        hiddenComponentTypes, hasFactory, MachineConfig.machineParallelDefault(),
        MachineConfig.defaultFactoryMaxThreads());
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes, int internalParallelism, int maxParallelism, boolean requiresBlueprint, boolean factoryOnly, int color, boolean hideComponentsWhenFormed, AABB controllerBoundingBox, List<DynamicPatternDefinition> dynamicPatterns, Map<String, Integer> dynamicLengths, MachineDefinition source, Set<MachineComponentType> hiddenComponentTypes) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, internalParallelism, maxParallelism, requiresBlueprint,
        factoryOnly, color, hideComponentsWhenFormed, controllerBoundingBox, dynamicPatterns, dynamicLengths, source,
        hiddenComponentTypes, false);
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes, int internalParallelism, int maxParallelism, boolean requiresBlueprint, boolean factoryOnly, int color, boolean hideComponentsWhenFormed, AABB controllerBoundingBox, List<DynamicPatternDefinition> dynamicPatterns, Map<String, Integer> dynamicLengths, MachineDefinition source) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, internalParallelism, maxParallelism, requiresBlueprint,
        factoryOnly, color, hideComponentsWhenFormed, controllerBoundingBox, dynamicPatterns, dynamicLengths, source,
        EnumSet.allOf(MachineComponentType.class));
  }

  public boolean hidesComponent(MachineComponentType type) {
    return hideComponentsWhenFormed && type != null && hiddenComponentTypes.contains(type);
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes, int internalParallelism, int maxParallelism, boolean requiresBlueprint, boolean factoryOnly, int color, boolean hideComponentsWhenFormed, AABB controllerBoundingBox) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, internalParallelism, maxParallelism, requiresBlueprint,
        factoryOnly, color, hideComponentsWhenFormed, controllerBoundingBox, List.of(), Map.of(), null);
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes, int internalParallelism, int maxParallelism) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, internalParallelism, maxParallelism, false, false,
        MachineConfig.defaultCasingColor(), false, new AABB(0, 0, 0, 1, 1, 1));
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, 0, MachineConfig.defaultMaxMachineParallelism());
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, Map.of());
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, List.of());
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation,
        FactoryThreadPreset.defaults(), List.of());
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset, List.of());
  }

  public MachineDefinition {
    if (prefix == null || prefix.length() > 512)
      throw new IllegalArgumentException("Machine prefix exceeds 512 characters");
    recipeAdapters = List.copyOf(recipeAdapters);
    RecipeAdapterDefinitions.validate(recipeAdapters);
    hiddenComponentTypes = Set.copyOf(hiddenComponentTypes);
    if ((color & ~0xFFFFFF) != 0) {
      throw new IllegalArgumentException("Machine color must be an RGB value");
    }
    Objects.requireNonNull(controllerBoundingBox, "controllerBoundingBox");
    if (!validBox(controllerBoundingBox)) {
      throw new IllegalArgumentException("Controller bounding box must be finite and inside one block");
    }
    if (internalParallelism < 0 || maxParallelism < 1 || internalParallelism > maxParallelism) {
      throw new IllegalArgumentException("Invalid internal or maximum machine parallelism");
    }
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(translationKey, "translationKey");
    Objects.requireNonNull(orientationPolicy, "orientationPolicy");
    Objects.requireNonNull(orientationCompilation, "orientationCompilation");
    Objects.requireNonNull(factoryPreset, "factoryPreset");
    if (maximumFactoryThreads < 0
        || maximumFactoryThreads > FactoryThreadStateCodec.MAX_THREADS
        || factoryPreset.threads().size() > maximumFactoryThreads) {
      throw new IllegalArgumentException("Factory thread preset exceeds the machine business thread limit");
    }
    recipeModifiers = List.copyOf(recipeModifiers);
    structureModifiers = List.copyOf(structureModifiers);
    dynamicPatterns = List.copyOf(dynamicPatterns);
    dynamicLengths = Map.copyOf(dynamicLengths);
    if (source != null && (source.source() != null || !dynamicPatterns.isEmpty())) {
      throw new IllegalArgumentException("Dynamic layout source must be an original declaration");
    }
    new RecipeModifiers(recipeModifiers);
    parts = List.copyOf(parts);
    interfaceTypes = Map.copyOf(interfaceTypes);
    if (interfaceTypes.size() > SmartInterfaceType.MAX_TYPES) {
      throw new IllegalArgumentException("Too many smart interface types");
    }
    if (interfaceTypes.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue().id()))) {
      throw new IllegalArgumentException("Smart interface type key does not match its ID");
    }
    var assignedInterfaces = new HashSet<String>();
    var declaredParts = new ArrayList<>(parts);
    var dynamicIds = new HashSet<String>();
    long maximumParts = parts.size();
    for (var pattern : dynamicPatterns) {
      if (!dynamicIds.add(pattern.id())) {
        throw new IllegalArgumentException("Duplicate dynamic pattern ID");
      }
      maximumParts += (long) pattern.start().size() + (long) pattern.maximum() * pattern.body().size() + pattern.end()
          .size();
      declaredParts.addAll(pattern.declarationParts());
      if (pattern.maximum() > 1
          && pattern.body().stream().anyMatch(part -> !part.interfaceType().isEmpty() && !part.automaticInterface())) {
        throw new IllegalArgumentException("Repeated smart interfaces must use automatic interface roles");
      }
    }
    if (!dynamicPatterns.isEmpty() && maximumParts > DynamicPatternDefinition.MAX_EXPANDED_PARTS) {
      throw new IllegalArgumentException("Machine exceeds 4194304 expanded parts");
    }
    for (var part : declaredParts) {
      if (!part.selector().isEmpty() && part.rule().port() == null) {
        throw new IllegalArgumentException("Resource selectors require typed resource ports");
      }
      if (!part.interfaceType().isEmpty()
          && (part.rule().port() != null
              || part.field() != null
              || interfaceTypes.isEmpty()
              || !part.automaticInterface()
                  && (!interfaceTypes.containsKey(part.interfaceType())
                      || !assignedInterfaces.add(part.interfaceType()))
              || part.rule().alternatives().isEmpty()
              || part.rule().alternatives().stream()
                  .anyMatch(alternative -> !(alternative.block() instanceof SmartInterfaceBlock)))) {
        throw new IllegalArgumentException(
            "Smart interface roles require declared types and dedicated interface parts");
      }
    }
    if (structureModifiers.size() > StructureModifierDefinition.MAX_GROUPS) {
      throw new IllegalArgumentException("Invalid structure modifier count or machine schema");
    }
    var modifierIds = new HashSet<String>();
    var structuralRules = new ArrayList<RecipeModifier>();
    int conditionCount = 0;
    for (var modifier : structureModifiers) {
      if (!modifierIds.add(modifier.id())) {
        throw new IllegalArgumentException("Duplicate structure modifier ID");
      }
      if (structuralRules.size() + modifier.modifiers().size() > RecipeModifiers.MAX_RULES) {
        throw new IllegalArgumentException("Too many declared structure modifier rules");
      }
      structuralRules.addAll(modifier.modifiers());
      conditionCount = Math.addExact(conditionCount, modifier.conditions().size());
      for (int index = 0; index < modifier.partIndices().size(); index++) {
        int partIndex = modifier.partIndices().get(index);
        if (partIndex >= parts.size()) {
          throw new IllegalArgumentException("Structure modifier refers to missing part");
        }
        var original = parts.get(partIndex);
        var condition = modifier.conditions().get(index);
        if (original.rule().port() != null
            || !original.interfaceType().isEmpty()
            || original.rule().alternatives().stream()
                .anyMatch(alternative -> alternative.block() instanceof ParallelControllerBlock)
            || condition.rule().alternatives().stream()
                .anyMatch(alternative -> alternative.block() instanceof ParallelControllerBlock)
            || original.field() != null
            || !original.id().equals(condition.id())
            || !original.offset().equals(condition.offset())) {
          throw new IllegalArgumentException(
              "Structure modifier cannot replace a resource/field part or move its dependency");
        }
      }
    }
    if (conditionCount > StructureModifierDefinition.MAX_CONDITIONS) {
      throw new IllegalArgumentException("Too many structure modifier conditions");
    }
    new RecipeModifiers(structuralRules);
    if (parts.isEmpty() || energyPerTick < 0) {
      throw new IllegalArgumentException("Invalid fixed definition");
    }
  }

  public boolean allows(MachineOrientation orientation) {
    return orientationCompilation.allows(orientation);
  }

  public int allowedOrientationMask() {
    return orientationCompilation.allowedMask();
  }

  public List<Rule> rulesFor(MachineOrientation orientation) {
    return orientationCompilation.rulesFor(orientation);
  }

  public MachineDefinition sourceDefinition() {
    return source == null ? this : source;
  }

  public boolean hasDynamicPatterns() {
    return !dynamicPatterns.isEmpty();
  }

  /** All declaration templates, without materializing their maximum length. */
  public List<Part> declarationParts() {
    var original = sourceDefinition();
    if (!original.hasDynamicPatterns()) {
      return original.parts();
    }
    var result = new ArrayList<>(original.parts());
    for (var pattern : original.dynamicPatterns()) {
      result.addAll(pattern.declarationParts());
    }
    return List.copyOf(result);
  }

  /**
   * -1 selects each minimum; other values are independently clamped per segment.
   */
  public Map<String, Integer> defaultDynamicLengths(int requestedSize) {
    if (requestedSize < -1) {
      throw new IllegalArgumentException("Dynamic size must be -1 or nonnegative");
    }
    var result = new LinkedHashMap<String, Integer>();
    for (var pattern : sourceDefinition().dynamicPatterns()) {
      result.put(
          pattern.id(),
          requestedSize == -1 ? pattern.minimum() : Math.clamp(requestedSize, pattern.minimum(), pattern.maximum()));
    }
    return Map.copyOf(result);
  }

  /** Pure expansion; this immutable layout is not a structure proof. */
  public MachineDefinition resolveDynamic(Map<String, Integer> lengths) {
    var original = sourceDefinition();
    if (original != this) {
      return original.resolveDynamic(lengths);
    }
    Objects.requireNonNull(lengths, "lengths");
    if (lengths.size() != dynamicPatterns.size()) {
      throw new IllegalArgumentException("Dynamic selection must specify every declared pattern");
    }
    if (dynamicPatterns.isEmpty()) {
      return this;
    }
    var resolved = new ArrayList<>(parts);
    var positions = new HashSet<BlockPos>();
    var ids = new HashSet<String>();
    parts.forEach(part -> {
      positions.add(part.offset());
      ids.add(part.id());
    });
    for (var pattern : dynamicPatterns) {
      Integer size = lengths.get(pattern.id());
      if (size == null || size < pattern.minimum() || size > pattern.maximum()) {
        throw new IllegalArgumentException("Dynamic size outside declared range: " + pattern.id());
      }
      appendDynamic(resolved, positions, ids, pattern, pattern.start(), "start", 0);
      for (int repetition = 0; repetition < size; repetition++) {
        if ((repetition & 4095) == 0 && Thread.currentThread().isInterrupted()) {
          throw new CancellationException();
        }
        appendDynamic(resolved, positions, ids, pattern, pattern.body(), "body", repetition);
      }
      appendDynamic(resolved, positions, ids, pattern, pattern.end(), "end", size);
    }
    return new MachineDefinition(
        id,
        translationKey,
        resolved,
        energyPerTick,
        orientationPolicy,
        orientationCompilation.expanded(resolved),
        factoryPreset,
        recipeModifiers,
        structureModifiers,
        interfaceTypes,
        internalParallelism,
        maxParallelism,
        requiresBlueprint,
        factoryOnly,
        color,
        hideComponentsWhenFormed,
        controllerBoundingBox,
        List.of(),
        lengths,
        this,
        hiddenComponentTypes,
        hasFactory,
        parallelizable,
        maximumFactoryThreads,
        recipeAdapters,
        prefix,
        failureAction);
  }

  private static void appendDynamic(List<Part> target, Set<BlockPos> positions, Set<String> ids, DynamicPatternDefinition pattern, List<Part> template, String group, int repetition) {
    for (int index = 0; index < template.size(); index++) {
      var part = template.get(index);
      var offset = DynamicPatternDefinition
          .translated(part.offset(), pattern.startOffset(), pattern.step(), repetition);
      String prefix = "dynamic:" + pattern.id() + ":" + group + ":" + repetition + ":";
      String localId = part.id();
      if (prefix.length() + localId.length() > 128) {
        localId = UUID.nameUUIDFromBytes(localId.getBytes(StandardCharsets.UTF_8)).toString();
      }
      String id = prefix + localId;
      if (offset.equals(BlockPos.ZERO) || !positions.add(offset) || !ids.add(id)) {
        throw new IllegalArgumentException("Dynamic layout overlaps a part/controller: " + id + " at " + offset);
      }
      target.add(
          new Part(
              id,
              offset,
              part.rule(),
              part.field(),
              part.propertiesSpace(),
              part.selector(),
              part.interfaceType(),
              part.scriptCondition()));
    }
  }

  static boolean validBox(AABB box) {
    return Double.isFinite(box.minX)
        && Double.isFinite(box.minY)
        && Double.isFinite(box.minZ)
        && Double.isFinite(box.maxX)
        && Double.isFinite(box.maxY)
        && Double.isFinite(box.maxZ)
        && box.minX >= 0
        && box.minY >= 0
        && box.minZ >= 0
        && box.maxX <= 1
        && box.maxY <= 1
        && box.maxZ <= 1
        && box.minX <= box.maxX
        && box.minY <= box.maxY
        && box.minZ <= box.maxZ;
  }

  public record Part(
      String id,
      BlockPos offset,
      Rule rule,
      FieldCondition field,
      PropertySpace propertiesSpace,
      String selector,
      String interfaceType,
      StructureScriptChecks.Condition scriptCondition) {
    public Part(String id, BlockPos offset, Rule rule, FieldCondition field, PropertySpace propertiesSpace, String selector, String interfaceType) {
      this(id, offset, rule, field, propertiesSpace, selector, interfaceType, null);
    }
    public Part(String id, BlockPos offset, Rule rule, FieldCondition field, PropertySpace propertiesSpace) {
      this(id, offset, rule, field, propertiesSpace, "", "");
    }

    public Part {
      offset = offset.immutable();
      ResourceSelector.require(selector);
      if (!SmartInterfaceAssignment.AUTOMATIC.equals(interfaceType)) {
        ResourceSelector.require(interfaceType);
      }
    }

    public boolean automaticInterface() {
      return SmartInterfaceAssignment.AUTOMATIC.equals(interfaceType);
    }
  }

  public record Rule(PortKind port, List<Alternative> alternatives) {
    public Rule {
      alternatives = List.copyOf(alternatives);
    }

    public boolean matches(BlockState state) {
      if (port != null) {
        return state.getBlock() instanceof MachinePortBlock block && block.kind() == port;
      }
      for (Alternative alternative : alternatives) {
        if (alternative.matches(state)) {
          return true;
        }
      }
      return false;
    }
  }

  public record Alternative(
      StateOrientationAdapters.BlockSnapshot snapshot,
      Map<Property<?>, Comparable<?>> properties) {
    public Alternative {
      properties = Map.copyOf(properties);
    }

    public Block block() {
      return snapshot.block();
    }

    public boolean matches(BlockState state) {
      if (!state.is(block())) {
        return false;
      }
      for (var entry : properties.entrySet()) {
        if (!state.getValue(entry.getKey()).equals(entry.getValue())) {
          return false;
        }
      }
      return true;
    }
  }

  public record FieldCondition(String name, long min, long max) {
    public boolean matches(MachinePortBlockEntity port) {
      if (!port.schemaSupported()) {
        return false;
      }
      long value = switch (name) {
        case "energy_stored" -> port.energy().stored();
        case "fluid_amount" -> port.fluidTank().getFluidAmount();
        case "item_count" -> {
          long count = 0;
          for (int i = 0; i < port.inventory().getSlots(); i++) {
            count += port.inventory().getStackInSlot(i).getCount();
          }
          yield count;
        }
        default -> throw new IllegalStateException("Uncompiled field");
      };
      return value >= min && value <= max;
    }
  }
}
