package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MachineItemRecipeKubeJSSourcesTest {
  private static final String RECIPE = """
      {"type":"mmcece:machine","machine":"mmcece:ae","duration":100,
       "inputs":[{"stack":{"id":"minecraft:iron_ingot","count":1},"amount":1}],
       "outputs":[{"stack":{"id":"minecraft:gold_ingot"},"amount":1}]}
      """;

  @AfterEach
  void clear() {
    MachineItemRecipeKubeJSSources.clear();
  }

  @Test
  void seesRowsAddedAfterTheCaptureHook() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    MachineItemRecipeKubeJSSources.capture(live);
    live.put(ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot"), JsonParser.parseString(RECIPE));

    var snapshot = MachineItemRecipeKubeJSSources.snapshot();
    assertEquals(1, snapshot.size());
    assertTrue(snapshot.containsKey(ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot")));
  }

  @Test
  void normalizesTheKubeJsPrototypeCount() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    MachineItemRecipeKubeJSSources.capture(live);
    live.put(ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot"), JsonParser.parseString(RECIPE));

    var row = MachineItemRecipeKubeJSSources.snapshot().values().iterator().next().getAsJsonObject()
        .getAsJsonArray("inputs").get(0).getAsJsonObject();
    assertFalse(row.getAsJsonObject("stack").has("count"));
    assertEquals(1, row.get("amount").getAsInt());
  }

  @Test
  void ignoresForeignNamespacesTypesAndReadsTheLiveMapOnly() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    MachineItemRecipeKubeJSSources.capture(live);
    live.put(ResourceLocation.fromNamespaceAndPath("other", "ae/gold_ingot"), JsonParser.parseString(RECIPE));
    live.put(
        ResourceLocation.fromNamespaceAndPath("kubejs", "not_machine"),
        JsonParser.parseString("{\"type\":\"minecraft:crafting_shapeless\"}"));

    assertTrue(MachineItemRecipeKubeJSSources.snapshot().isEmpty());
  }

  @Test
  void stripsTheKubeJsInternalMarker() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    MachineItemRecipeKubeJSSources.capture(live);
    live.put(ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot"), JsonParser.parseString("""
        {"type":"mmcece:machine","machine":"mmcece:ae","duration":100,
         "_kubejs_changed_marker":{"source":"main.js"},
         "inputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":1}],
         "outputs":[{"stack":{"id":"minecraft:gold_ingot"},"amount":1}]}
        """));

    var root = MachineItemRecipeKubeJSSources.snapshot().values().iterator().next().getAsJsonObject();
    assertFalse(root.has("_kubejs_changed_marker"));
  }

  @Test
  void rejectsAPrototypeCountOtherThanOne() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    MachineItemRecipeKubeJSSources.capture(live);
    live.put(ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot"), JsonParser.parseString("""
        {"type":"mmcece:machine","machine":"mmcece:ae","duration":100,
         "inputs":[{"stack":{"id":"minecraft:iron_ingot","count":2},"amount":1}],
         "outputs":[{"stack":{"id":"minecraft:gold_ingot"},"amount":1}]}
        """));

    assertThrows(JsonParseException.class, MachineItemRecipeKubeJSSources::snapshot);
  }
}
