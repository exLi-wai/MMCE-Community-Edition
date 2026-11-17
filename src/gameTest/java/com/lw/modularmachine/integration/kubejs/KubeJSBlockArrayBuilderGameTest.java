package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_structure_nbt")
@PrefixGameTestTemplate(false)
public final class KubeJSBlockArrayBuilderGameTest {
  @GameTest(template = "empty16", batch = "block_array_identity")
  public static void replacing_an_explicit_part_keeps_existing_modifier_references(GameTestHelper helper) {
    var initial = JsonParser.parseString("""
        [{"part_id":"frame","offset":[2,0,0],"block":"minecraft:stone"}]
        """).getAsJsonArray();
    var array = new KubeJSBlockArrayBuilder(null, initial);
    array.addBlock(2, 0, 0, "minecraft:iron_block");
    array.addBlock(2, 0, 0, "minecraft:gold_block");
    var captured = array.capture(null);

    helper.assertTrue(captured.size() == 1, "Replacement added another part");
    helper.assertTrue(
        "frame".equals(captured.get(0).getAsJsonObject().get("part_id").getAsString()),
        "Captured replacement lost the explicit part ID");
    helper.assertTrue("frame".equals(array.partId(new BlockPos(2, 0, 0))), "Modifier reference changed");
    helper.assertTrue(
        "minecraft:stone".equals(initial.get(0).getAsJsonObject().get("block").getAsString()),
        "Replacement mutated the source declaration");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "block_array_identity")
  public static void implicit_native_ids_stay_stable_when_read_and_replaced(GameTestHelper helper) {
    var initial = JsonParser.parseString("""
        [{"offset":[-2,3,4],"block":"minecraft:stone"}]
        """).getAsJsonArray();
    var array = new KubeJSBlockArrayBuilder(null, initial);
    var position = new BlockPos(-2, 3, 4);
    helper.assertTrue("offset:-2,3,4".equals(array.partId(position)), "Implicit native part ID changed");
    helper.assertTrue(
        !array.capture(null).get(0).getAsJsonObject().has("part_id"),
        "Reading a part ID mutated its declaration");

    array.addBlock(-2, 3, 4, "minecraft:iron_block");

    helper.assertTrue(
        "offset:-2,3,4".equals(array.partId(position)),
        "Replacement changed the implicit modifier reference");
    helper.assertTrue(
        "offset:-2,3,4".equals(array.capture(null).get(0).getAsJsonObject().get("part_id").getAsString()),
        "Captured replacement lost the implicit native part ID");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "block_array_identity")
  public static void coordinate_products_keep_explicit_and_implicit_part_ids(GameTestHelper helper) {
    var initial = JsonParser.parseString("""
        [{"part_id":"left","offset":[-1,0,1],"block":"minecraft:stone"},
         {"offset":[1,0,1],"block":"minecraft:stone"}]
        """).getAsJsonArray();
    var array = new KubeJSBlockArrayBuilder(null, initial);
    array.addBlock(new int[]{-1, 1}, new int[]{0}, new int[]{1}, "minecraft:iron_block");
    var copy = KubeJSBlockArrayBuilder.newBuilder(array);

    helper
        .assertTrue("left".equals(copy.partId(new BlockPos(-1, 0, 1))), "Coordinate product lost the explicit part ID");
    helper.assertTrue(
        "offset:1,0,1".equals(copy.partId(new BlockPos(1, 0, 1))),
        "Coordinate product lost the implicit native part ID");
    helper.assertTrue(copy.capture(null).size() == 2, "Copy changed the number of declared parts");
    helper.succeed();
  }
}
