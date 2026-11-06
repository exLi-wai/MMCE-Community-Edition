package com.lw.modularmachine.gametest;

import net.minecraft.resources.ResourceLocation;

import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDefinition;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads upgrade fixtures explicitly; production datapacks do not register
 * upgrades.
 */
final class GameTestUpgradeFixtures {
  private static final List<String> FILES = List.of(
      "test_component_damage.json",
      "test_component_data.json",
      "test_component_default.json",
      "test_component_fallback.json",
      "test_component_named.json",
      "test_dynamic_charge.json",
      "test_fixed_parallel.json",
      "test_lifecycle_upgrade.json",
      "test_recipe_check_upgrade.json",
      "test_upgrade_bus_data.json",
      "test_upgrade_item_data.json");

  private GameTestUpgradeFixtures() {
  }

  static void install() {
    var definitions = new ArrayList<FactoryUpgradeDefinition>();
    for (var file : FILES) {
      var resource = GameTestUpgradeFixtures.class.getResourceAsStream("/data/mmcece/mmcece/upgrades/" + file);
      if (resource == null) {
        throw new IllegalStateException("Missing upgrade fixture " + file);
      }
      try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
        var id = file.substring(0, file.length() - ".json".length());
        definitions.add(
            FactoryUpgradeDefinition
                .parse(ResourceLocation.fromNamespaceAndPath("mmcece", id), JsonParser.parseReader(reader)));
      } catch (Exception failure) {
        throw new IllegalStateException("Invalid upgrade fixture " + file, failure);
      }
    }
    FactoryUpgradeService.replaceDefinitions(List.copyOf(definitions));
  }
}
