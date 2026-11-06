package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.OrientationPolicy;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registry-free format and reload tests; real BlockStates are covered by
 * GameTests.
 */
class MachineDefinitionSchemaTest {
  private static final ResourceLocation ID = id("schema_unit");
  private static final StateOrientationAdapters.RegistrySnapshot EMPTY = new StateOrientationAdapters.RegistrySnapshot(
      Map.of());
  private static final String PORTS = """
      {"parts":[
        {"offset":[1,0,0],"port":"energy_input"},
        {"offset":[-1,0,0],"port":"energy_output"}
      ]}
      """;

  @Test
  void schemaTwoKeepsHorizontalDefaultsAndResourceContracts() {
    JsonObject root = json(PORTS);
    root.getAsJsonArray("parts").get(0).getAsJsonObject()
        .add("field", json("{\"name\":\"energy_stored\",\"min\":9,\"max\":20}"));
    root.add("operation", json("{\"type\":\"energy_transfer\",\"energy_per_tick\":128}"));
    var definition = MachineDefinition.parse(ID, root, EMPTY);
    assertSame(OrientationPolicy.HORIZONTAL, definition.orientationPolicy());
    assertEquals(0x1111, definition.allowedOrientationMask());
    assertEquals("offset:1,0,0", definition.parts().getFirst().id());
    assertEquals(128, definition.energyPerTick());
    assertEquals(9, definition.parts().getFirst().field().min());
    assertEquals(20, definition.parts().getFirst().field().max());
    assertSame(PropertySpace.LOCAL, definition.parts().getFirst().propertiesSpace());
  }

  @Test
  void machineColorIsOptionalSixDigitRgb() {
    var defaultColor = parse(json(PORTS));
    assertEquals(0xFFFFFF, defaultColor.color());

    JsonObject root = json(PORTS);
    root.addProperty("color", "#12aBef");
    assertEquals(0x12ABEF, parse(root).color());

    root.addProperty("color", "FFF");
    assertPath(root, "$.color");

    root.remove("color");
    root.addProperty("machine_color", "#0A1b2C");
    assertEquals(0x0A1B2C, parse(root).color());

    root.addProperty("color", "#FFFFFF");
    assertPath(root, "Use only one of color or machine_color");
  }

  @Test
  void formedDisplayAndControllerBoundingBoxAreValidated() {
    JsonObject root = json(PORTS);
    root.addProperty("hide_components_when_formed", true);
    root.add("controller_bounding_box", JsonParser.parseString("[0.1,0.2,0.0,0.9,1.0,0.8]"));
    var definition = parse(root);
    assertTrue(definition.hideComponentsWhenFormed());
    assertEquals(0.1, definition.controllerBoundingBox().minX);
    assertEquals(0.8, definition.controllerBoundingBox().maxZ);

    root.add("controller_bounding_box", JsonParser.parseString("[-0.1,0,0,1,1,1]"));
    assertPath(root, "controller_bounding_box");
  }

  @Test
  void orientationAloneSelectsThePoseSpace() {
    var definition = parse(
        json(
            "{\"parts\":[{\"offset\":[1,0,0],\"port\":\"energy_input\"},{\"offset\":[-1,0,0],\"port\":\"energy_output\"}]}"));
    assertSame(OrientationPolicy.HORIZONTAL, definition.orientationPolicy());
    assertEquals(0x1111, definition.allowedOrientationMask());
    var posed = parse(
        json(
            "{\"orientation\":{\"policy\":\"all_24\"},\"parts\":[{\"offset\":[1,0,0],\"port\":\"energy_input\"},{\"offset\":[-1,0,0],\"port\":\"energy_output\"}]}"));
    assertEquals(MachineOrientation.ALL_MASK, posed.allowedOrientationMask());
  }

  @Test
  void schemaTwoDefaultsHorizontalAndExplicitAll24HasABoundedLazyCache() {
    assertEquals(0x1111, parse(json(PORTS)).allowedOrientationMask());
    JsonObject root = all24();
    var definition = parse(root);
    assertEquals(MachineOrientation.ALL_MASK, definition.allowedOrientationMask());
    assertEquals(0, definition.orientationCompilation().cachedOrientationCount());
    var orientation = MachineOrientation.of(Direction.UP, 3);
    assertSame(definition.rulesFor(orientation), definition.rulesFor(orientation));
    assertEquals(1, definition.orientationCompilation().cachedOrientationCount());
    for (var value : MachineOrientation.all()) {
      definition.rulesFor(value);
    }
    assertEquals(24, definition.orientationCompilation().cachedOrientationCount());
    assertThrows(UnsupportedOperationException.class, () -> definition.rulesFor(orientation).clear());
    root.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("port", "item_input");
    assertEquals("energy_input", definition.parts().getFirst().rule().port().name().toLowerCase());
  }

  @Test
  void partIdsUseOriginalCoordinatesAndExplicitIdsSurviveReordering() {
    JsonObject root = json(PORTS);
    root.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("part_id", "input");
    root.getAsJsonArray("parts").get(1).getAsJsonObject().addProperty("part_id", "output");
    var first = parse(root);
    var parts = root.getAsJsonArray("parts");
    JsonElement firstPart = parts.get(0);
    parts.set(0, parts.get(1));
    parts.set(1, firstPart);
    var second = parse(root);
    var firstIds = first.parts().stream()
        .collect(Collectors.toMap(MachineDefinition.Part::id, MachineDefinition.Part::offset));
    var secondIds = second.parts().stream()
        .collect(Collectors.toMap(MachineDefinition.Part::id, MachineDefinition.Part::offset));
    assertEquals(firstIds, secondIds);
    assertEquals("output", second.parts().getFirst().id());
  }

  @Test
  void duplicateBlankAndImplicitlyCollidingPartIdsAreRejectedWithPaths() {
    for (String bad : List.of("", " ", "x".repeat(129), "offset:-1,0,0")) {
      JsonObject root = json(PORTS);
      root.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("part_id", bad);
      assertPath(root, ".part_id");
    }
    JsonObject root = json(PORTS);
    for (var part : root.getAsJsonArray("parts")) {
      part.getAsJsonObject().addProperty("part_id", "same");
    }
    assertPath(root, "$.parts[1].part_id");
  }

  @Test
  void leftoverSchemaVersionIsRejectedAsUnknownField() {
    JsonObject root = json(PORTS);
    root.addProperty("schema_version", 1);
    assertPath(root, "$.schema_version");
  }

  @Test
  void orientationAndUnknownKeysHavePrecisePaths() {
    for (String orientation : List
        .of("{}", "{\"policy\":\"ALL_24\"}", "{\"policy\":24}", "{\"policy\":\"all_24\",\"mirror\":true}", "null")) {
      JsonObject root = json(PORTS);
      root.add("orientation", JsonParser.parseString(orientation));
      assertPath(root, "$.orientation");
    }
    JsonObject unknown = json(PORTS);
    unknown.addProperty("unrecognized", true);
    assertPath(unknown, "$.unrecognized");
  }

  @Test
  void offsetsRejectFractionsDuplicatesAndControllerOrigin() {
    for (String offset : List.of("[0,0,0]", "[1.5,0,0]", "[1,0]", "[\"1\",0,0]")) {
      JsonObject root = json(PORTS);
      root.getAsJsonArray("parts").get(0).getAsJsonObject().add("offset", JsonParser.parseString(offset));
      assertPath(root, "$.parts[0].offset");
    }
    JsonObject far = json(PORTS);
    far.getAsJsonArray("parts").get(0).getAsJsonObject().add("offset", JsonParser.parseString("[500,0,0]"));
    assertDoesNotThrow(() -> parse(far));
    JsonObject root = json(PORTS);
    root.getAsJsonArray("parts").get(0).getAsJsonObject().add("offset", JsonParser.parseString("[-1,0,0]"));
    assertPath(root, "$.parts[1].offset");
    root.getAsJsonArray("parts").remove(0);
    root.getAsJsonArray("parts").remove(0);
    assertPath(root, "$.parts");
  }

  @Test
  void propertySpaceCorePortsAndEnergyOperationsAreValidated() {
    JsonObject root = json(PORTS);
    var part = root.getAsJsonArray("parts").get(0).getAsJsonObject();
    part.addProperty("properties_space", "relative");
    assertPath(root, "$.parts[0].properties_space");
    part.addProperty("properties_space", "world");
    assertPath(root, "$.parts[0]");
    part.remove("properties_space");
    part.add("field", json("{\"name\":\"fluid_amount\"}"));
    assertPath(root, "$.parts[0].field");
    part.remove("field");
    root.add("operation", json("{\"type\":\"energy_transfer\",\"energy_per_tick\":0}"));
    assertPath(root, "$.operation.energy_per_tick");
  }

  @Test
  void referenceCollectionFreezesLiteralsAndIdentifiesTheirJsonPaths() {
    JsonObject root = json(
        "{\"parts\":[{\"offset\":[1,0,0],\"blocks\":[\"minecraft:furnace\",\"minecraft:dispenser\"],\"properties\":{\"facing\":\"north\"}}]}");
    var references = MachineDefinition.blockReferences(Map.of(ID, root));
    assertEquals(2, references.size());
    assertEquals("north", references.getFirst().properties().getFirst().literal());
    assertTrue(references.getFirst().properties().getFirst().path().endsWith("$.parts[0].properties.facing"));
    root.getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonObject("properties").addProperty("facing", "south");
    assertEquals("north", references.getFirst().properties().getFirst().literal());
    assertThrows(UnsupportedOperationException.class, references::clear);
    assertThrows(UnsupportedOperationException.class, references.getFirst().properties()::clear);
  }

  @Test
  void candidateCompilationDoesNotPublishAndReturnsAnImmutableMap() {
    var before = StructureService.definitionsSnapshot();
    long epoch = StructureService.definitionEpoch();
    var candidate = MachineDefinitionReloadListener.compileCandidate(Map.of(ID, all24()), EMPTY);
    assertEquals(1, candidate.size());
    assertSame(before, StructureService.definitionsSnapshot());
    assertEquals(epoch, StructureService.definitionEpoch());
    assertThrows(UnsupportedOperationException.class, candidate::clear);
  }

  @Test
  @Disabled("machine definitions load from config/modularmachinery/machinery now; reload/barrier is covered by the reload GameTests")
  void aReloadPublishesOnlyAfterTheBarrierAndTheWholeCandidateIsReady() {
    var previous = StructureService.definitionsSnapshot();
    long epoch = StructureService.definitionEpoch();
    var background = new QueueExecutor();
    var game = new QueueExecutor();
    var barrier = new Barrier();
    try {
      var task = new MachineDefinitionReloadListener().reload(
          barrier,
          new MemoryResources(Map.of("a", PORTS, "b", PORTS)),
          InactiveProfiler.INSTANCE,
          InactiveProfiler.INSTANCE,
          background,
          game);
      drain(background, game);
      assertTrue(barrier.reached);
      assertFalse(task.isDone());
      assertSame(previous, StructureService.definitionsSnapshot());
      assertEquals(epoch, StructureService.definitionEpoch());
      barrier.release.complete(null);
      drain(background, game);
      task.join();
      assertEquals(Set.of(id("a"), id("b")), StructureService.definitionsSnapshot().keySet());
      assertEquals(epoch + 1, StructureService.definitionEpoch());
    } finally {
      StructureService.replaceDefinitions(previous);
    }
  }

  @Test
  void malformedJsonCannotSilentlyDeleteThePreviousDefinitions() {
    assertFailedReloadKeepsSnapshot(Map.of("valid", PORTS, "bad_json", "{\"parts\":"));
  }

  @Test
  void oneInvalidDefinitionCannotPublishTheOtherValidDefinition() {
    assertFailedReloadKeepsSnapshot(
        Map.of("a_valid", PORTS, "z_invalid", "{\"parts\":[{\"offset\":[1,0,0],\"port\":\"not_a_port\"}]}"));
  }

  private static void assertFailedReloadKeepsSnapshot(Map<String, String> files) {
    var previous = StructureService.definitionsSnapshot();
    long epoch = StructureService.definitionEpoch();
    var background = new QueueExecutor();
    var game = new QueueExecutor();
    var barrier = new Barrier();
    var task = new MachineDefinitionReloadListener().reload(
        barrier,
        new MemoryResources(files),
        InactiveProfiler.INSTANCE,
        InactiveProfiler.INSTANCE,
        background,
        game);
    drain(background, game);
    assertTrue(task.isCompletedExceptionally());
    assertThrows(CompletionException.class, task::join);
    assertFalse(barrier.reached);
    assertSame(previous, StructureService.definitionsSnapshot());
    assertEquals(epoch, StructureService.definitionEpoch());
  }

  private static JsonObject json(String value) {
    return JsonParser.parseString(value).getAsJsonObject();
  }

  private static JsonObject all24() {
    var result = json(PORTS);
    result.add("orientation", json("{\"policy\":\"all_24\"}"));
    return result;
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }

  private static MachineDefinition parse(JsonObject root) {
    return MachineDefinition.parse(ID, root, EMPTY);
  }

  private static void assertPath(JsonObject root, String path) {
    var exception = assertThrows(JsonParseException.class, () -> parse(root));
    assertTrue(exception.getMessage().contains(path), exception.getMessage());
  }

  private static final class QueueExecutor implements Executor {
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      pending.addLast(command);
    }
  }

  private static void drain(QueueExecutor background, QueueExecutor game) {
    for (int steps = 0; !background.pending.isEmpty() || !game.pending.isEmpty(); steps++) {
      if (steps > 100) {
        fail("Reload executors did not quiesce");
      }
      if (!background.pending.isEmpty()) {
        background.pending.removeFirst().run();
      }
      if (!game.pending.isEmpty()) {
        game.pending.removeFirst().run();
      }
    }
  }

  private static final class Barrier implements PreparableReloadListener.PreparationBarrier {
    private final CompletableFuture<Void> release = new CompletableFuture<>();
    private boolean reached;

    @Override
    public <T> CompletableFuture<T> wait(T result) {
      reached = true;
      return release.thenApply(ignored -> result);
    }
  }

  private record MemoryResources(Map<String, String> files) implements ResourceManager {
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
      return files
          .entrySet().stream().collect(
              Collectors.toMap(
                  entry -> id("mmcece/machines/" + entry.getKey() + ".json"),
                  // This fixture only opens the stream; no pack metadata/source is consulted.
                  entry -> new Resource(
                      null,
                      () -> new ByteArrayInputStream(entry.getValue().getBytes(StandardCharsets.UTF_8)))))
          .entrySet().stream()
          .filter(entry -> entry.getKey().getPath().startsWith(path + "/") && filter.test(entry.getKey()))
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
