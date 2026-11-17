package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MachineRecipeCopiesTest {
  private static final ResourceLocation A = ResourceLocation.parse("test:a");
  private static final ResourceLocation B = ResourceLocation.parse("test:b");
  private static final ResourceLocation C = ResourceLocation.parse("test:c");

  private static RecipeAdapterImport from(String id, ResourceLocation source) {
    return new RecipeAdapterImport(id, source, List.of(), true, 0, 0, 0);
  }

  private static RecipeAdapterCustomization withModifier(RecipeModifier modifier) {
    return new RecipeAdapterCustomization(
        List.of(modifier),
        List.of(),
        List.of(),
        List.of(),
        Optional.empty(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void dependencyOrderIncludesUpstreamImportsOnceAndKeepsRegisteredAdaptersFirst() {
    var imports = Map.of(
        A,
        List.of(from("first", B), from("second", B)),
        B,
        List.of(from("parent", C)),
        C,
        List.of(from("furnace", ResourceLocation.parse("minecraft:furnace"))));
    assertEquals(List.of(C, B, A), RecipeAdapterDependencies.order(imports));
  }

  @Test
  void cyclesAndMissingMachinesRejectTheWholeProspectiveSet() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeAdapterDependencies.order(Map.of(A, List.of(from("from_b", B)), B, List.of(from("from_a", A)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeAdapterDependencies.order(Map.of(A, List.of(from("self", A)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeAdapterDependencies.order(Map.of(A, List.of(from("missing", B)))));
  }

  @Test
  void deepMachineChainsDoNotUseRecursiveJavaCalls() {
    var imports = new LinkedHashMap<ResourceLocation, List<RecipeAdapterImport>>();
    for (int index = 0; index < 3000; index++) {
      var machine = ResourceLocation.parse("test:machine_" + index);
      imports.put(
          machine,
          index == 0 ? List.of() : List.of(from("parent", ResourceLocation.parse("test:machine_" + (index - 1)))));
    }
    var order = RecipeAdapterDependencies.order(imports);
    assertEquals(3000, order.size());
    assertEquals(ResourceLocation.parse("test:machine_0"), order.getFirst());
    assertEquals(ResourceLocation.parse("test:machine_2999"), order.getLast());
  }

  @Test
  void copiesOriginalDeclarationsAndAppliesEachLocalModifierOnce() {
    var source = JsonParser.parseString("""
        {"type":"mmcece:machine","machine":"test:b","duration":20,"energy_per_tick":10,
         "parallelized":true,"failure_action":"still",
         "inputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":2,"chance":0.5}],
         "outputs":[{"stack":{"id":"minecraft:gold_ingot"},"amount":1,"chance":0.25}],
         "modifiers":[{"target":"item","io":"input","operation":"multiply","value":2}],
         "item_payments":{"fuels":[{"burn_time":100,"chance":0.5,"selector":"fuel"}]},
         "item_callbacks":[{"io":"input","row":0,"checker":"kubejs:check"}],
         "neoforge:conditions":[]}
        """).getAsJsonObject();
    var initial = source.deepCopy();
    var customization = withModifier(
        new RecipeModifier(
            RecipeModifier.Target.ITEM,
            RecipeModifier.Io.INPUT,
            RecipeModifier.Operation.MULTIPLY,
            3,
            false));
    var settings = new RecipeAdapterImport("copy", B, List.of(), true, 30, 5, 7, customization);
    var copied = MachineRecipeCopies.copy(source, A, settings);
    assertEquals(initial, source);
    assertEquals("test:a", copied.get("machine").getAsString());
    assertEquals(30, copied.get("duration").getAsInt());
    assertEquals(15, copied.get("energy_per_tick").getAsLong());
    assertEquals(2, copied.getAsJsonArray("modifiers").size());
    assertEquals(initial.get("item_payments"), copied.get("item_payments"));
    assertEquals(initial.get("item_callbacks"), copied.get("item_callbacks"));
    assertFalse(copied.has("neoforge:conditions"));
    var normalized = MachineRecipeJson.normalize(copied);
    assertEquals(12, normalized.getAsJsonArray("inputs").get(0).getAsJsonObject().get("amount").getAsLong());
    assertEquals(0.5, normalized.getAsJsonArray("inputs").get(0).getAsJsonObject().get("chance").getAsDouble());
    assertEquals(
        600,
        normalized.getAsJsonObject("item_payments").getAsJsonArray("fuels").get(0).getAsJsonObject().get("burn_time")
            .getAsLong());
  }

  @Test
  void resourceOnlyMachineCopiesAcceptCustomizationWithoutItemArrays() {
    var source = JsonParser.parseString("""
        {"machine":"test:b","duration":20,"energy_per_tick":10,
         "fluid_outputs":[{"id":"minecraft:water","amount":1000}]}
        """).getAsJsonObject();
    var customization = withModifier(
        new RecipeModifier(
            RecipeModifier.Target.FLUID,
            RecipeModifier.Io.OUTPUT,
            RecipeModifier.Operation.MULTIPLY,
            2,
            false));
    var settings = new RecipeAdapterImport("copy", B, List.of(), true, 0, 0, 0, customization);
    var copied = MachineRecipeCopies.copy(source, A, settings);
    assertTrue(copied.getAsJsonArray("inputs").isEmpty());
    assertTrue(copied.getAsJsonArray("outputs").isEmpty());
    assertEquals(
        2000,
        MachineRecipeJson.normalize(copied).getAsJsonArray("fluid_outputs").get(0).getAsJsonObject().get("amount")
            .getAsInt());
    assertFalse(source.has("inputs"));
    assertFalse(source.has("outputs"));
  }

  @Test
  void defaultCopyKeepsSourcePriorityAndExplicitZeroCanClearIt() {
    var source = JsonParser.parseString("{\"machine\":\"test:b\",\"duration\":20,\"priority\":9}").getAsJsonObject();
    var copied = MachineRecipeCopies.copy(source, A, from("copy", B));
    assertEquals(9, copied.get("priority").getAsInt());
    var explicit = new RecipeAdapterImport("copy", B, List.of(), true, 0, 0, 0, RecipeAdapterCustomization.NONE, true);
    assertEquals(0, MachineRecipeCopies.copy(source, A, explicit).get("priority").getAsInt());
  }
}
