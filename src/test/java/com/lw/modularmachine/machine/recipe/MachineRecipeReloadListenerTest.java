package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonParseException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MachineRecipeReloadListenerTest {
  private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mmcece", "reload_probe");

  @Test
  void malformedRecipeJsonIsSkippedWithoutThrowing() {
    var parsed = MachineRecipeReloadListener.parseIfValid(
        ID,
        new ByteArrayInputStream("{\"type\":\"mmcece:machine\",\"outputs\":[".getBytes(StandardCharsets.UTF_8)),
        new HashMap<>());

    assertNull(parsed);
  }

  @Test
  void skippedDeclarationOnlySuppliesAnExplicitMachineId() {
    var owners = new HashMap<ResourceLocation, ResourceLocation>();
    for (var machine : List.of("source", ":source", "test:")) {
      var input = "{\"machine\":\"" + machine + "\"}";
      assertNull(
          MachineRecipeReloadListener
              .parseIfValid(ID, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), owners));
      assertFalse(owners.containsKey(ID));
    }
    var input = "{\"machine\":\"test:source\"}";
    assertNull(
        MachineRecipeReloadListener
            .parseIfValid(ID, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), owners));
    assertEquals(ResourceLocation.parse("test:source"), owners.get(ID));
  }

  @Test
  void unreadableNativeFileKeepsItsOwnerWhileSameIdScriptsChangeMachines() {
    var source = ResourceLocation.parse("test:source");
    var firstScript = ResourceLocation.parse("test:first_script");
    var secondScript = ResourceLocation.parse("test:second_script");
    var machines = Set.of(source, firstScript, secondScript);
    var nativeOwners = new RecipeDeclarationOwners(Set.of(), Map.of());
    nativeOwners.accept(ID, source);
    var omissions = new HashMap<ResourceLocation, Set<ResourceLocation>>();
    var nativeHistory = nativeOwners.resolve(machines, Map.of(), omissions);
    Map<ResourceLocation, ResourceLocation> scriptHistory = Map.of();
    assertTrue(omissions.isEmpty());

    for (var replacement : List.of(firstScript, secondScript)) {
      omissions.clear();
      nativeOwners = new RecipeDeclarationOwners(Set.of(ID), Map.of());
      var scriptOwners = new RecipeDeclarationOwners(Set.of(), Map.of());
      scriptOwners.accept(ID, replacement);
      nativeHistory = nativeOwners.resolve(machines, nativeHistory, omissions);
      scriptHistory = scriptOwners.resolve(machines, scriptHistory, omissions);
      assertEquals(Map.of(ID, Set.of(source)), omissions);
      assertEquals(Map.of(ID, replacement), scriptHistory);
    }

    omissions.clear();
    var deleted = new RecipeDeclarationOwners(Set.of(), Map.of());
    assertTrue(deleted.resolve(machines, nativeHistory, omissions).isEmpty());
    assertTrue(omissions.isEmpty());
  }

  @Test
  void explicitCurrentOwnerSupersedesHistoryAndUnregisteredOwnersCannotAttractCopies() {
    var old = ResourceLocation.parse("test:previous");
    var current = ResourceLocation.parse("test:current");
    var owners = new RecipeDeclarationOwners(Set.of(ID), Map.of(ID, current));
    var omissions = new HashMap<ResourceLocation, Set<ResourceLocation>>();
    var history = owners.resolve(Set.of(old, current), Map.of(ID, old), omissions);
    assertEquals(Map.of(ID, Set.of(current)), omissions);

    owners = new RecipeDeclarationOwners(Set.of(ID), Map.of());
    omissions.clear();
    assertTrue(owners.resolve(Set.of(old), history, omissions).isEmpty());
    assertTrue(omissions.isEmpty());
  }

  @Test
  void validRecipeJsonStillPassesStructuralValidation() {
    var source = """
        {
          "type": "mmcece:machine",
          "machine": "mmcece:ae",
          "duration": 20,
          "inputs": [{"stack":{"id":"minecraft:dirt"},"amount":1}],
          "outputs": [{"stack":{"id":"minecraft:clay_ball"},"amount":1}]
        }
        """;
    var parsed = MachineRecipeReloadListener
        .parseIfValid(ID, new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), new HashMap<>());

    assertNotNull(parsed);
    assertEquals(MachineRecipeJson.TYPE, parsed.getAsJsonObject().get("type").getAsString());
  }

  @Test
  void excessRecipeFilesRejectReloadBeforeThePreparationBarrier() {
    var files = new HashMap<ResourceLocation, Resource>();
    var unopened = new Resource(null, () -> {
      throw new AssertionError("The file limit must be checked before opening any recipe");
    });
    for (int index = 0; index <= MachineRecipeReloadListener.MAX_FILES; index++) {
      files.put(ResourceLocation.fromNamespaceAndPath("mmcece", "recipe/mmcece/probe_" + index + ".json"), unopened);
    }
    var barrier = new Barrier();
    var task = reload(barrier, new MemoryResources(() -> files));

    var failure = assertThrows(CompletionException.class, task::join);
    assertFalse(barrier.reached);
    assertInstanceOf(JsonParseException.class, failure.getCause());
    assertTrue(failure.getCause().getMessage().contains("Too many native MMCE recipe files"));
  }

  @Test
  void resourceDiscoveryFailureRejectsReloadBeforeThePreparationBarrier() {
    var barrier = new Barrier();
    var task = reload(barrier, new MemoryResources(() -> {
      throw new IllegalStateException("Resource discovery failed");
    }));

    var failure = assertThrows(CompletionException.class, task::join);
    assertFalse(barrier.reached);
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals("Resource discovery failed", failure.getCause().getMessage());
  }

  private static CompletableFuture<Void> reload(Barrier barrier, ResourceManager resources) {
    // Preparation failures must never need the prospective recipe manager.
    return new MachineRecipeReloadListener(null, new MachineRecipeReloadListener.SourceCache())
        .reload(barrier, resources, InactiveProfiler.INSTANCE, InactiveProfiler.INSTANCE, Runnable::run, Runnable::run);
  }

  private static final class Barrier implements PreparableReloadListener.PreparationBarrier {
    private boolean reached;

    @Override
    public <T> CompletableFuture<T> wait(T result) {
      reached = true;
      return CompletableFuture.completedFuture(result);
    }
  }

  private record MemoryResources(Supplier<Map<ResourceLocation, Resource>> files) implements ResourceManager {
    @Override
    public Set<String> getNamespaces() {
      return Set.of("mmcece");
    }

    @Override
    public Optional<Resource> getResource(ResourceLocation location) {
      return Optional.empty();
    }

    @Override
    public List<Resource> getResourceStack(ResourceLocation location) {
      return List.of();
    }

    @Override
    public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
      return files.get();
    }

    @Override
    public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
      return Map.of();
    }

    @Override
    public Stream<PackResources> listPacks() {
      return Stream.empty();
    }
  }
}
