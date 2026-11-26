package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterContext;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.MachineDefinitionParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MachineRecipeCopiesTest {
  private static final ResourceLocation A = ResourceLocation.parse("test:a");
  private static final ResourceLocation B = ResourceLocation.parse("test:b");
  private static final ResourceLocation C = ResourceLocation.parse("test:c");
  private static final RecipeAdapterTags NO_TAGS = new RecipeAdapterTags(Map.of());

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
  void copyAllPropagatesOmittedIdsThroughSpecificCopies() {
    var skipped = ResourceLocation.parse("test:skipped");
    var first = from("first", A);
    var firstContext = new RecipeAdapterContext(B, first, RegistryAccess.EMPTY, List.of(), NO_TAGS);
    var skippedCopy = firstContext.importedId(skipped, "");
    var second = new RecipeAdapterImport("second", B, List.of(skippedCopy), false, 0, 0, 0);
    var secondContext = new RecipeAdapterContext(C, second, RegistryAccess.EMPTY, List.of(), NO_TAGS);
    var finalTarget = ResourceLocation.parse("test:d");
    var skippedSecondCopy = secondContext.importedId(skippedCopy, "");
    var machines = Map.of(A, machine(A, "[]"), B, machine(B, """
        [{"id":"first","adapter":"test:a"}]
        """), C, machine(C, """
        [{"id":"second","adapter":"test:b","recipes":["%s"]}]
        """.formatted(skippedCopy)), finalTarget, machine(finalTarget, """
        [{"id":"third","adapter":"test:c","recipes":["%s"]}]
        """.formatted(skippedSecondCopy)));

    var generated = RecipeAdapterImports.generate(
        List.of(),
        machines,
        RegistryAccess.EMPTY,
        NO_TAGS,
        Map.of(),
        Set.of(skipped),
        Map.of(skipped, Set.of(A)));

    assertTrue(generated.recipes().isEmpty());
  }

  @Test
  void skippedFanOutDoesNotConsumeGeneratedRecipeCapacity() {
    var omitted = new LinkedHashMap<ResourceLocation, Set<ResourceLocation>>();
    for (int index = 0; index < 3300; index++) {
      omitted.put(ResourceLocation.parse("test:disabled_" + index), Set.of(A));
    }
    var machines = new LinkedHashMap<ResourceLocation, MachineDefinition>();
    machines.put(A, machine(A, "[]"));
    var first = from("all_a", A);
    var sourceId = ResourceLocation.parse("test:disabled_0");
    for (int index = 0; index < 5; index++) {
      var target = ResourceLocation.parse("test:target_" + index);
      machines.put(target, machine(target, """
          [{"id":"all_a","adapter":"test:a"}]
          """));
      var derived = new RecipeAdapterContext(target, first, RegistryAccess.EMPTY, List.of(), NO_TAGS)
          .importedId(sourceId, "");
      var consumer = ResourceLocation.parse("test:consumer_" + index);
      machines.put(consumer, machine(consumer, """
          [{"id":"specific","adapter":"%s","recipes":["%s"]}]
          """.formatted(target, derived)));
    }

    var generated = RecipeAdapterImports
        .generate(List.of(), machines, RegistryAccess.EMPTY, NO_TAGS, Map.of(), omitted.keySet(), omitted);

    assertTrue(generated.recipes().isEmpty());
  }

  @Test
  void nestedCopyAllBoundsRetainedOmissionsAcrossTheGeneration() {
    var machines = new LinkedHashMap<ResourceLocation, MachineDefinition>();
    machines.put(A, machine(A, "[]"));
    var skipped = ResourceLocation.parse("test:disabled");
    var source = A;
    var selected = skipped;
    for (int level = 1; level <= 15; level++) {
      var target = ResourceLocation.parse("test:level_" + level);
      machines.put(target, machine(target, """
          [{"id":"left","adapter":"%1$s"},{"id":"right","adapter":"%1$s"}]
          """.formatted(source)));
      selected = new RecipeAdapterContext(target, from("left", source), RegistryAccess.EMPTY, List.of(), NO_TAGS)
          .importedId(selected, "");
      source = target;
    }
    machines.put(C, machine(C, """
        [{"id":"leaf","adapter":"%s","recipes":["%s"]}]
        """.formatted(source, selected)));

    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> RecipeAdapterImports.generate(
            List.of(),
            machines,
            RegistryAccess.EMPTY,
            NO_TAGS,
            Map.of(),
            Set.of(skipped),
            Map.of(skipped, Set.of(A))));
    assertTrue(failure.getMessage().contains("Too many retained omitted machine recipe copies"));
  }

  @Test
  void explicitCopySkipsUnreadableSourceWithoutAcceptingItsDerivedIdFromAnotherMachine() {
    var skipped = ResourceLocation.parse("test:unreadable");
    var first = new RecipeAdapterImport("first", A, List.of(skipped), false, 0, 0, 0);
    var skippedCopy = new RecipeAdapterContext(B, first, RegistryAccess.EMPTY, List.of(), NO_TAGS)
        .importedId(skipped, "");
    var source = machine(A, "[]");
    var intermediate = machine(B, """
        [{"id":"first","adapter":"test:a","recipes":["%s"]}]
        """.formatted(skipped));
    var validTarget = machine(C, """
        [{"id":"second","adapter":"test:b","recipes":["%s"]}]
        """.formatted(skippedCopy));
    var wrongTarget = machine(C, """
        [{"id":"second","adapter":"test:a","recipes":["%s"]}]
        """.formatted(skippedCopy));

    assertTrue(
        RecipeAdapterImports.generate(
            List.of(),
            Map.of(A, source, B, intermediate, C, validTarget),
            RegistryAccess.EMPTY,
            NO_TAGS,
            Map.of(),
            Set.of(skipped),
            Map.of()).recipes().isEmpty());
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> RecipeAdapterImports.generate(
            List.of(),
            Map.of(A, source, B, intermediate, C, wrongTarget),
            RegistryAccess.EMPTY,
            NO_TAGS,
            Map.of(),
            Set.of(skipped),
            Map.of()));
    assertTrue(failure.getMessage().contains("Missing machine recipe adapter source " + skippedCopy));
  }

  @Test
  void anAttributedInvalidScriptDoesNotHideAnUnattributedNativeDeclarationAtTheSameId() {
    var skipped = ResourceLocation.parse("test:mmcece/unreadable");
    var machines = Map.of(A, machine(A, "[]"), B, machine(B, "[]"), C, machine(C, """
        [{"id":"from_a","adapter":"test:a","recipes":["test:mmcece/unreadable"]}]
        """));
    var knownScriptOwner = Map.of(skipped, Set.of(B));
    var omitted = Set.of(skipped);
    assertTrue(
        RecipeAdapterImports.generate(
            List.of(),
            machines,
            RegistryAccess.EMPTY,
            NO_TAGS,
            Map.of(),
            omitted,
            knownScriptOwner,
            Set.of(skipped)).recipes().isEmpty());

    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> RecipeAdapterImports.generate(
            List.of(),
            machines,
            RegistryAccess.EMPTY,
            NO_TAGS,
            Map.of(),
            omitted,
            knownScriptOwner,
            Set.of()));
    assertTrue(failure.getMessage().contains("Missing machine recipe adapter source " + skipped));
  }

  private static MachineDefinition machine(ResourceLocation id, String adapters) {
    var root = JsonParser.parseString("""
        {"parts":[{"offset":[1,0,0],"block":"energy_input"}],"recipe_adapters":%s}
        """.formatted(adapters)).getAsJsonObject();
    return MachineDefinitionParser.parse(id, root, new StateOrientationAdapters.RegistrySnapshot(Map.of()));
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
    var copied = MachineRecipeCopies.copy(source, A, settings, RegistryAccess.EMPTY);
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
  void copiedFluidRowsKeepExplicitEmptyAndInheritOnlyWhenOmitted() {
    var source = JsonParser.parseString("""
        {"machine":"test:b","duration":10,"selectors":{"fluid_input":"source_in","fluid_output":"source_out"},
         "fluid_inputs":[{"id":"minecraft:water","amount":1},{"id":"minecraft:lava","amount":2,"selector":"hot"}],
         "fluid_outputs":[{"id":"minecraft:water","amount":1,"selector":""}]}
        """).getAsJsonObject();
    var original = source.deepCopy();
    var unmarked = new RecipeAdapterCustomization.FluidResource("""
        {"id":"minecraft:water","amount":3,"selector":""}
        """);
    var inherited = new RecipeAdapterCustomization.FluidResource("""
        {"id":"minecraft:water","amount":4}
        """);
    var customization = new RecipeAdapterCustomization(
        List.of(),
        List.of(),
        List.of(unmarked),
        List.of(inherited),
        Optional.empty(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Optional.empty(),
        Optional.empty());
    var copied = MachineRecipeCopies.copy(
        source,
        A,
        new RecipeAdapterImport("copy", B, List.of(), true, 0, 0, 0, customization),
        RegistryAccess.EMPTY);
    assertEquals(original, source);
    assertEquals(original.get("selectors"), copied.get("selectors"));
    var inputs = copied.getAsJsonArray("fluid_inputs");
    assertEquals(3, inputs.size());
    assertFalse(inputs.get(0).getAsJsonObject().has("selector"));
    assertEquals("hot", inputs.get(1).getAsJsonObject().get("selector").getAsString());
    assertEquals("", inputs.get(2).getAsJsonObject().get("selector").getAsString());
    var outputs = copied.getAsJsonArray("fluid_outputs");
    assertEquals(2, outputs.size());
    assertEquals("", outputs.get(0).getAsJsonObject().get("selector").getAsString());
    assertFalse(outputs.get(1).getAsJsonObject().has("selector"));
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
    var copied = MachineRecipeCopies.copy(source, A, settings, RegistryAccess.EMPTY);
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
    var copied = MachineRecipeCopies.copy(source, A, from("copy", B), RegistryAccess.EMPTY);
    assertEquals(9, copied.get("priority").getAsInt());
    var explicit = new RecipeAdapterImport("copy", B, List.of(), true, 0, 0, 0, RecipeAdapterCustomization.NONE, true);
    assertEquals(0, MachineRecipeCopies.copy(source, A, explicit, RegistryAccess.EMPTY).get("priority").getAsInt());
  }
}
