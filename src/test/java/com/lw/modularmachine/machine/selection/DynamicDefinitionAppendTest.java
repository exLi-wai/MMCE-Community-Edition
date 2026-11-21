package com.lw.modularmachine.machine.selection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicDefinitionAppendTest {
  @Test
  void rejectsOverlappingFixedAndDynamicRulesEvenWhenIdentical() {
    var target = target();
    var original = target.deepCopy();
    assertThrows(IllegalArgumentException.class, () -> append(target, exported()));
    var different = exported();
    body(different).addProperty("block", "minecraft:glass");
    assertThrows(IllegalArgumentException.class, () -> append(target, different));
    body(different).addProperty("block", "minecraft:stone");
    body(different).addProperty("selector", "ore");
    assertThrows(IllegalArgumentException.class, () -> append(target, different));
    assertEquals(original, target);
  }

  @Test
  void keepsOriginalFixedRulesAndExplicitEditsInDisjointAppendedParts() {
    var target = target();
    target.getAsJsonArray("parts").get(1).getAsJsonObject().addProperty("part_id", "frame");
    target.add("structure_modifiers", JsonParser.parseString("""
        [{"id":"boost","parts":[{"part_id":"frame","block":"minecraft:stone"}],"modifiers":[]}]
        """));
    var source = exported();
    source.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject()
        .add("start_offset", JsonParser.parseString("[3,0,0]"));
    var edited = body(source);
    edited.add("block", JsonParser.parseString("[\"minecraft:oak_log\",\"minecraft:birch_log\"]"));
    edited.add("properties", JsonParser.parseString("{\"axis\":\"x\"}"));
    edited.addProperty("selector", "ore");
    var merged = append(target, source);

    assertEquals(target.get("parts"), merged.get("parts"));
    assertEquals(target.get("structure_modifiers"), merged.get("structure_modifiers"));
    assertEquals(edited, body(merged));
    assertFalse(target.has("dynamic_patterns"));
  }

  @Test
  void rejectsFutureLengthCollisionInsteadOfRemovingAnUnsampledFixedPart() {
    var target = target();
    target.getAsJsonArray("parts").remove(1);
    target.getAsJsonArray("parts").add(JsonParser.parseString("""
        {"offset":[5,0,0],"block":"minecraft:stone"}
        """));
    var source = exported();
    source.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject().addProperty("maximum", 4);
    var original = target.deepCopy();
    var incoming = source.deepCopy();

    assertThrows(IllegalArgumentException.class, () -> append(target, source));
    assertEquals(original, target);
    assertEquals(incoming, source);
  }

  private static JsonObject append(JsonObject target, JsonObject source) {
    return DynamicDefinitionEditor.appendSegments(target, source.getAsJsonArray("dynamic_patterns"));
  }

  private static JsonObject body(JsonObject source) {
    return source.getAsJsonArray("dynamic_patterns").get(0).getAsJsonObject().getAsJsonArray("parts").get(0)
        .getAsJsonObject();
  }

  private static JsonObject target() {
    return JsonParser.parseString("""
        {"parts":[{"offset":[-1,0,0],"block":"minecraft:stone"},{"offset":[2,0,0],"block":"minecraft:stone"}]}
        """).getAsJsonObject();
  }

  private static JsonObject exported() {
    return JsonParser.parseString("""
        {"parts":[],"dynamic_patterns":[{"id":"tower","minimum":1,"maximum":1,
        "step":[1,0,0],"start_offset":[2,0,0],"parts":[{"offset":[0,0,0],"block":"minecraft:stone"}]}]}
        """).getAsJsonObject();
  }
}
