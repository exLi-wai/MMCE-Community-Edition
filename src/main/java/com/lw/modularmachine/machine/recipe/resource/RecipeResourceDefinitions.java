package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.api.recipe.ChemicalRecipeData;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.machine.component.ResourceTypeRegistry;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
  private static final Map<String, ResourceLocation> FIELD_OWNERS = new LinkedHashMap<>();
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

  static {
    register(ITEM);
    register(FLUID);
    register(ENERGY);
    register(CHEMICAL);
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
        var allowed = new LinkedHashSet<>(fieldNames());
        allowed.addAll(RESERVED_FIELDS);
        for (var entry : input.entries().toList()) {
          var key = ops.getStringValue(entry.getFirst()).result();
          if (key.isEmpty() || !allowed.contains(key.orElseThrow())) {
            return DataResult.error(() -> "Unsupported recipe resource field: " + entry.getFirst());
          }
        }
        DataResult<List<RecipeResources.Entry<?>>> decoded = DataResult.success(List.of());
        for (var type : types) {
          decoded = decoded.flatMap(entries -> decodeEntry(type, ops, input).map(entry -> {
            var next = new ArrayList<RecipeResources.Entry<?>>(entries.size() + 1);
            next.addAll(entries);
            next.add(entry);

            return List.copyOf(next);
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
          || FIELD_OWNERS.containsKey(field)) {
        throw new IllegalArgumentException("Conflicting native resource field: " + field);
      }
    }
    REGISTERED.put(type.id(), type);
    for (var field : fields) {
      FIELD_OWNERS.put(field, type.id());
    }
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
    return Set.copyOf(FIELD_OWNERS.keySet());
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
    var owned = new LinkedHashSet<String>();
    FIELD_OWNERS.forEach((field, owner) -> {
      if (owner.equals(type.id())) {
        owned.add(field);
      }
    });

    return Set.copyOf(owned);
  }

  /** Decode this descriptor's fields from a complete native declaration. */
  public static <D, T> DataResult<D> decodeOwned(RecipeResourceType<D> type, DynamicOps<T> ops, T declaration) {
    requireRegistered(type);

    return ops.getMap(declaration).flatMap(input -> decodeEntry(type, ops, input).map(RecipeResources.Entry::data));
  }

  /**
   * Pure validation defers extension data to its registry-aware native decoder.
   */
  public static synchronized boolean hasExtensionFields(Set<String> declared) {
    return Fields.hasExtensions(declared);
  }

  private static <D, T> DataResult<RecipeResources.Entry<D>> decodeEntry(RecipeResourceType<D> type, DynamicOps<T> ops, MapLike<T> input) {
    var allowed = fields(type);
    var owned = ops.createMap(
        input.entries()
            .filter(entry -> ops.getStringValue(entry.getFirst()).result().map(allowed::contains).orElse(false)));

    return ops.getMap(owned).flatMap(values -> type.nativeCodec().decode(ops, values))
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
