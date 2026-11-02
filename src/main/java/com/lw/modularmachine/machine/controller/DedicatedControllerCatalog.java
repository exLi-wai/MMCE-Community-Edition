package com.lw.modularmachine.machine.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.ModularMachine;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/** Registration-only manifest, frozen before deferred block registration. */
public final class DedicatedControllerCatalog {

  private static final long MAX_FILE_BYTES = 67_108_864L;
  private static final long MAX_TOTAL_BYTES = 1_073_741_824L;
  private static final int MAX_MACHINE_FILES = 1024;

  public record Entry(
      String id,
      ResourceLocation machine,
      boolean factory,
      String name,
      ResourceLocation casing,
      ResourceLocation overlay,
      ResourceLocation model) {
    public String registryPath() {
      return id.contains("/") ? (factory ? "factory_controller/" : "controller/") + machine
          .getNamespace() + "/" + machine.getPath() : (factory ? "factory_controller_" : "controller_") + id;
    }
  }
  public record Machine(ResourceLocation id, String translationKey) {
  }
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
      byte[] bytes = ("registered-machines-v4\n" + MACHINES + ENTRIES).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static boolean restartRequired() {
    try {
      return !MACHINES.equals(scanMachines()) || !ENTRIES.equals(read());
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
        if (machines.putIfAbsent(id, new Machine(id, translation)) != null) {
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
        if (!Set.of("id", "machine", "type", "name", "casing", "overlay", "model").containsAll(source.keySet())) {
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
        var casing = location(text(source, "casing", "mmcece:block/blockcasing_plain"));
        var overlay = location(
            text(
                source,
                "overlay",
                type.equals(
                    "factory") ? "mmcece:block/overlay_factory_controller" : "mmcece:block/overlay_controller"));
        var model = source.has("model") ? location(text(source, "model", null)) : null;
        result.add(new Entry(id, machine, type.equals("factory"), name, casing, overlay, model));
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
        if (roles.add(factory + ":" + machine.id())) {
          String id = (factory ? "factory/" : "ordinary/") + machine.id().getNamespace() + "/" + machine.id().getPath();
          result.add(
              new Entry(
                  id,
                  machine.id(),
                  factory,
                  "",
                  location("mmcece:block/blockcasing_plain"),
                  location(factory ? "mmcece:block/overlay_factory_controller" : "mmcece:block/overlay_controller"),
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
}