package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MachineDefinitionVariablesTest {
  @Test
  void stringAndArrayVariablesExpandBlockCandidates() {
    var variables = MachineDefinitionVariables.parse(
        JsonParser.parseString("{\"casings\":[\"minecraft:stone\",\"minecraft:deepslate\"]}").getAsJsonObject(),
        "casings.var.json");
    var root = JsonParser.parseString("{\"parts\":[{\"offset\":[1,2,3],\"block\":[\"casings\",\"minecraft:glass\"]}]}")
        .getAsJsonObject();
    var expanded = variables.expandParts(root, "machine.json");
    var part = expanded.getAsJsonArray("parts").get(0).getAsJsonObject();
    assertEquals("[\"minecraft:stone\",\"minecraft:deepslate\",\"minecraft:glass\"]", part.get("block").toString());
  }

  @Test
  void blockVariableUsesTheSameCanonicalField() {
    var variables = MachineDefinitionVariables.parse(
        JsonParser.parseString("{\"casings_all\":[\"minecraft:stone\",\"minecraft:glass\"]}").getAsJsonObject(),
        "casings.var.json");
    var root = JsonParser.parseString("{\"parts\":[{\"offset\":[1,0,0],\"block\":\"casings_all\"}]}").getAsJsonObject();
    var part = variables.expandParts(root, "machine.json").getAsJsonArray("parts").get(0).getAsJsonObject();
    assertEquals("[\"minecraft:stone\",\"minecraft:glass\"]", part.get("block").toString());
  }

  @Test
  void nestedVariablesAreRejected() {
    var variables = MachineDefinitionVariables.parse(
        JsonParser.parseString("{\"base\":\"minecraft:stone\",\"nested\":[\"base\"]}").getAsJsonObject(),
        "casings.var.json");
    var root = JsonParser.parseString("{\"parts\":[{\"offset\":[1,0,0],\"block\":\"nested\"}]}").getAsJsonObject();
    assertThrows(RuntimeException.class, () -> variables.expandParts(root, "machine.json"));
  }

  @Test
  void variableFilesCanBeMergedWithLaterDefinitionsWinning() {
    var values = new java.util.LinkedHashMap<String, java.util.List<String>>();
    values.putAll(
        MachineDefinitionVariables
            .parse(JsonParser.parseString("{\"casings\":\"minecraft:stone\"}").getAsJsonObject(), "a.var.json")
            .values());
    values.putAll(
        MachineDefinitionVariables
            .parse(JsonParser.parseString("{\"casings\":\"minecraft:glass\"}").getAsJsonObject(), "b.var.json")
            .values());
    assertEquals(Map.of("casings", java.util.List.of("minecraft:glass")), values);
  }

  @Test
  void unifiedBlockFieldRecognizesPortNames() {
    var root = JsonParser.parseString("{\"parts\":[{\"offset\":[1,0,0],\"block\":\"item_input_bus\"}]}")
        .getAsJsonObject();
    var definition = MachineDefinition.parse(
        ResourceLocation.fromNamespaceAndPath("test", "unified"),
        root,
        new StateOrientationAdapters.RegistrySnapshot(Map.of()));
    assertEquals(PortKind.ITEM_INPUT, definition.parts().getFirst().rule().port());
    assertEquals("item_input_bus", root.getAsJsonArray("parts").get(0).getAsJsonObject().get("block").getAsString());
  }

  @Test
  void nonCanonicalPartFieldsAreRejected() {
    for (String field : new String[]{"port", "blocks", "elements"}) {
      var root = JsonParser.parseString("{\"parts\":[{\"offset\":[1,0,0],\"" + field + "\":\"item_input\"}]}")
          .getAsJsonObject();
      assertThrows(
          RuntimeException.class,
          () -> MachineDefinition.parse(
              ResourceLocation.fromNamespaceAndPath("test", "non_canonical_" + field),
              root,
              new StateOrientationAdapters.RegistrySnapshot(Map.of())));
    }
  }
}
