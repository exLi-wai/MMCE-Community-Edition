package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterContext;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.MachineDefinitionParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

}
