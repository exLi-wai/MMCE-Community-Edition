package com.lw.modularmachine.machine.structure;

import static com.lw.modularmachine.machine.structure.MachineDefinitionJson.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.appearance.MachineComponentType;
import com.lw.modularmachine.machine.controller.MachineControllerRegistrationFlags;
import com.lw.modularmachine.machine.factory.FactoryThreadPreset;
import com.lw.modularmachine.machine.factory.FactoryThreadStateCodec;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.orientation.OrientationPolicy;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterDefinitions;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import com.lw.modularmachine.machine.structure.MachineDefinition.Part;
import static com.lw.modularmachine.machine.structure.MachineDefinition.validBox;
import org.jetbrains.annotations.ApiStatus;

/**
 * JSON validation, reference capture and snapshot-only compilation of machine
 * declarations.
 */
@ApiStatus.Internal
public final class MachineDefinitionParser {

  private MachineDefinitionParser() {
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
    return parse(id, root, snapshot, MachineDefinitionDefaults.capture());
  }

  public static MachineDefinition parse(ResourceLocation id, JsonObject root, StateOrientationAdapters.RegistrySnapshot snapshot, MachineDefinitionDefaults defaults) {
    Objects.requireNonNull(defaults, "defaults");
    String path = id + ": $";
    header(root, path);
    MachinePartParser.checkAlternativeBudget(Map.of(id, root));
    OrientationPolicy policy = orientationPolicy(root, path);
    List<Part> parts = MachinePartParser.parse(id, parts(root, path), path + ".parts", snapshot, false);
    var dynamicPatterns = DynamicPatternDefinition.parse(id, root, policy, snapshot);
    long transfer = energyTransfer(root, path, parts, dynamicPatterns);
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
    int maximumFactoryThreads = maximumFactoryThreads(root, path, defaults.maximumFactoryThreads());
    var factory = root.has("factory") ? FactoryThreadPreset
        .parse(root.get("factory"), path + ".factory", maximumFactoryThreads) : FactoryThreadPreset.defaults();

    var modifiers = root.has("recipe_modifiers") ? RecipeModifiers
        .parse(root.get("recipe_modifiers"), path + ".recipe_modifiers") : List.<RecipeModifier>of();
    var parallel = parallelism(root, path, defaults);
    var interfaceTypes = root.has("interfaces") ? SmartInterfaceType
        .parse(root.get("interfaces"), path + ".interfaces") : Map.<String, SmartInterfaceType>of();
    boolean hideComponentsWhenFormed = booleanAlias(
        root,
        "hide_components_when_formed",
        "hide-components-when-formed",
        false,
        path);
    AABB controllerBoundingBox = boundingBoxAlias(root, "controller_bounding_box", "controller-bounding-box", path);
    var registrationFlags = MachineControllerRegistrationFlags.parse(root, path);

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
        parallel.internal(),
        parallel.maximum(),
        root.has("requires_blueprint") && bool(root.get("requires_blueprint"), path + ".requires_blueprint"),
        registrationFlags.factoryOnly(),
        machineColor(root, path, defaults.casingColor()),
        hideComponentsWhenFormed,
        controllerBoundingBox,
        dynamicPatterns,
        Map.of(),
        null,
        hiddenComponentTypes(root, path),
        registrationFlags.hasFactory(),
        root.has("parallelizable") ? bool(root.get("parallelizable"), path + ".parallelizable") : defaults
            .parallelizable(),
        maximumFactoryThreads,
        RecipeAdapterDefinitions.parse(root, path));
  }

  /**
   * Collect requested literals off-thread; capture subsequently parses only those
   * literals on-thread.
   */
  public static List<StateOrientationAdapters.BlockReference> blockReferences(Map<ResourceLocation, ? extends JsonElement> roots) {
    MachinePartParser.checkAlternativeBudget(roots);
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
        MachinePartParser.partFields(part, partPath);
        if (!part.has("block")) {
          continue;
        }
        if (MachinePartParser.candidatePort(part) != null) {
          continue;
        }
        JsonObject required = part
            .has("properties") ? object(part.get("properties"), partPath + ".properties") : new JsonObject();
        var requested = new ArrayList<StateOrientationAdapters.PropertyReference>();
        for (var property : MachinePartParser.propertyOptions(required, partPath + ".properties").entrySet()) {
          for (String literal : property.getValue()) {
            requested.add(
                new StateOrientationAdapters.PropertyReference(
                    property.getKey(),
                    literal,
                    partPath + ".properties." + property.getKey()));
          }
        }
        var literals = List.copyOf(requested);
        for (String name : MachinePartParser.blockNames(part, partPath)) {
          ResourceLocation blockId = MachinePartParser.blockId(name, partPath + ".block");
          result.add(new StateOrientationAdapters.BlockReference(blockId, partPath, literals));
        }
      }
    }
    return List.copyOf(result);
  }

  private static OrientationPolicy orientationPolicy(JsonObject root, String path) {
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
    return policy;
  }

  private static long energyTransfer(JsonObject root, String path, List<Part> parts, List<DynamicPatternDefinition> dynamicPatterns) {
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
    return transfer;
  }

  private record Parallelism(int internal, int maximum) {
  }

  private static Parallelism parallelism(JsonObject root, String path, MachineDefinitionDefaults defaults) {
    int internalParallelism = 0;
    int maxParallelism = defaults.maximumParallelism();
    if (root.has("parallel")) {
      var parallel = object(root.get("parallel"), path + ".parallel");
      fields(parallel, path + ".parallel", "internal", "maximum");
      internalParallelism = Math.toIntExact(optionalInteger(parallel, "internal", 0, path + ".parallel"));
      maxParallelism = Math.toIntExact(optionalInteger(parallel, "maximum", maxParallelism, path + ".parallel"));
      if (root.has("max-parallelism") && parallel.has("maximum")) {
        throw error(path, "Use only one of parallel.maximum or max-parallelism");
      }
    }
    if (root.has("max-parallelism")) {
      maxParallelism = Math.toIntExact(integer(root.get("max-parallelism"), path + ".max-parallelism"));
    }
    if (internalParallelism > maxParallelism
        && !root.has("max-parallelism")
        && (!root.has("parallel") || !root.getAsJsonObject("parallel").has("maximum"))) {
      maxParallelism = internalParallelism;
    }
    return new Parallelism(internalParallelism, maxParallelism);
  }

  static int header(JsonObject root, String path) {
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
        "recipe_adapters",
        "structure_modifiers",
        "dynamic_patterns",
        "dynamic-patterns",
        "interfaces",
        "parallel",
        "parallelizable",
        "max-parallelism",
        "max-threads",
        "requires_blueprint",
        "factory_only",
        "factory-only",
        "has_factory",
        "has-factory",
        "hide_components_when_formed",
        "hide-components-when-formed",
        "hide_component_types",
        "controller_bounding_box",
        "controller-bounding-box",
        "color",
        "machine_color");
    return 2;
  }

  private static int maximumFactoryThreads(JsonObject root, String path, int fallback) {
    JsonObject factory = root.has("factory") ? object(root.get("factory"), path + ".factory") : null;
    if (root.has("max-threads") && factory != null && factory.has("maximum_threads")) {
      throw error(path, "Use only one of factory.maximum_threads or max-threads");
    }
    long maximum = fallback;
    if (root.has("max-threads")) {
      maximum = integer(root.get("max-threads"), path + ".max-threads");
    } else if (factory != null && factory.has("maximum_threads")) {
      maximum = integer(factory.get("maximum_threads"), path + ".factory.maximum_threads");
    }
    if (maximum < 1 || maximum > FactoryThreadStateCodec.MAX_THREADS) {
      throw error(path + ".factory.maximum_threads", "Factory thread limit is outside the supported bounded range");
    }

    return (int) maximum;
  }

  private static Set<MachineComponentType> hiddenComponentTypes(JsonObject root, String path) {
    if (!root.has("hide_component_types")) {
      return EnumSet.allOf(MachineComponentType.class);
    }
    var types = EnumSet.noneOf(MachineComponentType.class);
    var values = array(root.get("hide_component_types"), path + ".hide_component_types");
    for (int index = 0; index < values.size(); index++) {
      String value = string(values.get(index), path + ".hide_component_types[" + index + "]");
      MachineComponentType selected = null;
      for (var type : MachineComponentType.values()) {
        if (type.getSerializedName().equals(value)) {
          selected = type;
          break;
        }
      }
      if (selected == null) {
        throw error(path + ".hide_component_types[" + index + "]", "Unknown component type " + value);
      }
      types.add(selected);
    }

    return types;
  }

  static JsonArray parts(JsonObject root, String path) {
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

  private static int machineColor(JsonObject root, String path, int fallback) {
    if (root.has("color") && root.has("machine_color")) {
      throw error(path, "Use only one of color or machine_color");
    }
    String key = root.has("machine_color") ? "machine_color" : "color";
    return root.has(key) ? color(root.get(key), path + "." + key) : fallback;
  }

}
