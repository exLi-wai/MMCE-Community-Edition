package com.lw.modularmachine.client.selection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Segment;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RangedSelectionExportSnapshotTest {
  @Test
  void exportsCurrentCountsByStableKeyAndPreservesCompiledGeometryAndDeclarations() {
    var body = json("""
        {"offset":[-1,0,0],"block":["minecraft:oak_log","minecraft:birch_log"],
        "properties":{"axis":["x","z"]},"properties_space":"local","selector":"ore","interface":"item"}
        """);
    var pattern = new RangedSelectionExportSnapshot.Pattern(
        "first",
        "compiled_id",
        new BlockPos(4, 5, 6),
        new BlockPos(-2, 0, 0),
        List.of(json("{\"offset\":[1,0,0],\"block\":\"minecraft:stone\"}")),
        List.of(body),
        List.of(json("{\"offset\":[2,0,0],\"block\":\"minecraft:glass\"}")));
    var snapshot = new RangedSelectionExportSnapshot(List.of(), List.of(pattern));
    var current = design(List.of("other", "first"), List.of(2, 7));

    var root = snapshot.root(current, "sample");
    var exported = root.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject();

    assertEquals("machine.mmcece.sample", root.get("translation_key").getAsString());
    assertEquals("compiled_id", exported.get("id").getAsString());
    assertEquals(1, exported.get("minimum").getAsInt());
    assertEquals(7, exported.get("maximum").getAsInt());
    assertEquals(JsonParser.parseString("[4,5,6]"), exported.get("start_offset"));
    assertEquals(JsonParser.parseString("[-2,0,0]"), exported.get("step"));
    assertEquals(body, exported.getAsJsonArray("parts").get(0));
    assertEquals(
        JsonParser.parseString("[1,0,0]"),
        exported.getAsJsonArray("parts_start").get(0).getAsJsonObject().get("offset"));
    assertEquals(
        JsonParser.parseString("[2,0,0]"),
        exported.getAsJsonArray("parts_end").get(0).getAsJsonObject().get("offset"));
  }

  @Test
  void automaticIdsAvoidExplicitIdsEvenWhenTheyAppearLater() {
    var snapshot = new RangedSelectionExportSnapshot(
        List.of(),
        List.of(pattern("first", ""), pattern("second", "segment_1"), pattern("third", "")));
    var patterns = snapshot.root(design(List.of("first", "second", "third"), List.of(1, 2, 3)), "sample")
        .getAsJsonArray("dynamic_patterns");

    assertEquals("segment_2", patterns.get(0).getAsJsonObject().get("id").getAsString());
    assertEquals("segment_1", patterns.get(1).getAsJsonObject().get("id").getAsString());
    assertEquals("segment_3", patterns.get(2).getAsJsonObject().get("id").getAsString());
  }

  @Test
  void rejectsDuplicateIdsAndMissingStableKeys() {
    var duplicate = new RangedSelectionExportSnapshot(
        List.of(),
        List.of(pattern("first", "same"), pattern("second", "same")));
    var missing = new RangedSelectionExportSnapshot(List.of(), List.of(pattern("removed", "")));

    assertThrows(
        IllegalArgumentException.class,
        () -> duplicate.root(design(List.of("first", "second"), List.of(1, 2)), "sample"));
    assertThrows(IllegalArgumentException.class, () -> missing.root(Design.defaults(), "sample"));
  }

  @Test
  void eachRootIsDetachedAndKeepsTheUnassignedOriginPart() {
    var fixed = json("{\"offset\":[0,0,0],\"block\":\"minecraft:sand\"}");
    var snapshot = new RangedSelectionExportSnapshot(List.of(fixed), List.of(pattern("selection_1", "")));
    var first = snapshot.root(Design.defaults(), "first");
    first.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("block", "minecraft:air");
    first.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject().getAsJsonArray("parts").remove(0);

    var second = snapshot.root(Design.defaults(), "second");

    assertEquals(fixed, second.getAsJsonArray("parts").get(0));
    assertEquals(1, second.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject().getAsJsonArray("parts").size());
    assertEquals("machine.mmcece.second", second.get("translation_key").getAsString());
  }

  @Test
  void appendPreservesFixedRulesAndUsesTheEditedDynamicDeclarationsAndExistingIds() {
    var target = json("""
        {"translation_key":"machine.test.original","color":"AABBCC","parts":[
          {"offset":[2,0,0],"block":"MY_VARIABLE"},
          {"offset":[10,0,0],"block":["minecraft:sand","minecraft:stone"],"part_id":"repeater","selector":"ore"},
          {"offset":[16,0,0],"block":"minecraft:glass"}],
         "dynamic-patterns":[{"name":"segment_1","maxSize":1,"structure-size-offset-start":[4,0,0],
          "structure-size-offset":[1,0,0],"parts":[{"offset":[0,0,0],"block":"minecraft:stone"}]}]}
        """);
    var original = target.deepCopy();
    var selected = new RangedSelectionExportSnapshot.Pattern(
        "selection_1",
        "",
        new BlockPos(20, 0, 0),
        new BlockPos(2, 0, 0),
        List.of(),
        List.of(json("{\"offset\":[0,0,0],\"block\":\"minecraft:sand\"}")),
        List.of(json("{\"offset\":[0,0,0],\"block\":\"minecraft:glass\"}")));
    var snapshot = new RangedSelectionExportSnapshot(List.of(), List.of(selected));

    var merged = snapshot.appendTo(target, Design.defaults());

    assertEquals(original, target);
    assertEquals(target.get("translation_key"), merged.get("translation_key"));
    assertEquals(target.get("color"), merged.get("color"));
    assertEquals(target.get("parts"), merged.get("parts"));
    var segments = merged.getAsJsonArray("dynamic-patterns");
    assertFalse(merged.has("dynamic_patterns"));
    assertEquals(target.getAsJsonArray("dynamic-patterns").get(0), segments.get(0));
    var added = segments.get(1).getAsJsonObject();
    assertEquals("segment_2", added.get("id").getAsString());
    var body = added.getAsJsonArray("parts").get(0).getAsJsonObject();
    assertEquals(json("{\"offset\":[0,0,0],\"block\":\"minecraft:sand\"}"), body);
    assertEquals(json("{\"offset\":[0,0,0],\"block\":\"minecraft:glass\"}"), added.getAsJsonArray("parts_end").get(0));
    merged.getAsJsonArray("parts").remove(1);
    assertEquals(original, target);
  }

  @Test
  void appendRejectsExplicitDuplicateId() {
    var target = json("""
        {"parts":[{"offset":[2,0,0],"block":"minecraft:stone"}],
         "dynamic_patterns":[{"id":"taken","maximum":1,"step":[1,0,0],"start_offset":[5,0,0],
         "parts":[{"offset":[0,0,0],"block":"minecraft:sand"}]}]}
        """);
    var snapshot = new RangedSelectionExportSnapshot(List.of(), List.of(pattern("selection_1", "taken")));

    assertThrows(IllegalArgumentException.class, () -> snapshot.appendTo(target, Design.defaults()));
  }

  private static RangedSelectionExportSnapshot.Pattern pattern(String key, String id) {
    return new RangedSelectionExportSnapshot.Pattern(
        key,
        id,
        BlockPos.ZERO,
        new BlockPos(0, 1, 0),
        List.of(),
        List.of(json("{\"offset\":[0,0,0],\"block\":\"minecraft:sand\"}")),
        List.of());
  }

  private static Design design(List<String> keys, List<Integer> maximums) {
    var segments = maximums.stream()
        .map(
            maximum -> new Segment(
                new Settings(
                    "changed_draft_id",
                    1,
                    maximum,
                    Direction.SOUTH,
                    0,
                    false,
                    new BlockPos(0, 0, 1),
                    false,
                    BlockPos.ZERO,
                    1,
                    "",
                    ""),
                null,
                null,
                null))
        .toList();

    return Design.defaults().withSegments(segments, keys);
  }

  private static JsonObject json(String source) {
    return JsonParser.parseString(source).getAsJsonObject();
  }
}
