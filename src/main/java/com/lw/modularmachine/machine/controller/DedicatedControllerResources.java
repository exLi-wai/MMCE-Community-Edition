package com.lw.modularmachine.machine.controller;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Generates only registered-controller resources; user resource packs can
 * override these defaults.
 */
public final class DedicatedControllerResources {
  private static Path generated;
  private DedicatedControllerResources() {
  }
  public static synchronized void register(AddPackFindersEvent event) {
    if (DedicatedControllerCatalog.entries().isEmpty()) {
      return;
    }
    if (generated == null) {
      generated = generate();
    }
    var pack = Pack.readMetaAndCreate(
        new PackLocationInfo(
            "mmcece/dedicated_controllers",
            Component.literal("MMCE dedicated controllers"),
            PackSource.BUILT_IN,
            Optional.empty()),
        new PathPackResources.PathResourcesSupplier(generated),
        event.getPackType(),
        new PackSelectionConfig(true, Pack.Position.BOTTOM, false));
    if (pack == null) {
      throw new IllegalStateException("Generated controller pack cannot be loaded");
    }
    event.addRepositorySource(consumer -> consumer.accept(pack));
  }
  private static Path generate() {
    String fingerprint = DedicatedControllerCatalog.resourceFingerprint();
    Path root = FMLPaths.GAMEDIR.get().resolve("generated/mmcece/controllers/" + fingerprint).toAbsolutePath()
        .normalize();
    try {
      var metadata = new JsonObject();
      var pack = new JsonObject();
      pack.addProperty("pack_format", 34);
      var supported = new JsonObject();
      supported.addProperty("min_inclusive", 34);
      supported.addProperty("max_inclusive", 48);
      pack.add("supported_formats", supported);
      pack.addProperty("description", "MMCE startup dedicated controller resources");
      metadata.add("pack", pack);
      write(root.resolve("pack.mcmeta"), metadata);
      var mineable = new JsonArray();
      var names = new JsonObject();
      for (var machine : DedicatedControllerCatalog.machines()) {
        var blueprint = new JsonObject();
        blueprint.addProperty("parent", "minecraft:item/generated");
        var texture = new JsonObject();
        texture.addProperty("layer0", "mmcece:item/blueprint");
        blueprint.add("textures", texture);
        write(
            root.resolve(
                "assets/mmcece/models/item/blueprint/" + machine.id().getNamespace() + "/" + machine.id()
                    .getPath() + ".json"),
            blueprint);
      }
      for (var entry : DedicatedControllerCatalog.entries()) {
        String path = entry.registryPath();
        String id = "mmcece:" + path;
        var variants = new JsonObject();
        for (var pose : MachineOrientation.all()) {
          var variant = new JsonObject();
          variant.addProperty("model", "mmcece:block/dedicated/" + entry.id() + "/" + pose.key());
          String key = "facing=" + pose.front().getSerializedName() + ",roll=" + pose.roll();
          variants.add(key + ",formed=false", variant.deepCopy());
          variants.add(key + ",formed=true", variant);
          var template = read("models/block/" + (entry.factory() ? "factory/" : "controller/") + pose.key() + ".json");
          if (entry.model() == null) {
            var textures = new JsonObject();
            textures.addProperty("casing", entry.casing().toString());
            textures.addProperty("overlay", entry.overlay().toString());
            template.add("textures", textures);
            tintCasingFaces(template);
          } else {
            // Custom models must supply all 24 keys: <model prefix>/<front>_<roll>.
            template = new JsonObject();
            template.addProperty("parent", entry.model() + "/" + pose.key());
          }
          write(
              root.resolve("assets/mmcece/models/block/dedicated/" + entry.id() + "/" + pose.key() + ".json"),
              template);
        }
        var state = new JsonObject();
        state.add("variants", variants);
        write(root.resolve("assets/mmcece/blockstates/" + path + ".json"), state);
        var item = new JsonObject();
        item.addProperty("parent", "mmcece:block/dedicated/" + entry.id() + "/north_0");
        write(root.resolve("assets/mmcece/models/item/" + path + ".json"), item);
        var loot = read("/data/mmcece/loot_table/blocks/machine_controller.json");
        loot.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject()
            .addProperty("name", id);
        write(root.resolve("data/mmcece/loot_table/blocks/" + path + ".json"), loot);
        mineable.add(id);
        if (!entry.name().isEmpty()) {
          names.addProperty("block.mmcece." + path, entry.name());
        }
      }
      var tag = new JsonObject();
      tag.add("values", mineable);
      write(root.resolve("data/minecraft/tags/block/mineable/pickaxe.json"), tag);
      write(root.resolve("data/minecraft/tags/block/needs_iron_tool.json"), tag);
      write(root.resolve("assets/mmcece/lang/en_us.json"), names);
      write(root.resolve("assets/mmcece/lang/zh_cn.json"), names);
      return root;
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot generate dedicated controller pack", failure);
    }
  }
  private static JsonObject read(String relative) throws IOException {
    String path = relative.startsWith("/") ? relative : "/assets/mmcece/" + relative;
    try (var input = DedicatedControllerResources.class.getResourceAsStream(path)) {
      if (input == null) {
        throw new IOException("Missing controller resource template " + path);
      }
      return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
  }

  private static void tintCasingFaces(JsonObject model) {
    var elements = model.getAsJsonArray("elements");
    if (elements == null) {
      return;
    }
    for (var element : elements) {
      if (!element.isJsonObject() || !element.getAsJsonObject().has("faces")) {
        continue;
      }
      var faces = element.getAsJsonObject().getAsJsonObject("faces");
      for (var face : faces.entrySet()) {
        var faceData = face.getValue();
        if (faceData.isJsonObject()
            && faceData.getAsJsonObject().has("texture")
            && "#casing".equals(faceData.getAsJsonObject().get("texture").getAsString())) {
          faceData.getAsJsonObject().addProperty("tintindex", 0);
        }
      }
    }
  }

  private static void write(Path path, JsonObject value) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(value), StandardCharsets.UTF_8);
  }
}
