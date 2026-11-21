package com.lw.modularmachine.client.selection;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import com.lw.modularmachine.machine.structure.DynamicPatternDefinition;
import com.lw.modularmachine.machine.structure.MachineDefinitionDefaults;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RangedSelectionExportFileTest {
  private static final String OUTPUT_PART = "{\"offset\":[0,0,0],\"block\":\"fluid_output\"}";
  private static final String MACHINE = """
      {"parts":[{"offset":[1,0,0],"block":"item_input"}]}
      """;

  @TempDir
  Path gameDirectory;

  @Test
  void normalizesTheOptionalExtensionAndCreatesADefaultName() {
    assertEquals("my_machine-v2.3", RangedSelectionExportFile.baseName(" My_Machine-V2.3.JSON "));
    assertTrue(RangedSelectionExportFile.baseName(" ").matches("selection_[0-9]{8}_[0-9]{6}"));
  }

  @Test
  void rejectsUnsafePathsAndReservedNames() {
    for (String name : new String[]{"../machine", "a/b", "a\\b", "C:machine", "a..b", ".json", "machine.",
        "machine.var.json", "CON", "prn.json", "aux.name.json", "NUL", "COM1", "lpt9.json", "a".repeat(97)}) {
      assertThrows(IllegalArgumentException.class, () -> RangedSelectionExportFile.baseName(name), name);
    }
  }

  @Test
  void appendsToTheCollisionNameOfAMaximumLengthExport() throws IOException {
    var directory = gameDirectory.resolve("mmcece");
    var root = JsonParser.parseString(MACHINE).getAsJsonObject();
    var name = "a".repeat(96);
    var original = RangedSelectionExportFile.save(directory, name, root);
    var collision = RangedSelectionExportFile.save(directory, name, root);
    assertEquals(name + "_1", collision.name());

    var targetName = RangedSelectionExportFile.targetName(collision.file().getFileName().toString());
    var saved = merge(RangedSelectionExportFile.capture(gameDirectory, targetName));
    assertEquals(collision.file(), saved.file());
    assertTrue(JsonParser.parseString(Files.readString(saved.file())).getAsJsonObject().has("dynamic_patterns"));
    assertEquals(root, JsonParser.parseString(Files.readString(original.file())));
  }

  @Test
  void writesPrettyUtf8JsonAndPreservesEveryExistingCollision() throws IOException {
    var root = new JsonObject();
    root.addProperty("translation_key", "选区方案");
    Path definitions = gameDirectory.resolve("config/modularmachinery/machinery/mmcece");
    var first = RangedSelectionExportFile.save(definitions, "machine", root);
    String original = Files.readString(first.file(), StandardCharsets.UTF_8);
    Path occupied = first.file().resolveSibling("machine_1.json");
    Files.writeString(occupied, "previous export", StandardCharsets.UTF_8);
    root.addProperty("parallelizable", true);
    var second = RangedSelectionExportFile.save(definitions, "machine", root);

    assertEquals(definitions.toRealPath().resolve("machine.json"), first.file());
    assertEquals("machine", first.name());
    assertEquals("machine_2", second.name());
    assertEquals(original, Files.readString(first.file(), StandardCharsets.UTF_8));
    assertEquals("previous export", Files.readString(occupied, StandardCharsets.UTF_8));
    assertTrue(original.contains("\n  \"translation_key\": \"选区方案\""));
    assertEquals(root, JsonParser.parseString(Files.readString(second.file(), StandardCharsets.UTF_8)));
    try (var files = Files.list(definitions)) {
      assertEquals(3, files.count());
    }
  }

  @Test
  void rejectsAnOccupiedExportDirectoryWithoutChangingIt() throws IOException {
    Path config = gameDirectory.resolve("config");
    Files.createDirectories(config);
    Path occupied = config.resolve("modularmachinery");
    Files.writeString(occupied, "existing file", StandardCharsets.UTF_8);

    assertThrows(
        IOException.class,
        () -> RangedSelectionExportFile.save(occupied.resolve("machinery/mmcece"), "machine", new JsonObject()));
    assertEquals("existing file", Files.readString(occupied, StandardCharsets.UTF_8));
  }

  @Test
  void mergesOnlyTheSelectedVersionAndPreservesOtherFiles() throws IOException {
    Path exports = Files.createDirectory(gameDirectory.resolve("mmcece"));
    Path file = exports.resolve("machine.json.json");
    Path other = exports.resolve("machine.json");
    Files.writeString(file, MACHINE.replace("item_input", "INPUT_VAR"));
    Files.writeString(gameDirectory.resolve("ports.var.json"), "{\"INPUT_VAR\":\"item_input\"}");
    Files.writeString(other, MACHINE);
    var baseName = RangedSelectionExportFile.targetName(" machine.JSON.JSON ");
    var selected = RangedSelectionExportFile.capture(gameDirectory, baseName);
    var saved = merge(selected);
    assertEquals(file, saved.file());
    assertEquals("machine.json", saved.name());
    var merged = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    assertEquals("INPUT_VAR", merged.getAsJsonArray("parts").get(0).getAsJsonObject().get("block").getAsString());
    assertEquals(1, merged.getAsJsonArray("dynamic_patterns").size());
    assertInstanceOf(IOException.class, assertThrows(CompletionException.class, () -> merge(selected)).getCause());
    assertEquals(MACHINE, Files.readString(other));
    try (var files = Files.list(exports)) {
      assertEquals(2, files.count());
    }
  }

  @Test
  void rejectsASemanticallyInvalidMergedMachineBeforeReplacingTheFile() throws IOException {
    Path file = Files.createDirectory(gameDirectory.resolve("mmcece")).resolve("machine.json");
    Files.writeString(file, MACHINE);
    var selected = RangedSelectionExportFile.capture(gameDirectory, "machine");
    var invalid = snapshot("{\"offset\":[0,0,0],\"block\":\"item_input\",\"interface\":\"missing\"}");
    var failure = assertThrows(
        CompletionException.class,
        () -> RangedSelectionExportFile.merge(selected, invalid, Design.defaults(), Runnable::run, Runnable::run)
            .join());
    assertTrue(failure.getCause().getMessage().contains("Smart interface roles"), failure::getMessage);
    assertEquals(MACHINE, Files.readString(file));
  }

  @Test
  void variableChangeAfterPreparationPreventsPublication() throws IOException {
    Path file = Files.createDirectory(gameDirectory.resolve("mmcece")).resolve("machine.json");
    String original = MACHINE.replace("item_input", "INPUT_VAR");
    Files.writeString(file, original);
    Path variables = gameDirectory.resolve("blocks.var.json");
    Files.writeString(variables, "{\"INPUT_VAR\":\"item_input\"}");
    var selected = RangedSelectionExportFile.capture(gameDirectory, "machine");
    var pending = new java.util.ArrayDeque<Runnable>();
    var attempt = RangedSelectionExportFile
        .merge(selected, snapshot(OUTPUT_PART), Design.defaults(), pending::add, Runnable::run);
    Files.writeString(variables, "{\"INPUT_VAR\":\"energy_output\"}");
    pending.remove().run();
    assertInstanceOf(IOException.class, assertThrows(CompletionException.class, attempt::join).getCause());
    assertEquals(original, Files.readString(file));
  }

  @Test
  void validatesOtherMachinesWithTheProductionParserBeforeReplacingTheFile() throws IOException {
    Path exports = Files.createDirectory(gameDirectory.resolve("mmcece"));
    Path file = exports.resolve("machine.json");
    Files.writeString(file, MACHINE);
    String invalid = """
        {"parts":[{"offset":[1,0,0],"block":"item_input"}],
        "operation":{"type":"energy_transfer","energy_per_tick":32}}
        """;
    Files.writeString(exports.resolve("other.json"), invalid);
    var selected = RangedSelectionExportFile.capture(gameDirectory, "machine");
    var failure = assertThrows(CompletionException.class, () -> merge(selected));
    assertTrue(failure.getCause().getMessage().contains("Energy transfer"), failure::getMessage);
    assertEquals(MACHINE, Files.readString(file));
  }

  @Test
  void oneAddedBlockCannotExceedTheSharedAlternativeBudget() throws IOException {
    Path exports = Files.createDirectory(gameDirectory.resolve("mmcece"));
    Path file = exports.resolve("machine.json");
    Files.writeString(file, MACHINE);
    var other = JsonParser.parseString(MACHINE).getAsJsonObject();
    var optional = JsonParser.parseString("{\"offset\":[2,0,0],\"block\":\"missing:optional\"}").getAsJsonObject();
    var properties = new JsonObject();
    for (int index = 0; index < 22; index++) {
      var values = new JsonArray();
      values.add("a");
      values.add("b");
      properties.add("property_" + index, values);
    }
    optional.add("properties", properties);
    other.getAsJsonArray("parts").add(optional);
    Files.writeString(exports.resolve("other.json"), other.toString());
    var selected = RangedSelectionExportFile.capture(gameDirectory, "machine");
    var addition = snapshot("{\"offset\":[0,0,0],\"block\":\"minecraft:stone\"}");
    var failure = assertThrows(
        CompletionException.class,
        () -> RangedSelectionExportFile.merge(selected, addition, Design.defaults(), Runnable::run, Runnable::run)
            .join());
    assertTrue(failure.getCause().getMessage().contains("4194304 block alternatives"), failure::getMessage);
    assertEquals(MACHINE, Files.readString(file));
  }

  @Test
  void refusesEachNewTemplateWhoseMissingPartWasSkipped() {
    var machine = ResourceLocation.fromNamespaceAndPath("mmcece", "machine");
    var registry = new StateOrientationAdapters.RegistrySnapshot(Map.of());
    var defaults = MachineDefinitionDefaults.capture();
    var missing = JsonParser.parseString("""
        {"offset":[0,1,0],"block":"missing:only_candidate"}
        """);
    for (String template : List.of("parts_start", "parts", "parts_end")) {
      var source = JsonParser.parseString(MACHINE).getAsJsonObject();
      var merged = snapshot(OUTPUT_PART).appendTo(source, Design.defaults());
      var added = merged.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject();
      if (!added.has(template)) {
        added.add(template, new JsonArray());
      }
      added.getAsJsonArray(template).add(missing);
      var additions = DynamicPatternDefinition.declarations(merged, "$");
      var failure = assertThrows(
          IllegalArgumentException.class,
          () -> RangedSelectionExportFile
              .validateCandidate(machine, Map.of(machine, merged), registry, defaults, additions));
      assertTrue(
          failure.getMessage().contains("Dynamic segment added contains unavailable block constraints"),
          failure::getMessage);
    }
  }

  @Test
  void validatesTheEffectiveLayoutAfterScriptsReplaceFixedParts() {
    var source = JsonParser.parseString(MACHINE).getAsJsonObject();
    var effective = snapshot(OUTPUT_PART).appendTo(source, Design.defaults());
    var machine = ResourceLocation.fromNamespaceAndPath("mmcece", "machine");
    var registry = new StateOrientationAdapters.RegistrySnapshot(Map.of());
    var defaults = MachineDefinitionDefaults.capture();
    var additions = DynamicPatternDefinition.declarations(effective, "$");
    assertDoesNotThrow(
        () -> RangedSelectionExportFile
            .validateCandidate(machine, Map.of(machine, effective), registry, defaults, additions));
    // MachineBuilder's set.parts can add a fixed port at the new segment's start.
    var patchedParts = JsonParser.parseString("""
        [{"offset":[1,0,0],"block":"item_input"},{"offset":[3,0,0],"block":"energy_output"}]
        """).getAsJsonArray();
    effective.add("parts", patchedParts);
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> RangedSelectionExportFile
            .validateCandidate(machine, Map.of(machine, effective), registry, defaults, additions));
    assertTrue(failure.getMessage().contains("collision"), failure::getMessage);
  }

  @Test
  void anotherMachineChangingAfterRegistryCapturePreventsPublication() throws IOException {
    Path exports = Files.createDirectory(gameDirectory.resolve("mmcece"));
    Path file = exports.resolve("machine.json");
    Path other = exports.resolve("other.json");
    Files.writeString(file, MACHINE);
    Files.writeString(other, MACHINE);
    var selected = RangedSelectionExportFile.capture(gameDirectory, "machine");
    var worker = new java.util.ArrayDeque<Runnable>();
    var attempt = RangedSelectionExportFile
        .merge(selected, snapshot(OUTPUT_PART), Design.defaults(), Runnable::run, worker::add);
    worker.remove().run();
    String changed = MACHINE.replace("item_input", "energy_output");
    Files.writeString(other, changed);
    while (!worker.isEmpty()) {
      worker.remove().run();
    }
    assertInstanceOf(IOException.class, assertThrows(CompletionException.class, attempt::join).getCause());
    assertEquals(MACHINE, Files.readString(file));
    assertEquals(changed, Files.readString(other));
  }

  @Test
  void rejectsMissingChangedAndUnsafeTargets() throws IOException {
    for (String name : new String[]{" ", "../outside", "folder/target", "machine.var"}) {
      assertThrows(IllegalArgumentException.class, () -> RangedSelectionExportFile.capture(gameDirectory, name));
    }
    var missing = gameDirectory.resolve("missing");
    assertThrows(IOException.class, () -> RangedSelectionExportFile.capture(missing, "machine"));
    assertFalse(Files.exists(missing));
    var file = Files.createDirectory(gameDirectory.resolve("mmcece")).resolve("machine.json");
    Files.writeString(file, MACHINE);
    var selected = RangedSelectionExportFile.capture(gameDirectory, "machine");
    Files.writeString(file, "{\"changed\":true}");
    assertInstanceOf(IOException.class, assertThrows(CompletionException.class, () -> merge(selected)).getCause());
    assertEquals("{\"changed\":true}", Files.readString(file));
    Files.delete(file);
    assertInstanceOf(IOException.class, assertThrows(CompletionException.class, () -> merge(selected)).getCause());
    assertFalse(Files.exists(file));
  }

  @Test
  void rejectsAnExportReplacedWithALinkAndLinkedDirectories() throws IOException {
    Path exports = Files.createDirectory(gameDirectory.resolve("mmcece"));
    Path file = exports.resolve("machine.json");
    Files.writeString(file, MACHINE);
    var selected = RangedSelectionExportFile.capture(gameDirectory, "machine");
    Path outside = gameDirectory.resolve("machine.json");
    Files.writeString(outside, "{}");
    Files.delete(file);
    try {
      Files.createSymbolicLink(file, outside);
    } catch (UnsupportedOperationException | IOException unavailable) {
      Assumptions.abort("Symbolic links unavailable on this filesystem: " + unavailable.getMessage());
    }
    assertInstanceOf(IOException.class, assertThrows(CompletionException.class, () -> merge(selected)).getCause());
    assertThrows(IOException.class, () -> RangedSelectionExportFile.capture(gameDirectory, "machine"));
    Path linked = Files.createSymbolicLink(gameDirectory.resolve("linked"), gameDirectory);
    assertThrows(IOException.class, () -> RangedSelectionExportFile.capture(linked, "machine"));
    assertEquals("{}", Files.readString(outside));
  }

  @Test
  void concurrentMergesFromOneVersionAllowOnlyOneWriter() throws Exception {
    Path file = Files.createDirectory(gameDirectory.resolve("mmcece")).resolve("machine.json");
    Files.writeString(file, MACHINE);
    var selected = RangedSelectionExportFile.capture(gameDirectory, "machine");
    var ready = new CyclicBarrier(2);
    Callable<Boolean> edit = () -> {
      ready.await(10, TimeUnit.SECONDS);
      try {
        merge(selected);
        return true;
      } catch (CompletionException stale) {
        assertInstanceOf(IOException.class, stale.getCause());
        return false;
      }
    };
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(edit);
      var second = workers.submit(edit);
      assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }
    assertEquals(
        1,
        JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("dynamic_patterns").size());
  }

  private static RangedSelectionExportFile.Saved merge(RangedSelectionExportFile.Target target) {
    return RangedSelectionExportFile
        .merge(target, snapshot(OUTPUT_PART), Design.defaults(), Runnable::run, Runnable::run).join();
  }

  private static RangedSelectionExportSnapshot snapshot(String declaration) {
    var part = JsonParser.parseString(declaration).getAsJsonObject();
    var segment = new RangedSelectionExportSnapshot.Pattern(
        "selection_1",
        "added",
        new BlockPos(3, 0, 0),
        new BlockPos(1, 0, 0),
        List.of(),
        List.of(part),
        List.of());
    return new RangedSelectionExportSnapshot(List.of(), List.of(segment));
  }
}
