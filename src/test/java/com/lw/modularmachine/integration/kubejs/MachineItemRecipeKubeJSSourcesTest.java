package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.RecipeScriptCallbacks;
import com.lw.modularmachine.machine.recipe.RecipeScriptSources;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MachineItemRecipeKubeJSSourcesTest {
  private static final String RECIPE = """
      {"type":"mmcece:machine","machine":"mmcece:ae","duration":100,
       "inputs":[{"stack":{"id":"minecraft:iron_ingot","count":1},"amount":1}],
       "outputs":[{"stack":{"id":"minecraft:gold_ingot"},"amount":1}]}
      """;
  private final RecipeManager manager = new RecipeManager(RegistryAccess.EMPTY);

  @AfterEach
  void clear() {
    RecipeScriptSources.clear();
    RecipeScriptCallbacks.reset();
  }

  @Test
  void seesRowsAddedAfterTheCaptureHook() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    MachineItemRecipeKubeJSSources.capture(manager, live, ignored -> null);
    live.put(ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot"), JsonParser.parseString(RECIPE));

    var snapshot = RecipeScriptSources.snapshot(manager);
    assertEquals(1, snapshot.declarations().size());
    assertTrue(snapshot.declarations().containsKey(ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot")));
    RecipeScriptSources.clear(manager);
    assertTrue(RecipeScriptSources.snapshot(manager).declarations().isEmpty());
  }

  @Test
  void finishingAnotherManagerKeepsTheCurrentCaptureOpen() {
    var older = new RecipeManager(RegistryAccess.EMPTY);
    var uncaptured = new RecipeManager(RegistryAccess.EMPTY);
    Map<ResourceLocation, JsonElement> olderLive = new LinkedHashMap<>();
    Map<ResourceLocation, JsonElement> currentLive = new LinkedHashMap<>();
    var olderId = ResourceLocation.parse("kubejs:older");
    var currentId = ResourceLocation.parse("kubejs:current");
    MachineItemRecipeKubeJSSources.capture(older, olderLive, ignored -> null);
    olderLive.put(olderId, JsonParser.parseString(RECIPE));
    MachineItemRecipeKubeJSSources.capture(manager, currentLive, ignored -> null);
    currentLive.put(currentId, JsonParser.parseString(RECIPE));

    assertEquals(Set.of(olderId), RecipeScriptSources.snapshot(older).declarations().keySet());
    assertTrue(RecipeScriptSources.snapshot(uncaptured).declarations().isEmpty());
    RecipeScriptSources.clear(uncaptured);
    RecipeScriptSources.clear(older);
    assertTrue(RecipeScriptSources.snapshot(older).declarations().isEmpty());
    assertEquals(Set.of(currentId), RecipeScriptSources.snapshot(manager).declarations().keySet());
    var callbacks = new RecipeScriptCallbacks.Bindings(
        Map.of(currentId, (context, item) -> true),
        Map.of(),
        Map.of(),
        List.of(),
        List.of());
    var source = currentLive.get(currentId).getAsJsonObject();
    assertDoesNotThrow(() -> RecipeScriptCallbacks.capture(currentId, source, callbacks));

    RecipeScriptSources.clear(manager);
    assertThrows(IllegalStateException.class, () -> RecipeScriptCallbacks.capture(currentId, source, callbacks));
  }

  @Test
  void normalizesTheKubeJsPrototypeCount() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    MachineItemRecipeKubeJSSources.capture(manager, live, ignored -> null);
    live.put(ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot"), JsonParser.parseString(RECIPE));

    var row = RecipeScriptSources.snapshot(manager).declarations().values().iterator().next().getAsJsonObject()
        .getAsJsonArray("inputs").get(0).getAsJsonObject();
    assertFalse(row.getAsJsonObject("stack").has("count"));
    assertEquals(1, row.get("amount").getAsInt());
  }

  @Test
  void recognizesScriptCorrectionsBeforeNormalizingThePrototypeCount() {
    var unchangedId = ResourceLocation.parse("mmcece:mmcece/unchanged");
    var editedId = ResourceLocation.parse("mmcece:mmcece/edited");
    var replacedId = ResourceLocation.parse("mmcece:mmcece/replaced");
    var nativeSource = JsonParser.parseString(RECIPE).getAsJsonObject();
    assertThrows(JsonParseException.class, () -> MachineRecipeJson.validate(nativeSource));
    var edited = nativeSource.deepCopy();
    var replacement = nativeSource.deepCopy();
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    live.put(unchangedId, nativeSource);
    live.put(editedId, edited);
    live.put(replacedId, nativeSource);
    var replacements = new LinkedHashMap<ResourceLocation, MachineItemRecipeKubeJSSources.Replacement>();
    MachineItemRecipeKubeJSSources.capture(manager, live, replacements::get);

    // An outer copy remains native, but KubeJS may also explicitly replace it with
    // an equal declaration.
    live.put(unchangedId, nativeSource.deepCopy());
    edited.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("stack").remove("count");
    replacements.putAll(MachineItemRecipeKubeJSSources.machineReplacements(live, Map.of(replacedId, replacement)));
    live.put(replacedId, replacement);

    var snapshot = RecipeScriptSources.snapshot(manager);
    assertEquals(Map.of(editedId, edited, replacedId, edited), snapshot.declarations());
    assertEquals(Set.of(replacedId), snapshot.correctedStackPrototypes());
    assertTrue(snapshot.skippedIds().isEmpty());
    assertDoesNotThrow(() -> MachineRecipeJson.validate(snapshot.declarations().get(replacedId)));
  }

  @Test
  void removedOriginalsAndMetadataStayOutWhileAnExplicitReadditionWinsItsRemovedSlot() {
    var deleted = ResourceLocation.parse("test:mmcece/gone");
    var readded = ResourceLocation.parse("test:mmcece/readded");
    var converted = ResourceLocation.parse("test:mmcece/ordinary");
    var metadata = ResourceLocation.parse("kubejs:_meta");
    var source = JsonParser.parseString(RECIPE);
    var live = new LinkedHashMap<ResourceLocation, JsonElement>(
        Map.of(deleted, source, readded, source, converted, source));
    var additions = new LinkedHashMap<ResourceLocation, MachineItemRecipeKubeJSSources.Replacement>();
    MachineItemRecipeKubeJSSources.capture(manager, live, additions::get);
    live.clear();
    live.put(converted, JsonParser.parseString("{\"type\":\"minecraft:smelting\"}"));
    var added = Map.of(readded, source.deepCopy(), metadata, source.deepCopy());
    additions.putAll(MachineItemRecipeKubeJSSources.machineReplacements(live, added));
    live.putAll(added);

    var snapshot = RecipeScriptSources.snapshot(manager);
    assertEquals(Set.of(deleted, readded, converted), snapshot.removedNativeIds());
    assertEquals(Set.of(readded), snapshot.declarations().keySet());
    assertEquals(Set.of(readded), snapshot.correctedStackPrototypes());
    assertTrue(snapshot.ordinaryFallbacks().isEmpty());
    assertTrue(snapshot.skippedIds().isEmpty());
  }

  @Test
  void acceptsAnyNamespaceButExcludesUnchangedOriginalsAndOtherTypes() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    var original = ResourceLocation.fromNamespaceAndPath("mmcece", "mmcece/original");
    live.put(original, JsonParser.parseString(RECIPE));
    MachineItemRecipeKubeJSSources.capture(manager, live, ignored -> null);
    var added = ResourceLocation.fromNamespaceAndPath("other", "gold_ingot");
    live.put(added, JsonParser.parseString(RECIPE));
    live.put(
        ResourceLocation.fromNamespaceAndPath("kubejs", "not_machine"),
        JsonParser.parseString("{\"type\":\"minecraft:crafting_shapeless\"}"));

    var snapshot = RecipeScriptSources.snapshot(manager);
    assertEquals(1, snapshot.declarations().size());
    assertTrue(snapshot.declarations().containsKey(added));
    assertFalse(snapshot.declarations().containsKey(original));
    assertTrue(snapshot.skippedIds().isEmpty());
    assertTrue(
        live.get(original).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("stack")
            .has("count"));
  }

  @Test
  void stripsTheKubeJsInternalMarker() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    MachineItemRecipeKubeJSSources.capture(manager, live, ignored -> null);
    live.put(ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot"), JsonParser.parseString("""
        {"type":"mmcece:machine","machine":"mmcece:ae","duration":100,
         "_kubejs_changed_marker":{"source":"main.js"},
         "inputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":1}],
         "outputs":[{"stack":{"id":"minecraft:gold_ingot"},"amount":1}]}
        """));

    var root = RecipeScriptSources.snapshot(manager).declarations().values().iterator().next().getAsJsonObject();
    assertFalse(root.has("_kubejs_changed_marker"));
  }

  @Test
  void skipsAPrototypeCountOtherThanOne() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    MachineItemRecipeKubeJSSources.capture(manager, live, ignored -> null);
    var invalidId = ResourceLocation.fromNamespaceAndPath("kubejs", "ae/invalid");
    var validId = ResourceLocation.fromNamespaceAndPath("kubejs", "ae/gold_ingot");
    live.put(invalidId, JsonParser.parseString("""
        {"type":"mmcece:machine","machine":"mmcece:ae","duration":100,
         "inputs":[{"stack":{"id":"minecraft:iron_ingot","count":2},"amount":1}],
         "outputs":[{"stack":{"id":"minecraft:gold_ingot"},"amount":1}]}
        """));
    live.put(validId, JsonParser.parseString(RECIPE));

    var snapshot = RecipeScriptSources.snapshot(manager);
    assertEquals(1, snapshot.declarations().size());
    assertTrue(snapshot.declarations().containsKey(validId));
    assertEquals(Set.of(invalidId), snapshot.skippedIds());
  }

  @Test
  void invalidReplacementsFallBackOnlyToTheSurvivingEditedRecipesAtTheirFinalIds() {
    var editedId = ResourceLocation.withDefaultNamespace("edited");
    var removedId = ResourceLocation.withDefaultNamespace("removed");
    var oldId = ResourceLocation.withDefaultNamespace("old");
    var renamedId = ResourceLocation.withDefaultNamespace("renamed");
    var original = JsonParser.parseString("""
        {"type":"minecraft:smelting","ingredient":{"item":"minecraft:iron_ore"},
         "result":{"id":"minecraft:iron_ingot"}}
        """);
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    live.put(editedId, original);
    live.put(removedId, original);
    live.put(oldId, original);
    var fallbacks = new LinkedHashMap<ResourceLocation, MachineItemRecipeKubeJSSources.Replacement>();
    MachineItemRecipeKubeJSSources.capture(manager, live, fallbacks::get);
    var edited = original.deepCopy().getAsJsonObject();
    edited.getAsJsonObject("result").addProperty("id", "minecraft:gold_ingot");
    live.clear();
    live.put(editedId, edited);
    live.put(renamedId, original);
    var invalid = JsonParser.parseString(RECIPE).getAsJsonObject();
    invalid.addProperty("duration", 0);
    var additions = Map
        .<ResourceLocation, JsonElement>of(editedId, invalid, removedId, invalid, oldId, invalid, renamedId, invalid);
    fallbacks.putAll(MachineItemRecipeKubeJSSources.machineReplacements(live, additions));
    live.putAll(additions);

    var snapshot = RecipeScriptSources.snapshot(manager);
    assertEquals(additions.keySet(), snapshot.skippedIds());
    assertEquals(ResourceLocation.parse("mmcece:ae"), snapshot.skippedMachines().get(editedId));
    assertEquals(Map.of(editedId, edited, renamedId, original), snapshot.ordinaryFallbacks());
    assertEquals(List.copyOf(live.keySet()), snapshot.recipeOrder());
    assertNotSame(edited, snapshot.ordinaryFallbacks().get(editedId));
  }

  @Test
  void recordsEveryOverflowIdInIdOrderWithoutCountingUnchangedOrOtherRecipes() {
    Map<ResourceLocation, JsonElement> live = new LinkedHashMap<>();
    var source = JsonParser.parseString(RECIPE);
    var unchanged = ResourceLocation.fromNamespaceAndPath("kubejs", "z_native");
    var changed = ResourceLocation.fromNamespaceAndPath("kubejs", "z_changed");
    var overflow = ResourceLocation.fromNamespaceAndPath("kubejs", "z_added");
    var other = ResourceLocation.fromNamespaceAndPath("kubejs", "z_smelting");
    var modified = source.deepCopy().getAsJsonObject();
    modified.addProperty("duration", 200);
    live.put(unchanged, source);
    live.put(changed, source);
    MachineItemRecipeKubeJSSources.capture(manager, live, ignored -> null);
    live.put(changed, modified);
    live.put(overflow, source);
    live.put(other, JsonParser.parseString("{\"type\":\"minecraft:smelting\"}"));
    for (int index = 16_383; index >= 0; index--) {
      live.put(ResourceLocation.fromNamespaceAndPath("kubejs", "recipe_" + index), source);
    }

    var snapshot = RecipeScriptSources.snapshot(manager);

    assertEquals(16_384, snapshot.declarations().size());
    assertTrue(snapshot.declarations().containsKey(ResourceLocation.fromNamespaceAndPath("kubejs", "recipe_0")));
    assertEquals(Set.of(changed, overflow), snapshot.skippedIds());
  }
}
