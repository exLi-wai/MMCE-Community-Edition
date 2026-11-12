package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeStartCost;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryUpgradeDefinitionTest {
  private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "parallel");

  @Test
  void parsesFixedItemAndPreservesDefinitionIdentityWithoutChangingJson() {
    var json = JsonParser
        .parseString("{\"schema_version\":1,\"item\":\"minecraft:amethyst_shard\",\"parallel_add\":2}");
    var before = json.deepCopy();
    var definition = FactoryUpgradeDefinition.parse(ID, json);

    assertEquals("minecraft:amethyst_shard", definition.item().toString());
    assertEquals(ID, definition.effect().id());
    assertEquals(3, definition.effect().snapshot(0).parallelLimit());
    assertEquals(1, definition.effect().energyNumerator());
    assertEquals(before, json);
  }

  @Test
  void rejectsUnknownFieldsTypesSchemasAndOutOfRangeEffects() {
    for (String extra : List.of(
        ",\"extra\":1",
        ",\"parallel_add\":1.5",
        ",\"parallel_add\":2147483647",
        ",\"parallel_add\":-1",
        ",\"speed_denominator\":0",
        ",\"speed_numerator\":257",
        ",\"fluid_numerator\":true",
        ",\"energy_numerator\":\"2\"")) {
      var json = JsonParser.parseString("{\"schema_version\":1,\"item\":\"minecraft:amethyst_shard\"" + extra + "}");
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, json), extra);
    }
    for (String invalid : List
        .of("[]", "null", "{}", "{\"item\":\"minecraft:stone\"}", "{\"schema_version\":1,\"item\":\"BAD ITEM\"}")) {
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, JsonParser.parseString(invalid)));
    }
  }

  @Test
  void fixedUpgradeCanReachTheIntParallelLimit() {
    var json = JsonParser
        .parseString("{\"schema_version\":1,\"item\":\"minecraft:stone\",\"parallel_add\":2147483646}");

    assertEquals(Integer.MAX_VALUE, FactoryUpgradeDefinition.parse(ID, json).effect().snapshot(0).parallelLimit());
  }

  @Test
  void parsesMachineListsAsStableIdentifiersWithoutRequiringLiveDefinitions() {
    var json = JsonParser.parseString(
        "{\"schema_version\":1,\"item\":\"minecraft:stone\",\"compatible_machines\":[\"pack:future_machine\"],\"incompatible_machines\":[\"pack:other\"]}");
    var definition = FactoryUpgradeDefinition.parse(ID, json);

    assertEquals(
        Set.of(ResourceLocation.parse("pack:future_machine")),
        definition.compatibility().compatibleMachines());
    assertEquals(false, definition.compatibility().allows(ResourceLocation.parse("pack:currently_loaded")));
    assertEquals(true, definition.compatibility().allows(ResourceLocation.parse("pack:future_machine")));
  }

  @Test
  void rejectsMalformedDuplicateAndConflictingMachineLists() {
    for (String extra : List.of(
        ",\"compatible_machines\":null",
        ",\"compatible_machines\":\"test:a\"",
        ",\"compatible_machines\":[1]",
        ",\"compatible_machines\":[\"BAD ID\"]",
        ",\"compatible_machines\":[\"test:a\",\"test:a\"]",
        ",\"compatible_machines\":[\"test:a\"],\"incompatible_machines\":[\"test:a\"]")) {
      var json = JsonParser.parseString("{\"schema_version\":1,\"item\":\"minecraft:stone\"" + extra + "}");
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, json));
    }
  }

  @Test
  void omittedComponentsAndExplicitDefaultsHaveDifferentMatchModes() {
    var generic = FactoryUpgradeDefinition
        .parse(ID, JsonParser.parseString("{\"schema_version\":1,\"item\":\"minecraft:paper\"}"));
    var exact = FactoryUpgradeDefinition
        .parse(ID, JsonParser.parseString("{\"schema_version\":1,\"item\":\"minecraft:paper\",\"components\":{}}"));

    assertEquals(false, generic.exactComponents());
    assertEquals(true, exact.exactComponents());
    assertEquals(0, exact.components().size());
  }

  @Test
  void rejectsMalformedOrOversizedComponentMaps() {
    for (String value : List.of("null", "[]", "true", "\"minecraft:damage\"")) {
      var json = JsonParser
          .parseString("{\"schema_version\":1,\"item\":\"minecraft:paper\",\"components\":" + value + "}");
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, json));
    }
    var json = JsonParser.parseString("{\"schema_version\":1,\"item\":\"minecraft:paper\",\"components\":{}}")
        .getAsJsonObject();
    for (int index = 0; index <= FactoryUpgradeDefinition.MAX_COMPONENTS; index++) {
      json.getAsJsonObject("components").addProperty("test:component_" + index, index);
    }
    assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, json));
  }

  @Test
  void parsesDynamicHandlerReferencesAndRejectsMalformedIdentifiers() {
    var parsed = FactoryUpgradeDefinition.parse(
        ID,
        JsonParser
            .parseString("{\"schema_version\":1,\"item\":\"minecraft:paper\",\"dynamic_effect\":\"pack:charged\"}"));
    assertEquals(ResourceLocation.parse("pack:charged"), parsed.dynamicEffect());
    for (String invalid : List.of("null", "[]", "false", "\"BAD ID\"")) {
      var root = JsonParser
          .parseString("{\"schema_version\":1,\"item\":\"minecraft:paper\",\"dynamic_effect\":" + invalid + "}");
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
    }
  }

  @Test
  void lifecycleHandlerIsIndependentFromDynamicEffectsAndStrictlyValidated() {
    var root = new JsonObject();
    root.addProperty("schema_version", 1);
    root.addProperty("item", "minecraft:paper");
    root.addProperty("lifecycle_handler", "pack:observer");
    var parsed = FactoryUpgradeDefinition.parse(ID, root);
    assertEquals(ResourceLocation.parse("pack:observer"), parsed.lifecycleHandler());
    assertEquals(null, parsed.dynamicEffect());
    for (String value : List.of("null", "[]", "false", "1")) {
      root.add("lifecycle_handler", JsonParser.parseString(value));
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
    }
    for (String value : List.of("BAD ID", "pack:" + "a".repeat(256))) {
      root.addProperty("lifecycle_handler", value);
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
    }
  }

  @Test
  void componentSpecificationOwnsItsNestedJson() {
    var effect = new FactoryUpgradeEntry(ID, 1, 1, 1, 1, 1, 1, 1);
    var components = JsonParser.parseString("{\"minecraft:custom_data\":{\"variant\":1}}").getAsJsonObject();
    var definition = new FactoryUpgradeDefinition(ID, effect, FactoryUpgradeCompatibility.unrestricted(), components);
    components.getAsJsonObject("minecraft:custom_data").addProperty("variant", 9);
    definition.components().getAsJsonObject("minecraft:custom_data").addProperty("variant", 7);

    assertEquals(1, definition.components().getAsJsonObject("minecraft:custom_data").get("variant").getAsInt());
  }

  @Test
  void recipeCheckHandlerIsIndependentAndStrictlyValidated() {
    var root = new JsonObject();
    root.addProperty("schema_version", 1);
    root.addProperty("item", "minecraft:sugar");
    root.addProperty("recipe_check_handler", "pack:gate");
    var parsed = FactoryUpgradeDefinition.parse(ID, root);
    assertEquals(ResourceLocation.parse("pack:gate"), parsed.recipeCheckHandler());
    assertEquals(null, parsed.dynamicEffect());
    assertEquals(null, parsed.lifecycleHandler());
    for (String value : List.of("null", "[]", "true", "1")) {
      root.add("recipe_check_handler", JsonParser.parseString(value));
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
    }
    for (String value : List.of("BAD ID", "pack:" + "a".repeat(256))) {
      root.addProperty("recipe_check_handler", value);
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
    }
  }

  @Test
  void parsesIndependentWholeSlotStartCosts() {
    var root = new JsonObject();
    root.addProperty("schema_version", 1);
    root.addProperty("item", "minecraft:sugar");
    var cost = new JsonObject();
    cost.addProperty("consume", 2);
    root.add("start_cost", cost);
    var parsed = FactoryUpgradeDefinition.parse(ID, root);

    assertEquals(new FactoryUpgradeStartCost(0, 2), parsed.startCost());
    cost.addProperty("durability", 1);
    assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
  }

  @Test
  void startupDataHandlerIsIndependentAndStrictlyValidated() {
    var root = new JsonObject();
    root.addProperty("schema_version", 1);
    root.addProperty("item", "minecraft:echo_shard");
    root.addProperty("start_data_handler", "pack:data");
    var parsed = FactoryUpgradeDefinition.parse(ID, root);
    assertEquals(ResourceLocation.parse("pack:data"), parsed.startDataHandler());
    assertEquals(null, parsed.dynamicEffect());
    assertEquals(false, parsed.startCost().present());
    for (String value : List.of("null", "[]", "false", "1")) {
      root.add("start_data_handler", JsonParser.parseString(value));
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
    }
    for (String value : List.of("BAD ID", "pack:" + "a".repeat(256))) {
      root.addProperty("start_data_handler", value);
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
    }
  }

  @Test
  void startupBusDataHandlerIsIndependentAndStrictlyValidated() {
    var root = new JsonObject();
    root.addProperty("schema_version", 1);
    root.addProperty("item", "minecraft:glow_ink_sac");
    root.addProperty("start_bus_data_handler", "pack:bus_data");
    var parsed = FactoryUpgradeDefinition.parse(ID, root);
    assertEquals(ResourceLocation.parse("pack:bus_data"), parsed.startBusDataHandler());
    assertEquals(null, parsed.startDataHandler());
    assertEquals(false, parsed.startCost().present());
    for (String value : List.of("null", "[]", "false", "1")) {
      root.add("start_bus_data_handler", JsonParser.parseString(value));
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
    }
    for (String value : List.of("BAD ID", "pack:" + "a".repeat(256))) {
      root.addProperty("start_bus_data_handler", value);
      assertThrows(JsonParseException.class, () -> FactoryUpgradeDefinition.parse(ID, root));
    }
  }
}
