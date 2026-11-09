package com.lw.modularmachine.machine.selection;

import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PartEdit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PartOverridesTest {
  @Test
  void changesOnlySelectedFieldsAndDoesNotMutateTheSource() {
    var original = JsonParser.parseString("""
        {"offset":[1,2,3],"block":"casings","part_id":"keep",
         "field":{"name":"energy_stored","min":10},"selector":"old","interface":"temperature",
         "properties":{"facing":"north"},"properties_space":"local"}
        """).getAsJsonObject();
    var edited = PartOverrides
        .apply(original, new PartEdit("", "facing=south|west", "world", true, "input", false, "ignored"));
    assertEquals(original.get("offset"), edited.get("offset"));
    assertEquals(original.get("field"), edited.get("field"));
    assertEquals("keep", edited.get("part_id").getAsString());
    assertEquals("casings", edited.get("block").getAsString());
    assertEquals("temperature", edited.get("interface").getAsString());
    assertEquals("input", edited.get("selector").getAsString());
    assertEquals("world", edited.get("properties_space").getAsString());
    assertEquals("[\"south\",\"west\"]", edited.getAsJsonObject("properties").get("facing").toString());
    assertEquals("north", original.getAsJsonObject("properties").get("facing").getAsString());
    assertEquals("old", original.get("selector").getAsString());
  }

  @Test
  void explicitEmptyMarkersAndPropertyClearRemoveOnlyThoseConstraints() {
    var original = JsonParser.parseString("""
        {"offset":[1,0,0],"block":"old","properties":{"axis":"y"},
         "properties_space":"local","selector":"x","interface":"y"}
        """).getAsJsonObject();
    var edited = PartOverrides
        .apply(original, new PartEdit("minecraft:stone, casings, minecraft:stone", "-", "world", true, "", true, ""));
    assertEquals("[\"minecraft:stone\",\"casings\"]", edited.get("block").toString());
    assertFalse(edited.has("properties"));
    assertFalse(edited.has("properties_space"));
    assertFalse(edited.has("selector"));
    assertFalse(edited.has("interface"));
    assertEquals(original.get("offset"), edited.get("offset"));
    assertEquals(original, PartOverrides.apply(original, PartEdit.inherit()));
  }

  @Test
  void malformedPropertyAssignmentsAndEmptyCandidatesAreRejected() {
    var part = JsonParser.parseString("{\"block\":\"minecraft:stone\"}").getAsJsonObject();
    for (var value : new String[]{"facing=", "facing=north|", "facing=north;facing=south", "{\"offset\":[1,2,3]}"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> PartOverrides.apply(part, new PartEdit("", value, "world", false, "", false, "")));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> PartOverrides.apply(part, new PartEdit("minecraft:stone,", "", "world", false, "", false, "")));
  }
}
