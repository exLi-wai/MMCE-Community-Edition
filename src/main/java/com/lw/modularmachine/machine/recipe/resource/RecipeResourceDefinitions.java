package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.api.recipe.BufferedAmountRecipeData;
import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.CompressedAirRecipeData;
import com.lw.modularmachine.api.recipe.EnvironmentRecipeData;
import com.lw.modularmachine.api.recipe.EmberRecipeData;
import com.lw.modularmachine.api.recipe.LaserRecipeData;
import com.lw.modularmachine.api.recipe.WorldPoolRecipeData;
import com.lw.modularmachine.api.recipe.MeteorRecipeData;
import com.lw.modularmachine.api.recipe.KeyedAmountRecipeData;
import com.lw.modularmachine.api.recipe.ConstellationRecipeData;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.machine.component.ResourceTypeRegistry;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/** Typed native fields, frozen on first decoding before catalog publication. */
@ApiStatus.Internal
public final class RecipeResourceDefinitions {
  private static final Set<String> RESERVED_FIELDS = Set.of(
      "type",
      "machine",
      "priority",
      "duration",
      "failure_action",
      "parallelized",
      "random_seed",
      "interface_inputs",
      "ingredient_arrays",
      "random_item_outputs",
      "triggered_resources",
      "selectors",
      "modifiers",
      "thread_name",
      "max_threads",
      "recipe_tooltips",
      "load_jei",
      "item_callbacks",
      "neoforge:conditions",
      "_kubejs_changed_marker",
      "controller_command_actions");
  private static final Map<ResourceLocation, RecipeResourceType<?>> REGISTERED = new LinkedHashMap<>();
  private static final Map<ResourceLocation, Set<String>> FIELDS_BY_TYPE = new HashMap<>();
  private static final Set<RecipeResourceType<?>> BUILTINS;
  private static List<RecipeResourceType<?>> frozen;

  public static final RecipeResourceType<ItemRecipeData> ITEM = new RecipeResourceType<>(
      id("item"),
      ItemRecipeData.class,
      ItemRecipeData.CODEC,
      ItemRecipeData.NONE,
      data -> !data.inputs().isEmpty() || !data.payments().fuels().isEmpty(),
      data -> !data.outputs().isEmpty());
  public static final RecipeResourceType<FluidRecipeData> FLUID = new RecipeResourceType<>(
      id("fluid"),
      FluidRecipeData.class,
      FluidRecipeData.CODEC,
      FluidRecipeData.NONE,
      data -> !data.inputs().isEmpty() || !data.perTickInputs().isEmpty(),
      data -> !data.outputs().isEmpty() || !data.perTickOutputs().isEmpty());
  public static final RecipeResourceType<EnergyRecipeData> ENERGY = new RecipeResourceType<>(
      id("energy"),
      EnergyRecipeData.class,
      EnergyRecipeData.CODEC,
      EnergyRecipeData.NONE,
      data -> data.inputPerTick() > 0,
      data -> data.outputPerTick() > 0);
  public static final RecipeResourceType<ChemicalRecipeData> CHEMICAL = new RecipeResourceType<>(
      id("chemical"),
      ChemicalRecipeData.class,
      ChemicalRecipeData.CODEC.optionalFieldOf("chemicals", ChemicalRecipeData.NONE),
      ChemicalRecipeData.NONE,
      data -> !data.inputs().isEmpty() || !data.perTick().isEmpty(),
      data -> !data.outputs().isEmpty() || !data.perTickOutputs().isEmpty());

  public static final RecipeResourceType<EnvironmentRecipeData> ENVIRONMENT = new RecipeResourceType<>(
      id("environment"),
      EnvironmentRecipeData.class,
      EnvironmentRecipeData.CODEC,
      EnvironmentRecipeData.NONE,
      data -> !data.requirements().isEmpty(),
      data -> false);

  public static final RecipeResourceType<LaserRecipeData> LASER = new RecipeResourceType<>(
      id("laser"),
      LaserRecipeData.class,
      LaserRecipeData.CODEC,
      LaserRecipeData.NONE,
      data -> data.input() > 0,
      data -> false);

  public static final RecipeResourceType<EmberRecipeData> EMBER = new RecipeResourceType<>(
      id("ember"),
      EmberRecipeData.class,
      EmberRecipeData.CODEC,
      EmberRecipeData.NONE,
      data -> data.micros() > 0,
      data -> false);
  public static final RecipeResourceType<BufferedAmountRecipeData> MANA = new RecipeResourceType<>(
      id("mana"),
      BufferedAmountRecipeData.class,
      BufferedAmountRecipeData.codec("mana_input", "mana_output"),
      BufferedAmountRecipeData.NONE,
      data -> data.input() > 0 || data.output() > 0,
      data -> data.output() > 0);
  public static final RecipeResourceType<BufferedAmountRecipeData> AURA = new RecipeResourceType<>(
      id("aura"),
      BufferedAmountRecipeData.class,
      BufferedAmountRecipeData.inputCodec("aura_input"),
      BufferedAmountRecipeData.NONE,
      data -> data.input() > 0 || data.output() > 0,
      data -> false);

  public static final RecipeResourceType<BufferedAmountRecipeData> HEAT = new RecipeResourceType<>(
      id("heat"),
      BufferedAmountRecipeData.class,
      BufferedAmountRecipeData.codec("heat_input", "heat_output"),
      BufferedAmountRecipeData.NONE,
      data -> data.input() > 0 || data.output() > 0,
      data -> data.output() > 0);

  public static final RecipeResourceType<CompressedAirRecipeData> COMPRESSED_AIR = new RecipeResourceType<>(
      id("compressed_air"),
      CompressedAirRecipeData.class,
      CompressedAirRecipeData.CODEC,
      CompressedAirRecipeData.NONE,
      data -> data.input().isPresent() || data.output().isPresent(),
      data -> data.output().isPresent());

  public static final RecipeResourceType<WorldPoolRecipeData> AURA_WORLD = worldPool("aura_world", "aura", false);
  public static final RecipeResourceType<WorldPoolRecipeData> WILL = worldPool("will", "will", true);
  public static final RecipeResourceType<WorldPoolRecipeData> VIS = worldPool("vis", "vis", true);
  public static final RecipeResourceType<WorldPoolRecipeData> FLUX = worldPool("flux", "flux", true);
  public static final RecipeResourceType<BufferedAmountRecipeData> LIFE_ESSENCE = new RecipeResourceType<>(
      id("life_essence"),
      BufferedAmountRecipeData.class,
      BufferedAmountRecipeData.codec("life_essence_input", "life_essence_output"),
      BufferedAmountRecipeData.NONE,
      data -> data.input() > 0 || data.output() > 0,
      data -> data.output() > 0);

  private static RecipeResourceType<WorldPoolRecipeData> worldPool(String name, String field, boolean hasInput) {
    return new RecipeResourceType<>(
        id(name),
        WorldPoolRecipeData.class,
        WorldPoolRecipeData.codec(field, hasInput),
        WorldPoolRecipeData.NONE,
        WorldPoolRecipeData::present,
        data -> data.output().isPresent());
  }

  public static final RecipeResourceType<MeteorRecipeData> METEOR = new RecipeResourceType<>(
      id("meteor"),
      MeteorRecipeData.class,
      MeteorRecipeData.CODEC,
      MeteorRecipeData.NONE,
      data -> data.output().isPresent(),
      data -> data.output().isPresent());
  public static final RecipeResourceType<KeyedAmountRecipeData> ESSENTIA = keyed("essentia");
  public static final RecipeResourceType<KeyedAmountRecipeData> STARLIGHT = keyed("starlight");
  public static final RecipeResourceType<ConstellationRecipeData> CONSTELLATION = new RecipeResourceType<>(
      id("constellation"),
      ConstellationRecipeData.class,
      ConstellationRecipeData.CODEC,
      ConstellationRecipeData.NONE,
      data -> !data.active().isEmpty(),
      data -> false);

  private static RecipeResourceType<KeyedAmountRecipeData> keyed(String name) {
    return new RecipeResourceType<>(
        id(name),
        KeyedAmountRecipeData.class,
        KeyedAmountRecipeData.codec(name),
        KeyedAmountRecipeData.NONE,
        KeyedAmountRecipeData::present,
        data -> !data.output().isEmpty());
  }

  static {
    register(ITEM);
    register(FLUID);
    register(ENERGY);
    register(CHEMICAL);
    register(ENVIRONMENT);
    register(LASER);
    register(EMBER);
    register(MANA);
    register(AURA);
    register(HEAT);
    register(COMPRESSED_AIR);
    register(AURA_WORLD);
    register(WILL);
    register(VIS);
    register(FLUX);
    register(LIFE_ESSENCE);
    register(METEOR);
    register(ESSENTIA);
    register(STARLIGHT);
    register(CONSTELLATION);
    BUILTINS = Set.copyOf(REGISTERED.values());
  }

  public static boolean isBuiltin(RecipeResourceType<?> type) {
    return BUILTINS.contains(type);
  }

  public static final MapCodec<RecipeResources> CODEC = new MapCodec<>() {
    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) {
      return fieldNames().stream().map(ops::createString);
    }

    @Override
    public <T> DataResult<RecipeResources> decode(DynamicOps<T> ops, MapLike<T> input) {
      try {
        var types = snapshot();
        var grouped = new HashMap<ResourceLocation, List<Pair<T, T>>>();
        var entries = input.entries().iterator();
        while (entries.hasNext()) {
          var entry = entries.next();
          var key = ops.getStringValue(entry.getFirst()).result().orElse(null);
          if (key != null && RESERVED_FIELDS.contains(key)) {
            continue;
          }
          // snapshot() closes registration before we read the field index.
          var owner = Fields.OWNERS.get(key);
          if (owner == null) {
            return DataResult.error(() -> "Unsupported recipe resource field: " + entry.getFirst());
          }
          grouped.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(entry);
        }
        DataResult<List<RecipeResources.Entry<?>>> decoded = DataResult.success(new ArrayList<>(types.size()));
        for (var type : types) {
          var owned = grouped.getOrDefault(type.id(), List.of());
          decoded = decoded.flatMap(values -> decodeFields(type, ops, owned.stream()).map(data -> {
            values.add(data);
            return values;
          }));
        }

        return decoded.map(RecipeResources::new);
      } catch (RuntimeException invalid) {
        return DataResult.error(() -> "Invalid recipe resource declaration: " + invalid.getMessage());
      }
    }

    @Override
    public <T> RecordBuilder<T> encode(RecipeResources value, DynamicOps<T> ops, RecordBuilder<T> prefix) {
      var types = snapshot();
      for (var entry : value.entries()) {
        if (!types.contains(entry.type())) {
          return prefix.withErrorsFrom(DataResult.error(() -> "Unregistered recipe resource: " + entry.type().id()));
        }
      }
      for (var type : types) {
        prefix = encodeType(type, value, ops, prefix);
      }

      return prefix;
    }
  };

  private RecipeResourceDefinitions() {
  }

  /**
   * Lightweight structural metadata; use without initializing native stack
   * codecs.
   */
  @ApiStatus.Internal
  public static final class Fields {
    private static final Map<String, ResourceLocation> OWNERS = new LinkedHashMap<>();
    private static final Set<String> BUILTIN_IDS = Set
        .of("mmcece:item", "mmcece:fluid", "mmcece:energy", "mmcece:chemical");

    private Fields() {
    }

    private static synchronized void install(ResourceLocation type, List<String> fields) {
      for (var field : fields) {
        OWNERS.put(field, type);
      }
    }

    public static synchronized Set<String> names() {
      return Set.copyOf(OWNERS.keySet());
    }

    public static synchronized boolean hasExtensions(Set<String> declared) {
      for (var field : declared) {
        var owner = OWNERS.get(field);
        if (owner != null && !BUILTIN_IDS.contains(owner.toString())) {
          return true;
        }
      }

      return false;
    }
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }

  public static synchronized <D> void register(RecipeResourceType<D> type) {
    Objects.requireNonNull(type, "type");
    if (frozen != null) {
      throw new IllegalStateException("Recipe resource definition registration is closed");
    }
    if (REGISTERED.containsKey(type.id())) {
      throw new IllegalArgumentException("Duplicate recipe resource definition: " + type.id());
    }
    if (REGISTERED.size() >= 256) {
      throw new IllegalArgumentException("Too many recipe resource definitions");
    }
    // MapCodec keys describe possible fields; composed encoder/decoder codecs
    // can enumerate the same field more than once.
    var fields = type.nativeCodec().keys(JsonOps.INSTANCE).map(value -> value.getAsString()).distinct().toList();
    if (fields.isEmpty()) {
      throw new IllegalArgumentException("Resource codec needs native fields: " + type.id());
    }
    for (var field : fields) {
      if (field.isBlank()
          || field.length() > 128
          || RESERVED_FIELDS.contains(field)
          || Fields.OWNERS.containsKey(field)) {
        throw new IllegalArgumentException("Conflicting native resource field: " + field);
      }
    }
    REGISTERED.put(type.id(), type);
    FIELDS_BY_TYPE.put(type.id(), Set.copyOf(fields));
    Fields.install(type.id(), fields);
  }

  public static synchronized List<RecipeResourceType<?>> snapshot() {
    if (frozen == null) {
      ResourceTypeRegistry.snapshot();
      for (var type : REGISTERED.values()) {
        if (!type.resourceType().id().equals(type.id())) {
          throw new IllegalArgumentException("Recipe resource identity differs: " + type.id());
        }
      }
      frozen = List.copyOf(REGISTERED.values());
    }

    return frozen;
  }

  /** Key introspection alone does not close startup registration. */
  public static synchronized Set<String> fieldNames() {
    return Fields.names();
  }

  /** Builder lookup does not close startup integration registration. */
  public static synchronized Optional<RecipeResourceType<?>> find(ResourceLocation id) {
    return Optional.ofNullable(REGISTERED.get(id));
  }

  public static synchronized void requireRegistered(RecipeResourceType<?> type) {
    Objects.requireNonNull(type, "type");
    if (REGISTERED.get(type.id()) != type) {
      throw new IllegalArgumentException("Unregistered recipe resource descriptor: " + type.id());
    }
  }

  public static synchronized Set<String> fields(RecipeResourceType<?> type) {
    requireRegistered(type);
    return FIELDS_BY_TYPE.get(type.id());
  }

  /** Decode this descriptor's fields from a complete native declaration. */
  public static <D, T> DataResult<D> decodeOwned(RecipeResourceType<D> type, DynamicOps<T> ops, T declaration) {
    var allowed = fields(type);
    return ops.getMap(declaration)
        .flatMap(
            input -> decodeFields(
                type,
                ops,
                input.entries().filter(
                    entry -> ops.getStringValue(entry.getFirst()).result().map(allowed::contains).orElse(false))))
        .map(RecipeResources.Entry::data);
  }

  /**
   * Pure validation defers extension data to its registry-aware native decoder.
   */
  public static synchronized boolean hasExtensionFields(Set<String> declared) {
    return Fields.hasExtensions(declared);
  }

  private static <D, T> DataResult<RecipeResources.Entry<D>> decodeFields(RecipeResourceType<D> type, DynamicOps<T> ops, Stream<Pair<T, T>> fields) {
    return ops.getMap(ops.createMap(fields)).flatMap(values -> type.nativeCodec().decode(ops, values))
        .map(data -> new RecipeResources.Entry<>(type, data));
  }

  private static <D, T> RecordBuilder<T> encodeType(RecipeResourceType<D> type, RecipeResources value, DynamicOps<T> ops, RecordBuilder<T> prefix) {
    var encoded = type.nativeCodec().codec().encodeStart(ops, value.get(type));
    prefix.withErrorsFrom(encoded);
    var allowed = fields(type);
    encoded.result().ifPresent(data -> {
      var entries = ops.getMapValues(data);
      prefix.withErrorsFrom(entries);
      entries.result().ifPresent(values -> values.forEach(entry -> {
        var key = ops.getStringValue(entry.getFirst());
        prefix.withErrorsFrom(key);
        if (key.result().filter(allowed::contains).isPresent()) {
          prefix.add(entry.getFirst(), entry.getSecond());
        } else {
          prefix.withErrorsFrom(DataResult.error(() -> "Resource codec emitted an unowned field: " + type.id()));
        }
      }));
    });

    return prefix;
  }
}
