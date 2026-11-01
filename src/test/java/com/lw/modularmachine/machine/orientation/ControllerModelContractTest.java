package com.lw.modularmachine.machine.orientation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControllerModelContractTest {
  @Test
  void controllerFaceOverlayAndToolAssetsHaveCompleteResourceContracts() throws Exception {
    var english = resource("/assets/mmcece/lang/en_us.json");
    var chinese = resource("/assets/mmcece/lang/zh_cn.json");
    for (String key : List.of(
        "item.mmcece.machine_wrench",
        "tooltip.mmcece.machine_wrench",
        "message.mmcece.orientation",
        "message.mmcece.placement_not_allowed",
        "message.mmcece.placement_invalid_data")) {
      assertTrue(english.has(key) && chinese.has(key), key);
    }
    for (String front : new String[]{"north", "east", "south", "west", "up", "down"}) {
      for (int roll = 0; roll < 4; roll++) {
        String path = "/assets/mmcece/models/block/controller/" + front + "_" + roll + ".json";
        var model = resource(path);
        assertEquals("mmcece:block/overlay_controller", model.getAsJsonObject("textures").get("overlay").getAsString());
        assertFalse(model.getAsJsonObject("textures").has("up_arrow"));
        assertEquals(2, model.getAsJsonArray("elements").size());
        var element = model.getAsJsonArray("elements").get(1).getAsJsonObject();
        var faces = element.getAsJsonObject("faces");
        assertEquals(Set.of(front), faces.keySet());
        assertEquals("#overlay", faces.getAsJsonObject(front).get("texture").getAsString());
        assertEquals(front, faces.getAsJsonObject(front).get("cullface").getAsString());
        int base = front.equals("up") || front.equals("down") ? 180 : 0;
        assertEquals((base + roll * 90) % 360, faces.getAsJsonObject(front).get("rotation").getAsInt());
      }
    }
    assertEquals(
        "mmcece:item/machine_wrench",
        resource("/assets/mmcece/models/item/machine_wrench.json").getAsJsonObject("textures").get("layer0")
            .getAsString());
    var tags = resource("/data/mmcece/tags/item/controller_wrenches.json").getAsJsonArray("values");
    assertEquals("mmcece:machine_wrench", tags.get(0).getAsString());
  }

  private JsonObject resource(String path) throws Exception {
    try (var reader = new InputStreamReader(
        Objects.requireNonNull(getClass().getResourceAsStream(path)),
        StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }

  @Test
  void allTwentyFourStateVariantsHaveLiteralFrontFacesAndRollUvs() throws Exception {
    var variants = resource("/assets/mmcece/blockstates/machine_controller.json").getAsJsonObject("variants");
    assertEquals(24, variants.size());
    String[] fronts = {"north", "east", "south", "west", "up", "down"};
    for (String front : fronts) {
      for (int roll = 0; roll < 4; roll++) {
        var variant = variants.getAsJsonObject("facing=" + front + ",roll=" + roll);
        assertNotNull(variant);
        String model = variant.get("model").getAsString();
        assertEquals("mmcece:block/controller/" + front + "_" + roll, model);
        var json = resource("/assets/mmcece/models/" + model.substring("mmcece:".length()) + ".json");
        assertFalse(json.getAsJsonObject("textures").has("up_arrow"));
        var overlay = json.getAsJsonArray("elements").get(1).getAsJsonObject().getAsJsonObject("faces");
        assertEquals(Set.of(front), overlay.keySet());
        assertEquals(front, overlay.getAsJsonObject(front).get("cullface").getAsString());
        assertEquals("#overlay", overlay.getAsJsonObject(front).get("texture").getAsString());
        int base = front.equals("up") || front.equals("down") ? 180 : 0;
        assertEquals((base + roll * 90) % 360, overlay.getAsJsonObject(front).get("rotation").getAsInt());
        assertEquals(
            Set.of(fronts),
            json.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").keySet());
      }
    }
  }

  @Test
  void itemAndLegacyDefaultModelResourcesRemainAvailable() throws Exception {
    assertEquals(
        "mmcece:block/machine_controller",
        resource("/assets/mmcece/models/item/machine_controller.json").get("parent").getAsString());
    assertEquals(
        "#overlay",
        resource("/assets/mmcece/models/block/machine_controller.json").getAsJsonArray("elements").get(1)
            .getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north").get("texture").getAsString());
  }
}
