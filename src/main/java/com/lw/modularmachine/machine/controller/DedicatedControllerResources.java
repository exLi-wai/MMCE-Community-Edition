package com.lw.modularmachine.machine.controller;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.appearance.ControllerAppearanceState;
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
 * Internal generator for registered-controller resources; user resource packs
 * can override these defaults.
 */
public final class DedicatedControllerResources {
  private static Path generated;
  private DedicatedControllerResources() {
  }
  public static synchronized void register(AddPackFindersEvent event) {
    if (DedicatedControllerCatalog.entries().isEmpty() && DedicatedControllerCatalog.machines().isEmpty()) {
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
          String key = "facing=" + pose.front().getSerializedName() + ",roll=" + pose.roll();
          write(
              root.resolve("assets/mmcece/models/block/dedicated/" + entry.id() + "/" + pose.key() + ".json"),
              model(entry, entry.baseAppearance(), pose.key()));
          for (var appearance : ControllerAppearanceState.values()) {
            String modelPath = modelPath(entry, appearance, pose.key());
            var variant = new JsonObject();
            variant.addProperty("model", "mmcece:block/" + modelPath);
            // Real formation and visibility remain independent of the model choice.
            variants.add(key + ",appearance=" + appearance.getSerializedName(), variant);
            var resources = entry.appearance(appearance);
            if (!resources.equals(entry.baseAppearance())) {
              write(
                  root.resolve("assets/mmcece/models/block/" + modelPath + ".json"),
                  model(entry, resources, pose.key()));
            }
          }
        }
        var state = new JsonObject();
        state.add("variants", variants);
        write(root.resolve("assets/mmcece/blockstates/" + path + ".json"), state);
        var item = new JsonObject();
        item.addProperty("parent", "mmcece:block/" + modelPath(entry, ControllerAppearanceState.UNFORMED, "north_0"));
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

  private static String modelPath(DedicatedControllerCatalog.Entry entry, ControllerAppearanceState state, String pose) {
    if (entry.appearance(state).equals(entry.baseAppearance())) {
      return "dedicated/" + entry.id() + "/" + pose;
    }

    return "dedicated_appearance/" + state.getSerializedName() + "/" + entry.id() + "/" + pose;
  }

  private static JsonObject model(DedicatedControllerCatalog.Entry entry, DedicatedControllerCatalog.Appearance appearance, String pose)
      throws IOException {
    if (appearance.model() != null) {
      // Custom models must supply all 24 keys: <model prefix>/<front>_<roll>.
      var model = new JsonObject();
      model.addProperty("parent", appearance.model() + "/" + pose);

      return model;
    }
    var model = read("models/block/" + (entry.factory() ? "factory/" : "controller/") + pose + ".json");
    var textures = new JsonObject();
    textures.addProperty("casing", appearance.casing().toString());
    textures.addProperty("overlay", appearance.overlay().toString());
    model.add("textures", textures);
    tintCasingFaces(model);

    return model;
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
