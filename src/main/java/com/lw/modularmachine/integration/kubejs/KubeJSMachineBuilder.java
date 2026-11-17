package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.structure.StructureBlockCheck;
import com.lw.modularmachine.machine.structure.MachineDefinitionConfig;
import com.lw.modularmachine.machine.structure.MachineDefinitionFiles;
import com.lw.modularmachine.machine.structure.MachineDefinitionScripts;
import com.lw.modularmachine.machine.structure.StructureScriptChecks;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.recipe.RecipeFailureAction;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.factory.FactoryThreadStateCodec;
import com.lw.modularmachine.machine.structure.StructureModifierDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Configuration-owned machine identities with closed script declarations. */
@ApiStatus.Internal
public final class KubeJSMachineBuilder {
  private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();
  private final Session session;
  private final ResourceLocation id;
  private final JsonObject source;
  private final JsonObject original;
  private final KubeJSBlockArrayBuilder array;
  private final JsonArray modifiers;
  private final JsonArray interfaces;
  private final List<KubeJSMultiBlockModifierBuilder> addedModifiers = new ArrayList<>();
  private KubeJSBlockArrayBuilder lastModifier;
  private boolean built;

  private KubeJSMachineBuilder(Session session, ResourceLocation id, JsonObject source) {
    this.session = session;
    this.id = id;
    this.source = source.deepCopy();
    original = source.deepCopy();
    array = new KubeJSBlockArrayBuilder(this, source.getAsJsonArray("parts"));
    modifiers = source.has("structure_modifiers") ? source.getAsJsonArray("structure_modifiers")
        .deepCopy() : new JsonArray();
    interfaces = source.has("interfaces") ? source.getAsJsonArray("interfaces").deepCopy() : new JsonArray();
  }

  static Session begin() {
    if (ACTIVE.get() != null) {
      throw new IllegalStateException("Nested machine declaration session");
    }
    var session = new Session();
    ACTIVE.set(session);
    return session;
  }

  private static Session active() {
    var session = ACTIVE.get();
    if (session == null) {
      throw new IllegalStateException("Machine declarations require server script loading");
    }
    session.requireOpen();
    return session;
  }

  public static KubeJSMachineBuilder registerMachine(String registryName, String localizedName) {
    var builder = active().builder(registryName);
    builder.mutable();
    if (localizedName == null || localizedName.length() > 512) {
      throw new IllegalArgumentException("Machine display name exceeds 512 characters");
    }
    builder.source.addProperty("translation_key", localizedName);
    return builder;
  }

  public static KubeJSMachineBuilder registerMachine(String registryName, String localizedName, boolean hasFactory, boolean factoryOnly) {
    return registerMachine(registryName, localizedName).setHasFactory(hasFactory).setFactoryOnly(factoryOnly);
  }

  public static KubeJSMachineBuilder registerMachine(String registryName, String localizedName, boolean requiresBlueprint, String failureAction, int color) {
    return registerMachine(registryName, localizedName).setRequiresBlueprint(requiresBlueprint)
        .setFailureAction(failureAction).setColor(color);
  }

  public static KubeJSMachineBuilder registerMachine(String registryName, String localizedName, boolean requiresBlueprint, String failureAction, int color, boolean hasFactory, boolean factoryOnly) {
    return registerMachine(registryName, localizedName, requiresBlueprint, failureAction, color)
        .setHasFactory(hasFactory).setFactoryOnly(factoryOnly);
  }

  public static KubeJSMachineBuilder getBuilder(String registryName) {
    return active().builder(registryName);
  }

  public KubeJSBlockArrayBuilder getBlockArray() {
    mutable();
    return array;
  }

  public KubeJSBlockArrayBuilder getBlockArrayBuilder() {
    return getBlockArray();
  }

  public KubeJSMachineBuilder setParallelizable(boolean value) {
    mutable();
    source.addProperty("parallelizable", value);
    return this;
  }

  public KubeJSMachineBuilder setMaxParallelism(int value) {
    if (value < 1) {
      throw new IllegalArgumentException("Maximum parallelism must be positive");
    }
    object("parallel").addProperty("maximum", value);
    source.remove("max-parallelism");
    return this;
  }

  public KubeJSMachineBuilder setInternalParallelism(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("Internal parallelism cannot be negative");
    }
    object("parallel").addProperty("internal", value);
    return this;
  }

  public KubeJSMachineBuilder setMaxThreads(int value) {
    if (value < 0 || value > FactoryThreadStateCodec.MAX_THREADS) {
      throw new IllegalArgumentException("Maximum threads outside supported range");
    }
    object("factory").addProperty("maximum_extra_threads", value);
    object("factory").remove("maximum_threads");
    source.remove("max-threads");
    return this;
  }

  public KubeJSMachineBuilder addCoreThread(KubeJSFactoryRecipeThread thread) {
    var factory = object("factory");
    if (!factory.has("threads")) {
      factory.add("threads", new JsonArray());
    }
    var captured = Objects.requireNonNull(thread, "thread").capture();
    for (var current : factory.getAsJsonArray("threads")) {
      if (current.getAsJsonObject().get("id").equals(captured.get("id"))) {
        throw new IllegalArgumentException("Duplicate core thread " + captured.get("id"));
      }
    }
    factory.getAsJsonArray("threads").add(captured);
    return this;
  }

  public KubeJSMachineBuilder addSmartInterfaceType(KubeJSSmartInterfaceType type) {
    mutable();
    var captured = Objects.requireNonNull(type, "type").capture();
    for (var current : interfaces) {
      if (current.getAsJsonObject().get("id").equals(captured.get("id"))) {
        throw new IllegalArgumentException("Duplicate smart interface type " + captured.get("id"));
      }
    }
    interfaces.add(captured);
    return this;
  }

  public KubeJSMachineBuilder addSingleBlockModifier(int x, int y, int z, Object block, String description, RecipeModifier... rules) {
    mutable();
    var group = KubeJSMultiBlockModifierBuilder.newBuilder()
        .setBlockArray(KubeJSBlockArrayBuilder.newBuilder().addBlock(x, y, z, block)).addModifier(rules)
        .description(description);
    addMultiBlockModifier(group);
    lastModifier = group.blocks();
    return this;
  }

  public KubeJSMachineBuilder addMultiBlockModifier(KubeJSMultiBlockModifierBuilder modifier) {
    mutable();
    addedModifiers.add(Objects.requireNonNull(modifier, "modifier"));
    return this;
  }

  public KubeJSMachineBuilder setNBT(CompoundTag data) {
    lastArray().setNBT(data);
    return this;
  }

  public KubeJSMachineBuilder setPreviewNBT(CompoundTag data) {
    lastArray().setPreviewNBT(data);
    return this;
  }

  public KubeJSMachineBuilder setBlockChecker(StructureBlockCheck checker) {
    lastArray().setBlockChecker(checker);
    return this;
  }

  private KubeJSBlockArrayBuilder lastArray() {
    mutable();
    return lastModifier == null ? array : lastModifier;
  }

  public KubeJSMachineBuilder setRequiresBlueprint(boolean value) {
    mutable();
    source.addProperty("requires_blueprint", value);
    return this;
  }

  public KubeJSMachineBuilder setFailureAction(String action) {
    mutable();
    RecipeFailureAction.parse(action);
    source.addProperty("failure_action", action);
    return this;
  }

  public KubeJSMachineBuilder setColor(int value) {
    mutable();
    if ((value & ~0xFFFFFF) != 0) {
      throw new IllegalArgumentException("Machine color must be RGB");
    }
    source.addProperty("color", String.format(Locale.ROOT, "%06X", value));
    source.remove("machine_color");
    return this;
  }

  public KubeJSMachineBuilder setHasFactory(boolean value) {
    mutable();
    if (registration().hasFactory() != value) {
      throw new IllegalArgumentException(
          "Factory registration is fixed at startup; change has_factory in the machinery configuration and restart both sides");
    }
    source.addProperty("has_factory", value);
    source.remove("has-factory");
    return this;
  }

  public KubeJSMachineBuilder setFactoryOnly(boolean value) {
    mutable();
    if (registration().factoryOnly() != value) {
      throw new IllegalArgumentException(
          "Controller registration is fixed at startup; change factory_only in the machinery configuration and restart both sides");
    }
    source.addProperty("factory_only", value);
    source.remove("factory-only");
    return this;
  }

  private DedicatedControllerCatalog.Machine registration() {
    return DedicatedControllerCatalog.machines().stream().filter(machine -> machine.id().equals(id)).findFirst()
        .orElseThrow();
  }

  public KubeJSMachineBuilder setControllerBoundingBox(AABB value) {
    mutable();
    var box = new JsonArray();
    for (double coordinate : new double[]{value.minX, value.minY, value.minZ, value.maxX, value.maxY, value.maxZ}) {
      box.add(coordinate);
    }
    source.add("controller_bounding_box", box);
    source.remove("controller-bounding-box");
    return this;
  }

  KubeJSMachineBuilder setMachinePrefix(String value) {
    mutable();
    if (value == null || value.length() > 512) {
      throw new IllegalArgumentException("Machine prefix exceeds 512 characters");
    }
    source.addProperty("prefix", value);
    return this;
  }

  public KubeJSMachineBuilder build() {
    mutable();
    session.capture(this);
    built = true;
    return this;
  }

  void mutable() {
    session.requireOpen();
    if (built) {
      throw new IllegalStateException("Machine builder already built: " + id);
    }
  }

  private JsonObject object(String key) {
    mutable();
    if (!source.has(key)) {
      source.add(key, new JsonObject());
    }
    return source.getAsJsonObject(key);
  }

  String partId(BlockPos offset) {
    return array.partId(offset);
  }

  static ResourceLocation identifier(String text) {
    return text.indexOf(':') < 0 ? ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, text) : ResourceLocation
        .parse(text);
  }

  static final class Session {
    private final Thread owner = Thread.currentThread();
    private final String conditionPrefix = UUID.randomUUID().toString();
    private final Map<ResourceLocation, KubeJSMachineBuilder> builders = new LinkedHashMap<>();
    private final Map<ResourceLocation, String> declarations = new LinkedHashMap<>();
    private final Map<String, StructureScriptChecks.Condition> conditions = new LinkedHashMap<>();
    private final Map<String, StructureModifierDefinition.Display> displays = new LinkedHashMap<>();
    private Map<ResourceLocation, JsonObject> configurations;
    private boolean closed;

    private Session() {
    }

    void requireOpen() {
      if (closed || Thread.currentThread() != owner || ACTIVE.get() != this) {
        throw new IllegalStateException("Machine declaration session is closed or used outside its creating thread");
      }
    }

    private KubeJSMachineBuilder builder(String text) {
      requireOpen();
      var id = identifier(text);
      MachineDefinitionScripts.requireRegistered(id);
      return builders.computeIfAbsent(id, key -> {
        var source = configurations().get(key);
        if (source == null) {
          throw new IllegalArgumentException("Missing configuration machine " + key);
        }
        return new KubeJSMachineBuilder(this, key, source);
      });
    }

    private Map<ResourceLocation, JsonObject> configurations() {
      if (configurations != null) {
        return configurations;
      }
      var root = MachineDefinitionConfig.directory();
      try {
        var files = MachineDefinitionFiles.jsonFiles(root);
        var variables = MachineDefinitionFiles.readVariables(root, files);
        var values = new LinkedHashMap<ResourceLocation, JsonObject>();
        long totalBytes = 0;
        for (var file : files) {
          if (MachineDefinitionFiles.variable(file)) {
            continue;
          }
          var id = MachineDefinitionConfig.id(root.relativize(file));
          var bytes = MachineDefinitionFiles.read(file);
          totalBytes = Math.addExact(totalBytes, bytes.length);
          if (totalBytes > MachineDefinitionFiles.MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("Machine declarations exceed the directory size budget");
          }
          var json = MachineDefinitionFiles.parse(bytes, file.toString());
          if (values.put(id, variables.expand(json, file.toString())) != null) {
            throw new IllegalArgumentException("Duplicate configuration machine " + id);
          }
        }
        configurations = Map.copyOf(values);
        return configurations;
      } catch (IOException invalid) {
        throw new IllegalStateException("Cannot read machine declarations", invalid);
      }
    }

    String condition(StructureScriptChecks.Condition condition) {
      requireOpen();
      String key = conditionPrefix + "/" + conditions.size();
      conditions.put(key, condition);
      return key;
    }

    String display(StructureModifierDefinition.Display display) {
      requireOpen();
      String key = conditionPrefix + "/display/" + displays.size();
      displays.put(key, display);
      return key;
    }

    private void capture(KubeJSMachineBuilder builder) {
      var result = builder.source.deepCopy();
      var groups = builder.modifiers.deepCopy();
      for (var modifier : builder.addedModifiers) {
        groups.add(modifier.capture(builder, this, groups.size()));
      }
      result.add("parts", builder.array.capture(this));
      result.add("interfaces", builder.interfaces.deepCopy());
      result.add("structure_modifiers", groups);
      var patch = new JsonObject();
      var changed = new JsonObject();
      var removed = new JsonArray();
      for (var entry : result.entrySet()) {
        if (!entry.getValue().equals(builder.original.get(entry.getKey()))) {
          changed.add(entry.getKey(), entry.getValue().deepCopy());
        }
      }
      for (String key : builder.original.keySet()) {
        if (!result.has(key)) {
          removed.add(key);
        }
      }
      patch.add("set", changed);
      patch.add("remove", removed);
      declarations.put(builder.id, patch.toString());
    }

    void closeAndPublish() {
      requireOpen();
      for (var builder : builders.values()) {
        if (!builder.built) {
          capture(builder);
        }
      }
      MachineDefinitionScripts.stage(declarations, conditions, displays);
      closed = true;
      ACTIVE.remove();
    }

    void abort() {
      if (Thread.currentThread() != owner) {
        throw new IllegalStateException("Machine declaration abort outside creating thread");
      }
      closed = true;
      if (ACTIVE.get() == this) {
        ACTIVE.remove();
      }
    }
  }
}
