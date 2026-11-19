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
      boolean normal = tier.equals("normal");
      assertEquals(
          normal ? "mmcece:block/ported_shell" : "mmcece:block/framed_ported_shell",
          model.get("parent").getAsString());
      var textures = model.getAsJsonObject("textures");
      assertEquals("mmcece:block/blockcasing_plain", textures.get("casing").getAsString());
      String texture = "mmcece:block/overlay/" + (normal ? "" : "port_icons/") + "upgrades/overlay_upgrade_bus_" + tier;
      assertEquals(texture, textures.get(normal ? "overlay" : "icon").getAsString());
      BufferedImage image = texture(texture);
      assertEquals(32, image.getWidth());
      assertEquals(32, image.getHeight());
      if (!normal) {
        assertStaticOverlayMatchesOriginal(tier, image);
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

  private void assertStaticOverlayMatchesOriginal(String tier, BufferedImage icon) throws Exception {
    var parent = resource("/assets/mmcece/models/block/framed_ported_shell.json");
    String frameTexture = parent.getAsJsonObject("textures").get("frame").getAsString();
    assertEquals("mmcece:block/overlay/casings/overlay_reinforced", frameTexture);
    BufferedImage frame = texture(frameTexture);
    assertEquals(16, frame.getWidth());
    assertEquals(16, frame.getHeight());
    BufferedImage original = texture("mmcece:block/overlay/upgrades/overlay_upgrade_bus_" + tier);
    assertEquals(icon.getWidth(), original.getWidth());
    assertEquals(icon.getHeight(), original.getHeight());
    for (int y = 0; y < icon.getHeight(); y++) {
      for (int x = 0; x < icon.getWidth(); x++) {
        int framePixel = frame.getRGB(x / 2, y / 2);
        int iconPixel = icon.getRGB(x, y);
        if ((framePixel >>> 24) != 0) {
          assertEquals(255, framePixel >>> 24);
          assertEquals(0, iconPixel >>> 24, "Icon must not redraw the shared frame: " + tier);
        }
        assertEquals(
            original.getRGB(x, y),
            (framePixel >>> 24) == 0 ? iconPixel : framePixel,
            "Static overlay changed for " + tier + " at " + x + "," + y);
      }
    }
  }

  private BufferedImage texture(String location) throws Exception {
    String path = "/assets/mmcece/textures/" + location.substring("mmcece:".length()) + ".png";
    try (var input = Objects.requireNonNull(getClass().getResourceAsStream(path), path)) {
      return Objects.requireNonNull(ImageIO.read(input), path);
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
