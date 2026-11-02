package com.lw.modularmachine.machine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeBusResourceContractTest {
  private static final String[] TIERS = {"normal", "reinforced", "elite", "super", "ultimate"};

  @Test
  void allLegacyTiersHaveModelsLootAndTranslations() throws Exception {
    var english = resource("/assets/mmcece/lang/en_us.json");
    var chinese = resource("/assets/mmcece/lang/zh_cn.json");
    for (String tier : TIERS) {
      String block = "upgrade_bus_" + tier;
      var blockstate = resource("/assets/mmcece/blockstates/" + block + ".json");
      assertEquals(
          "mmcece:block/" + block,
          blockstate.getAsJsonObject("variants").getAsJsonObject("").get("model").getAsString());
      assertEquals(
          "mmcece:block/upgrade_bus_" + tier,
          resource("/assets/mmcece/models/item/" + block + ".json").get("parent").getAsString());
      var model = resource("/assets/mmcece/models/block/" + block + ".json");
      assertEquals("mmcece:block/ported_shell", model.get("parent").getAsString());
      assertEquals(
          "mmcece:block/overlay_upgrade_bus_" + tier,
          model.getAsJsonObject("textures").get("overlay").getAsString());
      try (var overlay = Objects.requireNonNull(
          getClass().getResourceAsStream("/assets/mmcece/textures/block/overlay_upgrade_bus_" + tier + ".png"))) {
        BufferedImage image = ImageIO.read(overlay);
        assertEquals(32, image.getWidth());
        assertEquals(32, image.getHeight());
      }
      assertEquals(
          "mmcece:" + block,
          resource("/data/mmcece/loot_table/blocks/" + block + ".json").getAsJsonArray("pools").get(0).getAsJsonObject()
              .getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
      assertTrue(english.has("block.mmcece." + block));
      assertTrue(chinese.has("block.mmcece." + block));
    }
  }

  @Test
  void upgradeBusUsesTheLegacySizedTextureAtlas() throws Exception {
    try (var input = Objects
        .requireNonNull(getClass().getResourceAsStream("/assets/mmcece/textures/gui/guiupgradebus.png"))) {
      BufferedImage image = ImageIO.read(input);
      assertEquals(256, image.getWidth());
      assertEquals(256, image.getHeight());
    }
  }

  @Test
  void sharedScrollbarBundlesBothThumbStatesAtTheirActualTextureSize() throws Exception {
    try (var input = Objects
        .requireNonNull(getClass().getResourceAsStream("/assets/mmcece/textures/gui/legacy_scrollbar.png"))) {
      BufferedImage image = ImageIO.read(input);
      assertEquals(24, image.getWidth());
      assertEquals(15, image.getHeight());
      boolean differentStates = false;
      for (int state = 0; state < 2; state++) {
        var colors = new HashSet<Integer>();
        for (int y = 0; y < 15; y++) {
          for (int x = 0; x < 12; x++) {
            int color = image.getRGB(state * 12 + x, y);
            if ((color >>> 24) != 0) {
              colors.add(color);
            }
            differentStates |= image.getRGB(x, y) != image.getRGB(x + 12, y);
          }
        }
        assertTrue(colors.size() > 1, "Scrollbar state is empty or a flat placeholder");
        assertTrue(!colors.contains(0xFFFF00FF), "Scrollbar contains missing-texture magenta");
      }
      assertTrue(differentStates, "Enabled and disabled thumbs must be distinct");
    }
  }

  private JsonObject resource(String path) throws Exception {
    try (var reader = new InputStreamReader(
        Objects.requireNonNull(getClass().getResourceAsStream(path)),
        StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }
}
