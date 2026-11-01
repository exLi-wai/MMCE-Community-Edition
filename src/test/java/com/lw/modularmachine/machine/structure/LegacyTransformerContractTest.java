package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyTransformerContractTest {
  private JsonObject json(String resource) throws Exception {
    try (var reader = new InputStreamReader(
        Objects.requireNonNull(getClass().getResourceAsStream(resource)),
        StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  @Test
  void seedPreservesAllLegacyCoordinatesAndEnergyAmounts() throws Exception {
    var old = json("/legacy/power_transformer.json");
    var recipe = json("/legacy/power_transformer_energy_transform.json");
    var converted = json("/converted/transformer.json");
    Map<String, JsonObject> parts = new HashMap<>();
    for (var element : converted.getAsJsonArray("parts")) {
      var part = element.getAsJsonObject();
      var pos = part.getAsJsonArray("offset");
      assertNull(parts.put(pos.get(0).getAsInt() + "," + pos.get(1).getAsInt() + "," + pos.get(2).getAsInt(), part));
    }
    assertEquals(32, parts.size());
    assertEquals(old.getAsJsonArray("parts").size(), parts.size());
    for (var element : old.getAsJsonArray("parts")) {
      var original = element.getAsJsonObject();
      var part = parts
          .get(original.get("x").getAsInt() + "," + original.get("y").getAsInt() + "," + original.get("z").getAsInt());
      assertNotNull(part, "Lost original coordinate");
      String block = original.get("elements").getAsString();
      switch (block) {
        case "minecraft:stonebrick@0" -> assertEquals("minecraft:stone_bricks", part.get("block").getAsString());
        case "modularmachinery:blockenergyinputhatch" -> assertEquals("energy_input", part.get("port").getAsString());
        case "modularmachinery:blockenergyoutputhatch" -> assertEquals("energy_output", part.get("port").getAsString());
        case "casings_decorative" -> assertEquals(6, part.getAsJsonArray("blocks").size());
        default -> assertEquals(block, part.get("block").getAsString());
      }
    }
    assertEquals(1, recipe.get("recipeTime").getAsInt());
    long amount = converted.getAsJsonObject("operation").get("energy_per_tick").getAsLong();
    assertEquals(128, amount);
    for (var requirement : recipe.getAsJsonArray("requirements")) {
      assertEquals(amount, requirement.getAsJsonObject().get("energyPerTick").getAsLong());
    }
  }
}
