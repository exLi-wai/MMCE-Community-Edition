package com.lw.modularmachine.machine.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.config.MachineStartupConfig;
import com.lw.modularmachine.machine.appearance.ControllerAppearanceState;
import com.lw.modularmachine.machine.structure.MachineDefinitionConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Internal registration-only manifest, frozen before deferred block
 * registration.
 */
public final class DedicatedControllerCatalog {

  private static final long MAX_FILE_BYTES = 67_108_864L;
  private static final long MAX_TOTAL_BYTES = 1_073_741_824L;
  private static final int MAX_MACHINE_FILES = 1024;
  private static final String CONTROLLER_OVERLAY = "mmcece:block/overlay/controllers/overlay_controller";
  private static final String FACTORY_CONTROLLER_OVERLAY = "mmcece:block/overlay/controllers/overlay_factory_controller";

  /** Internal immutable resources for one controller appearance. */
  public record Appearance(ResourceLocation casing, ResourceLocation overlay, ResourceLocation model) {
  }

  public record Entry(
      String id,
      ResourceLocation machine,
      boolean factory,
      String name,
      ResourceLocation casing,
      ResourceLocation overlay,
      ResourceLocation model,
      Map<ControllerAppearanceState, Appearance> appearances) {
    public Entry {
      var copied = new EnumMap<ControllerAppearanceState, Appearance>(ControllerAppearanceState.class);
      copied.putAll(appearances);
      appearances = Collections.unmodifiableMap(copied);
    }

    public Entry(String id, ResourceLocation machine, boolean factory, String name, ResourceLocation casing, ResourceLocation overlay, ResourceLocation model) {
      this(id, machine, factory, name, casing, overlay, model, Map.of());
    }

    public Appearance baseAppearance() {
      return new Appearance(casing, overlay, model);
    }

    public Appearance appearance(ControllerAppearanceState state) {
      return appearances.getOrDefault(state, baseAppearance());
    }

    public String registryPath() {
      return id.contains("/") ? (factory ? "factory_controller/" : "controller/") + machine
          .getNamespace() + "/" + machine.getPath() : (factory ? "factory_controller_" : "controller_") + id;
    }
  }
  public record Machine(
      ResourceLocation id,
      String translationKey,
      int color,
      boolean hasFactory,
      boolean factoryOnly) {
    public Machine(ResourceLocation id, String translationKey, int color) {
      this(id, translationKey, color, false, false);
    }
  }
  private static final boolean FACTORIES_BY_DEFAULT = MachineStartupConfig.enabledAtStartup();
  private static final List<Machine> MACHINES = scanMachines();
  private static final List<Entry> ENTRIES = load();
  private DedicatedControllerCatalog() {
  }
  public static List<Entry> entries() {
    return ENTRIES;
  }
  public static List<Machine> machines() {
    return MACHINES;
  }
  public static Path path() {
    return FMLPaths.CONFIGDIR.get().resolve("modularmachinery/controllers.json");
  }

  /**
   * Includes the generator revision so stale generated files cannot join a new
   * manifest.
   */
  public static String resourceFingerprint() {
    try {
      byte[] bytes = ("registered-machines-v10\n" + FACTORIES_BY_DEFAULT + "\n" + MACHINES + ENTRIES)
          .getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static boolean restartRequired() {
    try {
      return MachineStartupConfig.restartRequired() || !MACHINES.equals(scanMachines()) || !ENTRIES.equals(read());
    } catch (IOException | RuntimeException invalid) {
      return true;
    }
  }

  private static List<Machine> scanMachines() {
    var directory = MachineDefinitionConfig.ensureDirectory();
    var machines = new TreeMap<ResourceLocation, Machine>();
    var skipped = new ArrayList<String>();

    List<Path> files;
    try (var paths = Files.walk(directory)) {
      files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> path.toString().endsWith(".json") && !path.toString().endsWith(".var.json"))
          .limit(MAX_MACHINE_FILES + 1L).toList();
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot discover startup machines under " + directory, failure);
    }
    if (files.size() > MAX_MACHINE_FILES) {
      throw new IllegalStateException("At most " + MAX_MACHINE_FILES + " startup machines");
    }

    long total = 0;
    for (var file : files) {
      var relative = directory.relativize(file);
      long size;
      try {
        size = Files.size(file);
      } catch (IOException io) {
        skipped.add(relative + ": I/O " + io.getMessage());
        ModularMachine.LOGGER.error("Failed to stat machine definition {}", relative, io);
        continue;
      }
      if (size > MAX_FILE_BYTES) {
        skipped.add(relative + ": file exceeds " + MAX_FILE_BYTES + " bytes");
        ModularMachine.LOGGER.error("Skipping oversized machine definition {} ({} bytes)", relative, size);
        continue;
      }
      try {
        total = Math.addExact(total, size);
      } catch (ArithmeticException overflow) {
        throw new IllegalStateException("Total machine definition size overflowed", overflow);
      }
      if (total > MAX_TOTAL_BYTES) {
        throw new IllegalStateException(
            "Total machine definition size exceeded " + MAX_TOTAL_BYTES + " bytes; refusing to load");
      }

      try {
        var id = MachineDefinitionConfig.id(relative);
        var root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        String translation = text(root, "translation_key", "machine." + id.getNamespace() + "." + id.getPath());
        int color = color(root);
        var flags = MachineControllerRegistrationFlags.parse(root, relative.toString());
        if (machines
            .putIfAbsent(id, new Machine(id, translation, color, flags.hasFactory(), flags.factoryOnly())) != null) {
          skipped.add(relative + ": duplicate machine id " + id);
          ModularMachine.LOGGER.error("Skipping duplicate machine definition {} (id {})", relative, id);
        }
      } catch (JsonParseException | IllegalArgumentException invalid) {
        skipped.add(relative + ": " + invalid.getMessage());
        ModularMachine.LOGGER.error("Skipping invalid machine definition {}: {}", relative, invalid.getMessage());
      } catch (IOException io) {
        skipped.add(relative + ": I/O " + io.getMessage());
        ModularMachine.LOGGER.error("Failed to read machine definition {}", relative, io);
      } catch (RuntimeException unexpected) {
        skipped.add(relative + ": " + unexpected);
        ModularMachine.LOGGER.error("Failed to load machine definition {}", relative, unexpected);
      }
    }

    if (!skipped.isEmpty()) {
      ModularMachine.LOGGER.warn("[mmcece] {} invalid machine definition(s) skipped:", skipped.size());
      for (var message : skipped) {
        ModularMachine.LOGGER.warn("  - {}", message);
      }
    }
    return List.copyOf(machines.values());
  }

  private static List<Entry> load() {
    try {
      Files.createDirectories(path().getParent());
      if (!Files.exists(path())) {
        Files.writeString(
            path(),
            "{\"schema_version\":1,\"controllers\":[]}\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
      }
      return read();
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException(
          "Cannot load startup controller manifest " + path() + "; fix the file and restart",
          failure);
    }
  }

  private static List<Entry> read() throws IOException {
    if (Files.size(path()) > 262144) {
      throw new IllegalArgumentException("Controller manifest exceeds 256 KiB");
    }
    var root = JsonParser.parseString(Files.readString(path(), StandardCharsets.UTF_8)).getAsJsonObject();
    var schema = root.get("schema_version");
    if (!Set.of("schema_version", "controllers").containsAll(root.keySet())
        || schema == null
        || !schema.isJsonPrimitive()
        || !schema.getAsJsonPrimitive().isNumber()
        || schema.getAsBigDecimal().intValueExact() != 1
        || !root.has("controllers")
        || !root.get("controllers").isJsonArray()) {
      throw new IllegalArgumentException("Invalid controller manifest header");
    }
    var values = root.getAsJsonArray("controllers");
    if (values.size() > 128) {
      throw new IllegalArgumentException("At most 128 dedicated controllers");
    }
    var result = new ArrayList<Entry>();
    var ids = new HashSet<String>();
    var skipped = new ArrayList<String>();

    for (JsonElement value : values) {
      try {
        var source = value.getAsJsonObject();
        if (!Set.of("id", "machine", "type", "name", "casing", "overlay", "model", "appearances")
            .containsAll(source.keySet())) {
          throw new IllegalArgumentException("Unknown controller field");
        }
        String id = text(source, "id", null);
        String type = text(source, "type", null);
        String name = text(source, "name", "");
        if (!id.matches("[a-z0-9_]{1,64}")
            || !ids.add(id)
            || !Set.of("ordinary", "factory").contains(type)
            || name.length() > 128) {
          throw new IllegalArgumentException("Invalid dedicated controller entry: " + id);
        }
        var machine = registeredMachine(text(source, "machine", null));
        if (!controllerEnabled(machine, type.equals("factory"))) {
          throw new IllegalArgumentException(
              "Controller role is disabled by the startup machine settings: " + machine + " / " + type);
        }
        var casing = location(text(source, "casing", "mmcece:block/blockcasing_plain"));
        var overlay = location(
            text(source, "overlay", type.equals("factory") ? FACTORY_CONTROLLER_OVERLAY : CONTROLLER_OVERLAY));
        var model = source.has("model") ? location(text(source, "model", null)) : null;
        result.add(
            new Entry(
                id,
                machine,
                type.equals("factory"),
                name,
                casing,
                overlay,
                model,
                appearances(source, new Appearance(casing, overlay, model))));
      } catch (RuntimeException invalid) {
        // 单条控制器条目失败 -> 跳过并继续
        skipped.add(invalid.getMessage());
        ModularMachine.LOGGER.error("Skipping invalid controller entry: {}", invalid.getMessage());
      }
    }

    result.sort(Comparator.comparing(Entry::id));
    var roles = new HashSet<String>();
    var usedIds = new HashSet<String>();
    for (var entry : result) {
      if (!roles.add(entry.factory() + ":" + entry.machine()) || !usedIds.add(entry.id())) {
        // 合并后的重复角色/ID：记录后跳过这一条，不再整体失败
        skipped.add("Duplicate controller role or ID: " + entry.id());
        ModularMachine.LOGGER.error("Skipping duplicate controller role/ID {}", entry.id());
        continue;
      }
    }
    // 用去重后的条目重新组装（上一步只是校验，这一步清理冲突项）
    if (!skipped.isEmpty()) {
      var seenRoles = new HashSet<String>();
      var seenIds = new HashSet<String>();
      var cleaned = new ArrayList<Entry>(result.size());
      for (var entry : result) {
        if (seenRoles.add(entry.factory() + ":" + entry.machine()) && seenIds.add(entry.id())) {
          cleaned.add(entry);
        }
      }
      result = cleaned;
    }

    for (var machine : MACHINES) {
      for (boolean factory : new boolean[]{false, true}) {
        if (controllerEnabled(machine, factory) && roles.add(factory + ":" + machine.id())) {
          String id = (factory ? "factory/" : "ordinary/") + machine.id().getNamespace() + "/" + machine.id().getPath();
          result.add(
              new Entry(
                  id,
                  machine.id(),
                  factory,
                  "",
                  location("mmcece:block/blockcasing_plain"),
                  location(factory ? FACTORY_CONTROLLER_OVERLAY : CONTROLLER_OVERLAY),
                  null));
        }
      }
    }
    result.sort(Comparator.comparing(Entry::id));

    if (!skipped.isEmpty()) {
      ModularMachine.LOGGER.warn("[mmcece] {} invalid controller entr(ies) skipped:", skipped.size());
      for (var message : skipped) {
        ModularMachine.LOGGER.warn("  - {}", message);
      }
    }
    return List.copyOf(result);
  }

  private static boolean controllerEnabled(ResourceLocation machine, boolean factory) {
    return MACHINES.stream().filter(entry -> entry.id().equals(machine))
        .anyMatch(entry -> controllerEnabled(entry, factory));
  }

  private static boolean controllerEnabled(Machine machine, boolean factory) {
    return factory ? machine.hasFactory() || FACTORIES_BY_DEFAULT : !machine.factoryOnly();
  }

  private static Map<ControllerAppearanceState, Appearance> appearances(JsonObject source, Appearance base) {
    if (!source.has("appearances")) {
      return Map.of();
    }
    if (!source.get("appearances").isJsonObject()) {
      throw new IllegalArgumentException("Expected object controller field: appearances");
    }
    var result = new EnumMap<ControllerAppearanceState, Appearance>(ControllerAppearanceState.class);
    for (var entry : source.getAsJsonObject("appearances").entrySet()) {
      ControllerAppearanceState state = null;
      for (var candidate : ControllerAppearanceState.values()) {
        if (candidate.getSerializedName().equals(entry.getKey())) {
          state = candidate;
          break;
        }
      }
      if (state == null) {
        throw new IllegalArgumentException("Unknown controller appearance: " + entry.getKey());
      }
      if (!entry.getValue().isJsonObject()) {
        throw new IllegalArgumentException("Expected object controller appearance: " + entry.getKey());
      }
      var value = entry.getValue().getAsJsonObject();
      if (!Set.of("casing", "overlay", "model").containsAll(value.keySet())) {
        throw new IllegalArgumentException("Unknown controller appearance field: " + entry.getKey());
      }
      var casing = value.has("casing") ? location(text(value, "casing", null)) : base.casing();
      var overlay = value.has("overlay") ? location(text(value, "overlay", null)) : base.overlay();
      var model = base.model();
      if (value.has("model")) {
        model = value.get("model").isJsonNull() ? null : location(text(value, "model", null));
      }
      result.put(state, new Appearance(casing, overlay, model));
    }

    return result;
  }

  private static String text(JsonObject object, String key, String fallback) {
    var value = object.get(key);
    if (value == null && fallback != null) {
      return fallback;
    }
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException("Expected string controller field: " + key);
    }
    return value.getAsString();
  }

  private static ResourceLocation registeredMachine(String value) {
    var id = location(value);
    if (MACHINES.stream().noneMatch(machine -> machine.id().equals(id))) {
      throw new IllegalArgumentException("Controller override references unknown startup machine: " + id);
    }
    return id;
  }
  public static String translation(ResourceLocation id) {
    return MACHINES.stream().filter(machine -> machine.id().equals(id)).findFirst().map(Machine::translationKey)
        .orElse("machine." + id.getNamespace() + "." + id.getPath());
  }

  public static int color(ResourceLocation id) {
    return MACHINES.stream().filter(machine -> machine.id().equals(id)).findFirst().map(Machine::color)
        .orElse(MachineStartupConfig.defaultCasingColorAtStartup());
  }

  /**
   * Original labels: an ordinary controller is "&lt;machine&gt; Controller" and a
   * factory controller is "&lt;machine&gt; Integration Controller". An explicit
   * manifest name replaces the whole label.
   */
  public static MutableComponent displayName(Entry entry, String descriptionId) {
    if (!entry.name().isEmpty()) {
      return Component.translatableWithFallback(descriptionId, entry.name());
    }
    return Component.translatable(
        entry.factory() ? "block.mmcece.registered_factory_controller" : "block.mmcece.registered_controller",
        Component.translatable(translation(entry.machine())));
  }
  private static ResourceLocation location(String value) {
    var location = ResourceLocation.parse(value);
    if (location.getPath().isEmpty()) {
      throw new IllegalArgumentException("Empty controller resource path");
    }
    return location;
  }

  private static int color(JsonObject root) {
    if (root.has("color") && root.has("machine_color")) {
      throw new IllegalArgumentException("Use only one of color or machine_color");
    }
    var value = root.has("machine_color") ? root.get("machine_color") : root.get("color");
    if (value == null) {
      return MachineStartupConfig.defaultCasingColorAtStartup();
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException("Machine color must be a six-digit hexadecimal string");
    }
    String text = value.getAsString();
    if (text.startsWith("#")) {
      text = text.substring(1);
    }
    if (!text.matches("[0-9a-fA-F]{6}")) {
      throw new IllegalArgumentException("Machine color must be a six-digit hexadecimal string");
    }
    return Integer.parseInt(text, 16);
  }
}
