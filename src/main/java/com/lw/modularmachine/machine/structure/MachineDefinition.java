package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.block.SmartInterfaceBlock;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryThreadPreset;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.orientation.OrientationPolicy;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.smart.SmartInterfaceAssignment;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Immutable machine declarations and resolved dynamic layouts. */
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
    MachineDefinition source) {

  /**
   * In Laozi's world, Laozi builds it however he wants.
   */
  private static final Logger LOGGER = LogUtils.getLogger();
  public static final int MAX_PARTS = Integer.MAX_VALUE;

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes, int internalParallelism, int maxParallelism, boolean requiresBlueprint, boolean factoryOnly, int color, boolean hideComponentsWhenFormed, AABB controllerBoundingBox) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, internalParallelism, maxParallelism, requiresBlueprint,
        factoryOnly, color, hideComponentsWhenFormed, controllerBoundingBox, List.of(), Map.of(), null);
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes, int internalParallelism, int maxParallelism) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, internalParallelism, maxParallelism, false, false,
        0xFFFFFF, false, new AABB(0, 0, 0, 1, 1, 1));
  }

  public MachineDefinition(ResourceLocation id, String translationKey, List<Part> parts, long energyPerTick, OrientationPolicy orientationPolicy, OrientationCompilation orientationCompilation, FactoryThreadPreset factoryPreset, List<RecipeModifier> recipeModifiers, List<StructureModifierDefinition> structureModifiers, Map<String, SmartInterfaceType> interfaceTypes) {
    this(id, translationKey, parts, energyPerTick, orientationPolicy, orientationCompilation, factoryPreset,
        recipeModifiers, structureModifiers, interfaceTypes, 0, Integer.MAX_VALUE);
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

  /**
   * Server-thread convenience entry point; resource reload uses an explicit
   * snapshot.
   */
  public static MachineDefinition parse(ResourceLocation id, JsonObject root) {
    var snapshot = StateOrientationAdapters.capture(blockReferences(Map.of(id, root)));
    return parse(id, root, snapshot);
  }

  /**
   * Pure parsing/compilation: no registry, world, Block.rotate, or property
   * parser calls.
   */
  public static MachineDefinition parse(ResourceLocation id, JsonObject root, StateOrientationAdapters.RegistrySnapshot snapshot) {
    String path = id + ": $";
    header(root, path);
    OrientationPolicy policy = OrientationPolicy.HORIZONTAL;
    if (root.has("orientation")) {
      JsonObject orientation = object(root.get("orientation"), path + ".orientation");
      fields(orientation, path + ".orientation", "policy");
      try {
        policy = OrientationPolicy.parse(string(orientation.get("policy"), path + ".orientation.policy"));
      } catch (IllegalArgumentException exception) {
        throw error(path + ".orientation.policy", exception.getMessage());
      }
    }
    List<Part> parts = parseParts(id, parts(root, path), path + ".parts", snapshot, false);
    var dynamicPatterns = DynamicPatternDefinition.parse(id, root, policy, snapshot);
    long transfer = 0;
    if (root.has("operation")) {
      JsonObject operation = object(root.get("operation"), path + ".operation");
      fields(operation, path + ".operation", "type", "energy_per_tick");
      if (!string(operation.get("type"), path + ".operation.type").equals("energy_transfer")) {
        throw error(path + ".operation.type", "Unsupported operation");
      }
      transfer = integer(operation.get("energy_per_tick"), path + ".operation.energy_per_tick");
      if (transfer <= 0 || transfer > Integer.MAX_VALUE) {
        throw error(path + ".operation.energy_per_tick", "Invalid transfer amount");
      }
      for (PortKind kind : new PortKind[]{PortKind.ENERGY_INPUT, PortKind.ENERGY_OUTPUT}) {
        if (parts.stream().filter(p -> p.rule().port() == kind).count() != 1) {
          throw error(path + ".operation", "Energy transfer needs exactly one input and output port");
        }
      }
      if (dynamicPatterns.stream().flatMap(pattern -> pattern.declarationParts().stream()).anyMatch(
          part -> part.rule().port() == PortKind.ENERGY_INPUT || part.rule().port() == PortKind.ENERGY_OUTPUT)) {
        throw error(path + ".operation", "Energy transfer requires its single input and output in fixed parts");
      }
    }
    var structureModifiers = StructureModifierDefinition.parse(id, root, parts, snapshot);
    var compilation = OrientationCompilation.compile(id, policy, parts, structureModifiers);
    int allowedMask = compilation.allowedMask();
    for (var pattern : dynamicPatterns) {
      allowedMask &= pattern.allowedOrientationMask();
    }
    if (allowedMask == 0) {
      throw error(path + ".dynamic_patterns", "No common representable orientation for all dynamic templates");
    }
    compilation = compilation.restrict(allowedMask);
    String translation = root.has("translation_key") ? string(
        root.get("translation_key"),
        path + ".translation_key") : "machine." + id.getNamespace() + "." + id.getPath();
    var factory = root.has("factory") ? FactoryThreadPreset
        .parse(root.get("factory"), path + ".factory") : FactoryThreadPreset.defaults();

    var modifiers = root.has("recipe_modifiers") ? RecipeModifiers
        .parse(root.get("recipe_modifiers"), path + ".recipe_modifiers") : List.<RecipeModifier>of();
    int internalParallelism = 0;
    int maxParallelism = Integer.MAX_VALUE;
    if (root.has("parallel")) {
      var parallel = object(root.get("parallel"), path + ".parallel");
      fields(parallel, path + ".parallel", "internal", "maximum");
      internalParallelism = Math.toIntExact(optionalInteger(parallel, "internal", 0, path + ".parallel"));
      maxParallelism = Math.toIntExact(optionalInteger(parallel, "maximum", Integer.MAX_VALUE, path + ".parallel"));
    }
    var interfaceTypes = root.has("interfaces") ? SmartInterfaceType
        .parse(root.get("interfaces"), path + ".interfaces") : Map.<String, SmartInterfaceType>of();
    boolean hideComponentsWhenFormed = booleanAlias(
        root,
        "hide_components_when_formed",
        "hide-components-when-formed",
        false,
        path);
    AABB controllerBoundingBox = boundingBoxAlias(root, "controller_bounding_box", "controller-bounding-box", path);

    return new MachineDefinition(
        id,
        translation,
        parts,
        transfer,
        policy,
        compilation,
        factory,
        modifiers,
        structureModifiers,
        interfaceTypes,
        internalParallelism,
        maxParallelism,
        root.has("requires_blueprint") && bool(root.get("requires_blueprint"), path + ".requires_blueprint"),
        root.has("factory_only") && bool(root.get("factory_only"), path + ".factory_only"),
        machineColor(root, path),
        hideComponentsWhenFormed,
        controllerBoundingBox,
        dynamicPatterns,
        Map.of(),
        null);
  }

  /**
   * Collect requested literals off-thread; capture subsequently parses only those
   * literals on-thread.
   */
  public static List<StateOrientationAdapters.BlockReference> blockReferences(Map<ResourceLocation, ? extends JsonElement> roots) {
    List<StateOrientationAdapters.BlockReference> result = new ArrayList<>();
    if (roots.size() > 1024) {
      throw error("$", "At most 1024 machine definitions per reload");
    }
    int totalParts = 0;
    for (var entry : roots.entrySet()) {
      String path = entry.getKey() + ": $";
      JsonObject root = object(entry.getValue(), path);
      int schema = header(root, path);
      var declared = parts(root, path);
      var parts = new ArrayList<JsonElement>();
      declared.forEach(parts::add);
      parts.addAll(StructureModifierDefinition.captureParts(root, path));
      parts.addAll(DynamicPatternDefinition.captureParts(root, path));
      totalParts += parts.size();
      if (totalParts > 4_194_304) {
        throw error(path + ".parts", "Reload exceeds 4194304 total parts");
      }
      for (int index = 0; index < parts.size(); index++) {
        String partPath = path + ".parts[" + index + "]";
        JsonObject part = object(parts.get(index), partPath);
        partFields(part, partPath);
        if (!part.has("block")) {
          continue;
        }
        if (candidatePort(part) != null) {
          continue;
        }
        JsonObject required = part
            .has("properties") ? object(part.get("properties"), partPath + ".properties") : new JsonObject();
        for (var variant : propertyVariants(required, partPath + ".properties")) {
          for (String name : blockNames(part, partPath)) {
            String blockPath = partPath + ".block";
            String propertyPath = partPath + ".properties";
            List<StateOrientationAdapters.PropertyReference> variantProperties = new ArrayList<>();
            for (var property : variant.entrySet()) {
              variantProperties.add(
                  new StateOrientationAdapters.PropertyReference(
                      property.getKey(),
                      property.getValue(),
                      propertyPath + "." + property.getKey()));
            }
            ResourceLocation blockId = blockId(name, blockPath);
            if (result.size() >= 4_194_304) {
              throw error(partPath, "Reload exceeds 4194304 block alternatives");
            }
            result.add(new StateOrientationAdapters.BlockReference(blockId, partPath, variantProperties));
          }
        }
      }
    }
    return List.copyOf(result);
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
          throw new java.util.concurrent.CancellationException();
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
        this);
  }

  private static void appendDynamic(List<Part> target, Set<BlockPos> positions, Set<String> ids, DynamicPatternDefinition pattern, List<Part> template, String group, int repetition) {
    for (int index = 0; index < template.size(); index++) {
      var part = template.get(index);
      var offset = DynamicPatternDefinition
          .translated(part.offset(), pattern.startOffset(), pattern.step(), repetition);
      String prefix = "dynamic:" + pattern.id() + ":" + group + ":" + repetition + ":";
      String localId = part.id();
      if (prefix.length() + localId.length() > 128) {
        localId = java.util.UUID.nameUUIDFromBytes(localId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
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
              part.interfaceType()));
    }
  }

  static List<Part> parseParts(ResourceLocation id, JsonArray elements, String path, StateOrientationAdapters.RegistrySnapshot snapshot, boolean allowAnchor) {
    List<Part> parts = new ArrayList<>();
    int skippedParts = 0;
    Set<BlockPos> offsets = new HashSet<>();
    Set<String> partIds = new HashSet<>();
    for (int index = 0; index < elements.size(); index++) {
      String partPath = path + "[" + index + "]";
      JsonObject part = object(elements.get(index), partPath);
      partFields(part, partPath);
      JsonArray xyz = array(part.get("offset"), partPath + ".offset");
      if (xyz.size() != 3) {
        throw error(partPath + ".offset", "Expected three integer coordinates");
      }
      int[] values = new int[3];
      for (int axis = 0; axis < 3; axis++) {
        long value = integer(xyz.get(axis), partPath + ".offset[" + axis + "]");
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
          throw error(partPath + ".offset[" + axis + "]", "Offset outside the 32-bit coordinate range");
        }
        values[axis] = (int) value;
      }
      BlockPos offset = new BlockPos(values[0], values[1], values[2]);
      if (!allowAnchor && offset.equals(BlockPos.ZERO) || !offsets.add(offset)) {
        throw error(partPath + ".offset", "Duplicate/controller-anchor part");
      }
      String partId = part.has("part_id") ? string(
          part.get("part_id"),
          partPath + ".part_id") : "offset:" + values[0] + "," + values[1] + "," + values[2];
      if (partId.isBlank() || partId.length() > 128 || !partIds.add(partId)) {
        throw error(partPath + ".part_id", "Expected unique nonblank part ID of at most 128 characters");
      }
      PropertySpace space;
      try {
        space = part.has("properties_space") ? PropertySpace
            .parse(string(part.get("properties_space"), partPath + ".properties_space")) : PropertySpace.LOCAL;
      } catch (IllegalArgumentException exception) {
        throw error(partPath + ".properties_space", exception.getMessage());
      }
      if (!part.has("block")) {
        throw error(partPath, "Expected block");
      }
      PortKind port = candidatePort(part);
      List<Alternative> alternatives = new ArrayList<>();
      if (port != null) {
        if (part.has("properties") || part.has("properties_space")) {
          throw error(partPath, "Core ports do not have state properties");
        }
      } else {
        JsonObject properties = part
            .has("properties") ? object(part.get("properties"), partPath + ".properties") : new JsonObject();
        for (String name : blockNames(part, partPath)) {
          ResourceLocation blockId = blockId(name, partPath);
          if (!snapshot.has(blockId)) {
            LOGGER.debug("{}: skipping unknown block {}", partPath, blockId);
            continue;
          }
          var block = snapshot.block(blockId, partPath);
          for (var variant : propertyVariants(properties, partPath + ".properties")) {
            Map<Property<?>, Comparable<?>> required = new HashMap<>();
            for (var entry : variant.entrySet()) {
              String propertyPath = partPath + ".properties." + entry.getKey();
              var property = block.property(entry.getKey(), propertyPath);
              required.put(property.property(), property.value(entry.getValue(), propertyPath));
            }
            alternatives.add(new Alternative(block, required));
          }
        }
        if (alternatives.isEmpty()) {
          offsets.remove(offset);
          partIds.remove(partId);
          skippedParts++;
          LOGGER.debug("{}: no loadable blocks left; skipping the whole part", partPath);
          continue;
        }
      }
      FieldCondition condition = null;
      if (part.has("field")) {
        if (port == null) {
          throw error(partPath + ".field", "BE fields require an explicitly typed core port");
        }
        JsonObject field = object(part.get("field"), partPath + ".field");
        fields(field, partPath + ".field", "name", "min", "max");
        String name = string(field.get("name"), partPath + ".field.name");
        PortKind.Resource expected = switch (name) {
          case "energy_stored" -> PortKind.Resource.ENERGY;
          case "fluid_amount" -> PortKind.Resource.FLUID;
          case "item_count" -> PortKind.Resource.ITEM;
          default -> throw error(partPath + ".field.name", "Unsupported BE field: " + name);
        };
        if (port.resource() != expected) {
          throw error(partPath + ".field", "BE field/port resource mismatch");
        }
        long min = optionalInteger(field, "min", 0, partPath + ".field");
        long max = optionalInteger(field, "max", Long.MAX_VALUE, partPath + ".field");
        if (min < 0 || max < min) {
          throw error(partPath + ".field", "Invalid BE field range");
        }
        condition = new FieldCondition(name, min, max);
      }
      String selector = part.has("selector") ? string(part.get("selector"), partPath + ".selector") : "";
      String interfaceType = part.has("interface") ? string(part.get("interface"), partPath + ".interface") : "";
      parts.add(new Part(partId, offset, new Rule(port, alternatives), condition, space, selector, interfaceType));
    }
    if (skippedParts > 0) {
      LOGGER.warn(
          "{}: skipped {} part(s) whose blocks are missing from the registry (definition loads without them)",
          id,
          skippedParts);
    }
    return List.copyOf(parts);
  }

  private static int header(JsonObject root, String path) {
    // The pose space is selected by the orientation field only.
    fields(
        root,
        path,
        "translation_key",
        "parts",
        "operation",
        "orientation",
        "factory",
        "recipe_modifiers",
        "structure_modifiers",
        "dynamic_patterns",
        "dynamic-patterns",
        "interfaces",
        "parallel",
        "requires_blueprint",
        "factory_only",
        "hide_components_when_formed",
        "hide-components-when-formed",
        "controller_bounding_box",
        "controller-bounding-box",
        "color",
        "machine_color");
    return 2;
  }

  private static JsonArray parts(JsonObject root, String path) {
    JsonArray parts = array(root.get("parts"), path + ".parts");
    if (parts.isEmpty()) {
      throw error(path + ".parts", "Expected at least 1 part");
    }
    return parts;
  }

  private static boolean bool(JsonElement element, String path) {
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
      throw error(path, "Expected boolean");
    }
    return element.getAsBoolean();
  }

  private static boolean booleanAlias(JsonObject root, String primary, String legacy, boolean fallback, String path) {
    if (root.has(primary) && root.has(legacy)) {
      throw error(path, "Use only one of " + primary + " or " + legacy);
    }
    String key = root.has(primary) ? primary : root.has(legacy) ? legacy : null;
    return key == null ? fallback : bool(root.get(key), path + "." + key);
  }

  private static AABB boundingBoxAlias(JsonObject root, String primary, String legacy, String path) {
    if (root.has(primary) && root.has(legacy)) {
      throw error(path, "Use only one of " + primary + " or " + legacy);
    }
    String key = root.has(primary) ? primary : root.has(legacy) ? legacy : null;
    if (key == null) {
      return new AABB(0, 0, 0, 1, 1, 1);
    }
    JsonArray values = array(root.get(key), path + "." + key);
    if (values.size() != 6) {
      throw error(path + "." + key, "Expected six finite coordinates");
    }
    double minX = decimal(values.get(0), path + "." + key + "[0]");
    double minY = decimal(values.get(1), path + "." + key + "[1]");
    double minZ = decimal(values.get(2), path + "." + key + "[2]");
    double maxX = decimal(values.get(3), path + "." + key + "[3]");
    double maxY = decimal(values.get(4), path + "." + key + "[4]");
    double maxZ = decimal(values.get(5), path + "." + key + "[5]");
    AABB result = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    if (!validBox(result)) {
      throw error(path + "." + key, "Expected a box inside [0, 1]");
    }
    return result;
  }

  private static double decimal(JsonElement element, String path) {
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      throw error(path, "Expected number");
    }
    double value = element.getAsDouble();
    if (!Double.isFinite(value)) {
      throw error(path, "Expected finite number");
    }
    return value;
  }

  private static boolean validBox(AABB box) {
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

  private static int color(JsonElement element, String path) {
    String value = string(element, path);
    if (value.startsWith("#")) {
      value = value.substring(1);
    }
    if (!value.matches("[0-9a-fA-F]{6}")) {
      throw error(path, "Expected a six-digit hexadecimal RGB color, for example FFFFFF");
    }
    return Integer.parseInt(value, 16);
  }

  private static int machineColor(JsonObject root, String path) {
    if (root.has("color") && root.has("machine_color")) {
      throw error(path, "Use only one of color or machine_color");
    }
    String key = root.has("machine_color") ? "machine_color" : "color";
    return root.has(key) ? color(root.get(key), path + "." + key) : 0xFFFFFF;
  }

  private static void partFields(JsonObject part, String path) {
    fields(
        part,
        path,
        "offset",
        "block",
        "properties",
        "field",
        "part_id",
        "properties_space",
        "selector",
        "interface");
  }

  private static List<String> blockNames(JsonObject part, String path) {
    if (part.get("block").isJsonPrimitive()) {
      return List.of(string(part.get("block"), path + ".block"));
    }
    JsonArray elements = array(part.get("block"), path + ".block");
    if (elements.isEmpty() || elements.size() > 256) {
      throw error(path + ".block", "Expected 1..256 alternatives");
    }
    List<String> result = new ArrayList<>();
    for (int i = 0; i < elements.size(); i++) {
      result.add(string(elements.get(i), path + ".block[" + i + "]"));
    }
    return result;
  }

  private static List<Map<String, String>> propertyVariants(JsonObject properties, String path) {
    List<Map<String, String>> variants = new ArrayList<>();
    variants.add(new LinkedHashMap<>());
    for (var entry : properties.entrySet()) {
      String propertyPath = path + "." + entry.getKey();
      List<String> values;
      if (entry.getValue().isJsonArray()) {
        JsonArray array = entry.getValue().getAsJsonArray();
        if (array.isEmpty() || array.size() > 256) {
          throw error(propertyPath, "Expected 1..256 property values");
        }
        values = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
          values.add(literal(array.get(index), propertyPath + "[" + index + "]"));
        }
      } else {
        values = List.of(literal(entry.getValue(), propertyPath));
      }
      List<Map<String, String>> expanded = new ArrayList<>();
      for (var variant : variants) {
        for (String value : values) {
          if (expanded.size() >= 256) {
            throw error(path, "Property state combinations exceed 256 variants");
          }
          var copy = new LinkedHashMap<>(variant);
          copy.put(entry.getKey(), value);
          expanded.add(copy);
        }
      }
      variants = expanded;
    }
    return variants;
  }

  private static PortKind blockPort(JsonElement value) {
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
      return tryParsePort(value.getAsString());
    }
    if (value.isJsonArray() && value.getAsJsonArray().size() == 1) {
      JsonElement candidate = value.getAsJsonArray().get(0);
      if (candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString()) {
        return tryParsePort(candidate.getAsString());
      }
    }
    return null;
  }

  private static PortKind candidatePort(JsonObject part) {
    return blockPort(part.get("block"));
  }

  private static PortKind tryParsePort(String value) {
    for (var kind : PortKind.values()) {
      if (kind.name().equalsIgnoreCase(value) || kind.path().equalsIgnoreCase(value)) {
        return kind;
      }
    }
    return null;
  }

  private static ResourceLocation blockId(String name, String path) {
    ResourceLocation id = ResourceLocation.tryParse(name);
    if (id == null) {
      throw error(path, "Invalid block ID: " + name);
    }
    return id;
  }

  private static JsonObject object(JsonElement value, String path) {
    if (value == null || !value.isJsonObject()) {
      throw error(path, "Expected object");
    }
    return value.getAsJsonObject();
  }

  private static JsonArray array(JsonElement value, String path) {
    if (value == null || !value.isJsonArray()) {
      throw error(path, "Expected array");
    }
    return value.getAsJsonArray();
  }

  private static String string(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw error(path, "Expected string");
    }
    return value.getAsString();
  }

  private static String literal(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive()) {
      throw error(path, "Expected property literal");
    }
    return value.getAsString();
  }

  private static long optionalInteger(JsonObject owner, String name, long fallback, String path) {
    return owner.has(name) ? integer(owner.get(name), path + "." + name) : fallback;
  }

  private static long integer(JsonElement value, String path) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw error(path, "Expected integer");
    }
    try {
      return value.getAsBigDecimal().longValueExact();
    } catch (ArithmeticException | NumberFormatException exception) {
      throw error(path, "Non-integral/out-of-range integer");
    }
  }

  private static void fields(JsonObject object, String path, String... allowed) {
    Set<String> names = Set.of(allowed);
    for (String name : object.keySet()) {
      if (!names.contains(name)) {
        throw error(path + "." + name, "Unsupported field");
      }
    }
  }

  private static JsonParseException error(String path, String message) {
    return new JsonParseException(path + ": " + message);
  }

  public record Part(
      String id,
      BlockPos offset,
      Rule rule,
      FieldCondition field,
      PropertySpace propertiesSpace,
      String selector,
      String interfaceType) {
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
