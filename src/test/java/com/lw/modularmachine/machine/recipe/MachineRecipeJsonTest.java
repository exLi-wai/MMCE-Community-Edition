package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MachineRecipeJsonTest {
  private static JsonObject valid() {
    return JsonParser.parseString(
        "{\"type\":\"mmcece:machine_item\",\"machine\":\"mmcece:processor\",\"duration\":20,\"inputs\":[{\"stack\":{\"id\":\"minecraft:dirt\"},\"amount\":1}],\"outputs\":[{\"stack\":{\"id\":\"minecraft:iron_ingot\"},\"amount\":1}]}")
        .getAsJsonObject();
  }

  private static void reject(JsonObject root, String path) {
    assertTrue(
        assertThrows(JsonParseException.class, () -> MachineRecipeJson.validate(root)).getMessage().contains(path));
  }

  @Test
  void validatesDefaultsAndExactLongAmounts() {
    var root = valid();
    root.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("amount", Long.MAX_VALUE);
    var header = MachineRecipeJson.validate(root);
    assertEquals(0, header.priority());
    assertEquals(20, header.duration());
  }

  @Test
  void rejectsFractionalStringAndBooleanAmounts() {
    for (String raw : new String[]{"1.5", "\"2\"", "true", "9223372036854775808"}) {
      var root = valid();
      root.getAsJsonArray("inputs").get(0).getAsJsonObject().add("amount", JsonParser.parseString(raw));
      reject(root, "$.inputs[0].amount");
    }
  }

  @Test
  void rejectsUnknownOrFutureRequirements() {
    for (String field : new String[]{"fluids", "chance", "ignoreOutputCheck"}) {
      var root = valid();
      root.addProperty(field, true);
      reject(root, "$." + field);
    }
    var root = valid();
    root.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("future_field", "x");
    reject(root, "$.inputs[0].future_field");
  }

  @Test
  void leftoverSchemaVersionIsRejectedAsUnknownField() {
    var root = valid();
    root.addProperty("schema_version", 1);
    reject(root, "$.schema_version");
  }

  @Test
  void rejectsInvalidDurationValues() {
    for (String raw : new String[]{"0", "-1", "1.2", "2147483648"}) {
      var root = valid();
      root.add("duration", JsonParser.parseString(raw));
      reject(root, "$.duration");
    }
  }

  @Test
  void supportsOptionalNonnegativeFePerTick() {
    var root = valid();
    root.addProperty("energy_per_tick", Long.MAX_VALUE);
    var header = MachineRecipeJson.validate(root);
    assertEquals(Long.MAX_VALUE, header.energyPerTick());
    assertEquals(0, MachineRecipeJson.validate(valid()).energyPerTick());
    root.addProperty("energy_per_tick", -1);
    reject(root, "$.energy_per_tick");
    root = valid();
    root.addProperty("energy_per_tick", 1);
    assertEquals(1, MachineRecipeJson.validate(root).energyPerTick());
    root = valid();
    root.addProperty("energy_per_tick", 1.5);
    reject(root, "$.energy_per_tick");
  }

  @Test
  void validatesSchemaTwoFluidStackAndAmount() {
    var root = valid();
    root.add("fluid_per_tick", JsonParser.parseString("{\"id\":\"minecraft:water\",\"amount\":250}"));
    var header = MachineRecipeJson.validate(root);
    assertTrue(header.hasFluidPerTick());
    root.getAsJsonObject("fluid_per_tick").addProperty("amount", 0);
    reject(root, "$.fluid_per_tick.amount");
    root = valid();
    root.add("fluid_per_tick", JsonParser.parseString("{\"id\":\"minecraft:water\",\"amount\":1}"));
    assertTrue(MachineRecipeJson.validate(root).hasFluidPerTick());
  }

  @Test
  void validatesFailureActionWithStillAsTheDefault() {
    assertEquals("still", MachineRecipeJson.validate(valid()).failureAction());
    var root = valid();
    root.addProperty("failure_action", "decrease");
    assertEquals("decrease", MachineRecipeJson.validate(root).failureAction());
    root.addProperty("failure_action", "invalid");
    reject(root, "$.failure_action");
    root = valid();
    root.addProperty("failure_action", "reset");
    assertEquals("reset", MachineRecipeJson.validate(root).failureAction());
  }

  @Test
  void validatesStableRandomSeed() {
    var root = valid();
    root.addProperty("random_seed", Long.MAX_VALUE);
    assertEquals(Long.MAX_VALUE, MachineRecipeJson.validate(root).randomSeed());
    root.addProperty("random_seed", -1);
    reject(root, "$.random_seed");
    root = valid();
    root.addProperty("random_seed", 1);
    assertEquals(1, MachineRecipeJson.validate(root).randomSeed());
  }

  @Test
  void validatesInputAndOutputChanceInSchemaTwo() {
    var validChance = valid();
    validChance.getAsJsonArray("outputs").get(0).getAsJsonObject().addProperty("chance", 0.25);
    assertDoesNotThrow(() -> MachineRecipeJson.validate(validChance));
    var root = validChance;
    for (var chance : new String[]{"-0.1", "1.1", "\"half\""}) {
      root.getAsJsonArray("outputs").get(0).getAsJsonObject().add("chance", JsonParser.parseString(chance));
      reject(root, "$.outputs[0].chance");
    }
    var inputChance = valid();
    inputChance.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("chance", 0.0);
    assertDoesNotThrow(() -> MachineRecipeJson.validate(inputChance));
    inputChance.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("chance", 0.5);
    assertDoesNotThrow(() -> MachineRecipeJson.validate(inputChance));
    var halfChance = valid();
    halfChance.getAsJsonArray("outputs").get(0).getAsJsonObject().addProperty("chance", 0.5);
    assertDoesNotThrow(() -> MachineRecipeJson.validate(halfChance));
  }

  @Test
  void validatesSchemaTwoFixedFluidInputs() {
    var root = valid();
    root.add("fluid_inputs", JsonParser.parseString("[{\"id\":\"minecraft:water\",\"amount\":100}]"));
    var header = MachineRecipeJson.validate(root);
    assertTrue(header.hasFluidInputs());
    root.getAsJsonArray("fluid_inputs").get(0).getAsJsonObject().addProperty("amount", 0);
    reject(root, "$.fluid_inputs[0].amount");
    root = valid();
    root.add("fluid_inputs", JsonParser.parseString("[{\"id\":\"minecraft:water\",\"amount\":1}]"));
    assertTrue(MachineRecipeJson.validate(root).hasFluidInputs());
  }

  @Test
  void rejectsBadIdentifiersAndAmbiguousPrototypeCounts() {
    var root = valid();
    root.addProperty("machine", "Bad ID");
    reject(root, "$.machine");
    root = valid();
    root.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("stack").addProperty("count", 2);
    reject(root, "$.inputs[0].stack.count");
  }

  @Test
  void rejectsEmptyRequirementsAndWrongComponentShapes() {
    var root = valid();
    root.getAsJsonArray("inputs").remove(0);
    reject(root, "$.inputs");
    root = valid();
    root.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("stack")
        .addProperty("components", "not a map");
    reject(root, "$.inputs[0].stack.components");
  }

  @Test
  void acceptsIntegerPriorityBoundariesOnly() {
    for (int value : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      var root = valid();
      root.addProperty("priority", value);
      assertEquals(value, MachineRecipeJson.validate(root).priority());
    }
    var root = valid();
    root.addProperty("priority", 2147483648L);
    reject(root, "$.priority");
  }

  @Test
  void rejectsZeroOrNegativeRequirements() {
    for (long value : new long[]{0, -1}) {
      var root = valid();
      root.getAsJsonArray("outputs").get(0).getAsJsonObject().addProperty("amount", value);
      reject(root, "$.outputs[0].amount");
    }
  }

  @Test
  void boundsRequirementsBeforeNativeDecodingAndValidatesConditionShape() {
    var root = valid();
    var prototype = root.getAsJsonArray("inputs").get(0).deepCopy();
    for (int i = 1; i <= MachineRecipeJson.MAX_DEMANDS; i++) {
      root.getAsJsonArray("inputs").add(prototype.deepCopy());
    }
    reject(root, "$.inputs");
    root = valid();
    root.addProperty("neoforge:conditions", false);
    reject(root, "$.neoforge:conditions");
  }
}
