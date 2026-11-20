package com.lw.modularmachine.integration.kubejs;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import com.mojang.serialization.JsonOps;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData;
import com.lw.modularmachine.api.upgrade.UpgradeBusData;
import com.lw.modularmachine.api.upgrade.UpgradeItemData;
import com.lw.modularmachine.api.upgrade.UpgradeRecipeChecks;
import com.lw.modularmachine.api.upgrade.UpgradeEvents;
import com.lw.modularmachine.api.upgrade.DynamicUpgrades;
import com.lw.modularmachine.api.upgrade.UpgradeType;
import com.lw.modularmachine.api.upgrade.UpgradeDescriptions;
import com.lw.modularmachine.machine.upgrade.UpgradeScriptType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import com.lw.modularmachine.machine.upgrade.UpgradeDefinition;
import com.lw.modularmachine.machine.upgrade.UpgradeCatalogService;
import com.lw.modularmachine.machine.upgrade.UpgradeScriptHandlers;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import net.minecraft.world.item.ItemStack;

/**
 * Script-only parsing and mutable builders; publishes a closed upgrade-domain
 * snapshot.
 */
@ApiStatus.Internal
public final class KubeJSUpgradeDeclarations {
  private static final ThreadLocal<Registration> ACTIVE = new ThreadLocal<>();
  private KubeJSUpgradeDeclarations() {
  }

  public static Registration registration() {
    return new Registration();
  }

  static Registration active() {
    var registration = ACTIVE.get();
    if (registration == null) {
      throw new IllegalStateException("Upgrade builders require MMCECEUpgradeEvents.register");
    }
    registration.requireOpen();
    return registration;
  }

  static Registration activeOrNull() {
    var registration = ACTIVE.get();
    if (registration != null) {
      registration.requireOpen();
    }

    return registration;
  }

  /** Fluent KubeJS builder; JSON is kept private as the validation DTO. */
  public static final class FixedUpgradeBuilder {
    private final String idText;
    private final JsonObject source = new JsonObject();
    private boolean valid = true;
    private Registration registration;
    private String localizedName;
    private float level;
    private int maxStack = 256;
    private boolean dynamicType;
    private boolean registeredType;
    private final List<String> descriptions = new ArrayList<>();
    private UpgradeDescriptions.Handler descriptionHandler;
    private UpgradeDescriptions.Handler busDescriptionHandler;

    private FixedUpgradeBuilder(String id, String item) {
      idText = id;
      source.addProperty("schema_version", 1);
      if (item == null || item.isBlank()) {
        valid = false;
      } else {
        source.addProperty("item", item);
      }
    }

    public FixedUpgradeBuilder parallelAdd(int value) {
      number("parallel_add", value);
      return this;
    }

    public FixedUpgradeBuilder parallel(int value) {
      return parallelAdd(value);
    }

    public FixedUpgradeBuilder speed(int numerator, int denominator) {
      number("speed_numerator", numerator);
      number("speed_denominator", denominator);
      return this;
    }

    public FixedUpgradeBuilder energy(int numerator, int denominator) {
      number("energy_numerator", numerator);
      number("energy_denominator", denominator);
      return this;
    }

    public FixedUpgradeBuilder fluid(int numerator, int denominator) {
      number("fluid_numerator", numerator);
      number("fluid_denominator", denominator);
      return this;
    }

    public FixedUpgradeBuilder compatibleMachine(String id) {
      addString("compatible_machines", id);
      return this;
    }

    public FixedUpgradeBuilder incompatibleMachine(String id) {
      addString("incompatible_machines", id);
      return this;
    }

    public FixedUpgradeBuilder incompatibleUpgrade(String id) {
      addString("incompatible_upgrades", id);
      return this;
    }

    public FixedUpgradeBuilder exclusiveGroup(String id) {
      mutable();
      source.addProperty("exclusive_group", id);
      return this;
    }

    public FixedUpgradeBuilder maxCount(int value) {
      number("max_count", value);
      return this;
    }

    public FixedUpgradeBuilder addDescriptions(String... lines) {
      mutable();
      descriptions.addAll(List.of(lines));
      UpgradeDescriptions.validate(descriptions);
      return this;
    }

    public FixedUpgradeBuilder setDescriptionHandler(UpgradeDescriptions.Handler handler) {
      mutable();
      descriptionHandler = Objects.requireNonNull(handler, "handler");
      return this;
    }

    public FixedUpgradeBuilder setBusGUIDescriptionHandler(UpgradeDescriptions.Handler handler) {
      mutable();
      busDescriptionHandler = Objects.requireNonNull(handler, "handler");
      return this;
    }

    public FixedUpgradeBuilder addCompatibleMachines(String... machines) {
      mutable();
      for (var machine : machines) {
        compatibleMachine(machine);
      }
      return this;
    }

    public FixedUpgradeBuilder addIncompatibleMachines(String... machines) {
      mutable();
      for (var machine : machines) {
        incompatibleMachine(machine);
      }
      return this;
    }

    public FixedUpgradeBuilder setDynamicEffect(DynamicUpgrades.Evaluator evaluator) {
      mutable();
      var handlerId = ResourceLocation.parse(idText).withPrefix("upgrade_effect/").toString();
      registration.dynamic(handlerId, Objects.requireNonNull(evaluator, "evaluator"));
      return dynamicEffect(handlerId);
    }

    public void buildAndRegister() {
      mutable();
      if (localizedName == null || registeredType) {
        throw new IllegalStateException("Upgrade type was already registered or is a fixed item definition");
      }
      registeredType = true;
    }

    public FixedUpgradeBuilder dynamicEffect(String id) {
      mutable();
      source.addProperty("dynamic_effect", id);
      return this;
    }

    public FixedUpgradeBuilder lifecycleHandler(String id) {
      mutable();
      source.addProperty("lifecycle_handler", id);
      return this;
    }

    public FixedUpgradeBuilder recipeCheckHandler(String id) {
      mutable();
      source.addProperty("recipe_check_handler", id);
      return this;
    }

    public FixedUpgradeBuilder startDataHandler(String id) {
      mutable();
      source.addProperty("start_data_handler", id);
      return this;
    }

    public FixedUpgradeBuilder startBusDataHandler(String id) {
      mutable();
      source.addProperty("start_bus_data_handler", id);
      return this;
    }

    public FixedUpgradeBuilder tickDataHandler(String id) {
      mutable();
      source.addProperty("tick_data_handler", id);
      return this;
    }

    public FixedUpgradeBuilder finishDataHandler(String id) {
      mutable();
      source.addProperty("finish_data_handler", id);
      return this;
    }

    public FixedUpgradeBuilder startCost(int consume, int durability) {
      costs("start_cost", consume, durability);
      return this;
    }

    public FixedUpgradeBuilder tickCost(int consume, int durability) {
      costs("tick_cost", consume, durability);
      return this;
    }

    public FixedUpgradeBuilder component(String id, String value) {
      components().addProperty(id, value);
      return this;
    }

    public FixedUpgradeBuilder componentNumber(String id, double value) {
      components().addProperty(id, value);
      return this;
    }

    public FixedUpgradeBuilder componentBoolean(String id, boolean value) {
      components().addProperty(id, value);
      return this;
    }

    /** Advanced escape hatch for a component value with a structured JSON shape. */
    public FixedUpgradeBuilder componentJson(String id, String jsonValue) {
      mutable();
      try {
        components().add(id, JsonParser.parseString(jsonValue));
      } catch (RuntimeException invalid) {
        valid = false;
      }
      return this;
    }

    public FixedUpgradeBuilder recipeModifier(String target, String io, String operation, double value) {
      return recipeModifier(target, io, operation, value, false);
    }

    public FixedUpgradeBuilder recipeModifier(String target, String io, String operation, double value, boolean affectChance) {
      mutable();
      try {
        return addModifier(
            new RecipeModifier(
                RecipeModifier.Target.parse(target),
                RecipeModifier.Io.parse(io),
                RecipeModifier.Operation.parse(operation),
                value,
                affectChance));
      } catch (RuntimeException invalid) {
        valid = false;
        throw invalid;
      }
    }

    public FixedUpgradeBuilder addModifier(RecipeModifier modifier) {
      mutable();
      var modifiers = source.has("recipe_modifiers") ? source.getAsJsonArray("recipe_modifiers") : new JsonArray();
      if (modifier == null || modifiers.size() >= RecipeModifiers.MAX_RULES) {
        valid = false;
        throw new IllegalArgumentException("Missing or excessive fixed upgrade recipe modifiers");
      }
      modifiers.add(
          RecipeModifier.CODEC.encodeStart(JsonOps.INSTANCE, Objects.requireNonNull(modifier, "modifier"))
              .getOrThrow());
      source.add("recipe_modifiers", modifiers);
      return this;
    }

    public FixedUpgradeBuilder addModifiers(List<RecipeModifier> modifiers) {
      mutable();
      Objects.requireNonNull(modifiers, "modifiers");
      for (var modifier : modifiers) {
        addModifier(modifier);
      }
      return this;
    }

    private void number(String field, int value) {
      mutable();
      source.addProperty(field, value);
    }

    private JsonObject components() {
      mutable();
      if (!source.has("components")) {
        source.add("components", new JsonObject());
      }
      return source.getAsJsonObject("components");
    }

    private void addString(String field, String value) {
      mutable();
      var values = source.has(field) ? source.getAsJsonArray(field) : new JsonArray();
      values.add(value);
      source.add(field, values);
    }

    private void costs(String field, int consume, int durability) {
      mutable();
      var cost = new JsonObject();
      cost.addProperty("consume", consume);
      cost.addProperty("durability", durability);
      source.add(field, cost);
    }

    private void invalidate() {
      valid = false;
    }

    private void mutable() {
      if (registration != null) {
        registration.requireOpen();
      }
      if (registeredType) {
        throw new IllegalStateException("Upgrade type builder is closed");
      }
    }

    private UpgradeScriptType compileType() {
      return new UpgradeScriptType(
          new UpgradeType(ResourceLocation.parse(idText), localizedName, level, maxStack, dynamicType, descriptions),
          compile(),
          descriptionHandler,
          busDescriptionHandler);
    }

    private UpgradeDefinition compile() {
      if (!valid) {
        throw new JsonParseException("Invalid KubeJS fixed upgrade builder");
      }
      return UpgradeDefinition.parse(ResourceLocation.parse(idText), source);
    }
  }

  public static final class Registration {
    private final Map<ResourceLocation, DynamicUpgrades.Evaluator> dynamic = new HashMap<>();
    private final Map<ResourceLocation, UpgradeRecipeChecks.Checker> checks = new HashMap<>();
    private final Map<ResourceLocation, UpgradeEvents.Listener> events = new HashMap<>();
    private final Map<ResourceLocation, UpgradeItemData.Preparer> itemData = new HashMap<>();
    private final Map<ResourceLocation, UpgradeBusData.Preparer> busData = new HashMap<>();
    private final Map<ResourceLocation, UpgradeRuntimeData.Preparer> runtimeData = new HashMap<>();
    private final List<FixedUpgradeBuilder> definitions = new ArrayList<>();
    private final Set<ResourceLocation> definitionIdentifiers = new LinkedHashSet<>();
    private final Set<ResourceLocation> identifiers = new LinkedHashSet<>();
    private boolean valid = true;
    private final Thread owner = Thread.currentThread();
    private boolean open = true;
    private final Map<ResourceLocation, FixedUpgradeBuilder> types = new HashMap<>();
    private final List<Binding> bindings = new ArrayList<>();
    private final Set<ResourceLocation> supportedItems = new LinkedHashSet<>();

    private record Binding(ResourceLocation item, JsonObject components, ResourceLocation type) {
    }

    void activate() {
      requireOpen();
      ACTIVE.set(this);
    }

    void requireOpen() {
      if (!open || Thread.currentThread() != owner) {
        throw new IllegalStateException("Upgrade registration is closed or used outside its creating thread");
      }
    }

    FixedUpgradeBuilder newType(String name, String localizedName, float level, int maxStack, boolean dynamicType) {
      requireOpen();
      var id = ResourceLocation.parse(name.contains(":") ? name : "kubejs:" + name);
      new UpgradeType(id, localizedName, level, maxStack, dynamicType, List.of());
      if (!id.getNamespace().equals("kubejs")
          || types.containsKey(id)
          || definitionIdentifiers.contains(id)
          || types.size() >= UpgradeCatalogService.MAX_DEFINITIONS) {
        valid = false;
        throw new IllegalArgumentException("Duplicate or invalid upgrade type: " + name);
      }
      var builder = new FixedUpgradeBuilder(id.toString(), "minecraft:air");
      builder.registration = this;
      builder.localizedName = localizedName;
      builder.level = level;
      builder.maxStack = maxStack;
      builder.dynamicType = dynamicType;
      builder.maxCount(maxStack);
      types.put(id, builder);
      return builder;
    }

    void bind(ItemStack stack, String type) {
      requireOpen();
      if (stack == null || stack.isEmpty() || bindings.size() >= UpgradeCatalogService.MAX_DEFINITIONS) {
        throw new IllegalArgumentException("Missing or excessive upgrade item binding");
      }
      var id = ResourceLocation.parse(type.contains(":") ? type : "kubejs:" + type);
      bindings.add(new Binding(stack.getItem().builtInRegistryHolder().key().location(), null, id));
    }

    void support(ItemStack stack) {
      requireOpen();
      if (stack == null || stack.isEmpty() || supportedItems.size() >= UpgradeCatalogService.MAX_DEFINITIONS) {
        throw new IllegalArgumentException("Missing or excessive supported upgrade item");
      }
      supportedItems.add(stack.getItem().builtInRegistryHolder().key().location());
    }

    UpgradeType registeredType(ResourceLocation id) {
      requireOpen();
      var builder = types.get(id);

      return builder == null || !builder.registeredType ? null : builder.compileType().type();
    }

    boolean supports(ResourceLocation item) {
      requireOpen();

      return supportedItems.contains(item) || bindings.stream().anyMatch(binding -> binding.item().equals(item));
    }

    /** Starts one fixed item upgrade definition in the KubeJS server script. */
    public FixedUpgradeBuilder fixedUpgrade(String item, String id) {
      requireOpen();
      ResourceLocation identifier = ResourceLocation.tryParse(id);
      if (identifier == null
          || !identifier.getNamespace().equals("kubejs")
          || !identifier.toString().equals(id)
          || types.containsKey(identifier)
          || !definitionIdentifiers.add(identifier)
          || definitions.size() >= UpgradeCatalogService.MAX_DEFINITIONS) {
        valid = false;
        var invalid = new FixedUpgradeBuilder(id, item);
        invalid.registration = this;
        invalid.invalidate();
        return invalid;
      }
      var builder = new FixedUpgradeBuilder(id, item);
      builder.registration = this;
      definitions.add(builder);
      return builder;
    }

    public void dynamic(String id, DynamicUpgrades.Evaluator handler) {
      put(dynamic, id, handler);
    }

    public void recipeCheck(String id, UpgradeRecipeChecks.Checker handler) {
      put(checks, id, handler);
    }

    public void lifecycle(String id, UpgradeEvents.Listener handler) {
      put(events, id, handler);
    }

    public void startItemData(String id, UpgradeItemData.Preparer handler) {
      put(itemData, id, handler);
    }

    public void startBusData(String id, UpgradeBusData.Preparer handler) {
      put(busData, id, handler);
    }

    public void runtimeData(String id, UpgradeRuntimeData.Preparer handler) {
      put(runtimeData, id, handler);
    }

    private <T> void put(Map<ResourceLocation, T> handlers, String text, T handler) {
      requireOpen();
      ResourceLocation id = KubeJSRegistrationIds.parse(text);
      if (id == null
          || handler == null
          || handlers.containsKey(id)
          || !identifiers.contains(id) && identifiers.size() >= ServerCallbackRegistry.MAX_HANDLERS) {
        valid = false;
        return;
      }
      identifiers.add(id);
      handlers.put(id, handler);
    }

    public boolean valid() {
      return valid;
    }

    public UpgradeScriptHandlers.Snapshot snapshot() {
      requireOpen();
      open = false;
      if (ACTIVE.get() == this) {
        ACTIVE.remove();
      }
      List<UpgradeDefinition> compiled = new ArrayList<>();
      var compiledTypes = new HashMap<ResourceLocation, UpgradeScriptType>();
      if (valid) {
        try {
          definitions.stream().map(FixedUpgradeBuilder::compile).forEach(compiled::add);
          for (var entry : types.entrySet()) {
            if (!entry.getValue().registeredType) {
              throw new IllegalStateException("Upgrade type must buildAndRegister");
            }
            compiledTypes.put(entry.getKey(), entry.getValue().compileType());
          }
          for (var binding : bindings) {
            var type = compiledTypes.get(binding.type());
            if (type == null) {
              throw new IllegalArgumentException("Unknown upgrade type: " + binding.type());
            }
            compiled.add(type.template().forItem(binding.item(), binding.components()));
            supportedItems.add(binding.item());
          }
          for (var definition : definitions) {
            if (definition.localizedName != null
                || definition.descriptions.isEmpty()
                    && definition.descriptionHandler == null
                    && definition.busDescriptionHandler == null) {
              continue;
            }
            var effect = definition.compile();
            compiledTypes.put(
                effect.effect().id(),
                new UpgradeScriptType(
                    new UpgradeType(
                        effect.effect().id(),
                        effect.effect().id().toString(),
                        0,
                        256,
                        false,
                        definition.descriptions),
                    effect,
                    definition.descriptionHandler,
                    definition.busDescriptionHandler));
          }
        } catch (RuntimeException invalid) {
          valid = false;
        }
      }
      return !valid ? UpgradeScriptHandlers.Snapshot.EMPTY : new UpgradeScriptHandlers.Snapshot(
          dynamic,
          checks,
          events,
          itemData,
          busData,
          runtimeData,
          compiled,
          compiledTypes,
          supportedItems);
    }
  }

}
