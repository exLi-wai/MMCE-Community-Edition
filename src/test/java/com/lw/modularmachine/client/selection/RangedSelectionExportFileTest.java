package com.lw.modularmachine.client.selection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RangedSelectionExportFileTest {
  @TempDir
  Path gameDirectory;

  @Test
  void normalizesTheOptionalExtensionAndCreatesADefaultName() {
    assertEquals("my_machine-v2.3", RangedSelectionExportFile.fileName(" My_Machine-V2.3.JSON "));
    assertTrue(RangedSelectionExportFile.fileName(" ").matches("selection_[0-9]{8}_[0-9]{6}"));
  }

  @Test
  void rejectsUnsafePathsAndReservedNames() {
    for (String name : new String[]{"../machine", "a/b", "a\\b", "C:machine", "a..b", ".json", "machine.",
        "machine.var.json", "CON", "prn.json", "aux.name.json", "NUL", "COM1", "lpt9.json", "a".repeat(97)}) {
      assertThrows(IllegalArgumentException.class, () -> RangedSelectionExportFile.fileName(name), name);
    }
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
}
