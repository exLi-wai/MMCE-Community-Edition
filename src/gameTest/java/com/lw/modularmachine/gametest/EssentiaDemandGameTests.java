package com.lw.modularmachine.gametest;

import com.lw.modularmachine.integration.thaumaturge.EssentiaHatch;
import com.lw.modularmachine.integration.thaumaturge.PipeEssentiaHatch;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_resource_regression")
@PrefixGameTestTemplate(false)
public final class EssentiaDemandGameTests {
  private EssentiaDemandGameTests() {
  }

  @GameTest(template = "empty16")
  public static void payments_do_not_reopen_delivered_essentia_requests(GameTestHelper helper) {
    if (!Mods.THAUMATURGE.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var position = helper.absolutePos(new BlockPos(3, 2, 3));
    level.setBlockAndUpdate(position, MachineRegistries.ESSENTIA_INPUT.get().defaultBlockState());
    var hatch = (PipeEssentiaHatch) level.getBlockEntity(position);
    var key = ResourceLocation.parse("thaumaturge:aer");
    var aspect = EssentiaHatch.resolveAspect(level.registryAccess(), key);
    helper.assertTrue(hatch.request(key, 5, null) == 5, "Expected five units of outstanding demand");
    helper.assertTrue(
        hatch.insert(aspect, 3, Direction.UP, true) == 3 && hatch.roomFor(aspect, Direction.UP) == 5,
        "Simulated delivery retired actual demand");
    helper.assertTrue(hatch.insert(aspect, 3, Direction.UP, false) == 3, "Initial delivery failed");
    hatch.acquire(key, -2).resolve(false);
    helper.assertTrue(
        hatch.available(key) == 3 && hatch.roomFor(aspect, Direction.UP) == 2,
        "Rolled back payment changed the outstanding delivery");
    hatch.acquire(key, -2).resolve(true);
    helper.assertTrue(hatch.roomFor(aspect, Direction.UP) == 2, "Payment reopened a partially delivered request");
    helper.assertTrue(hatch.insert(aspect, 5, Direction.UP, false) == 2, "Accepted more than the undelivered balance");
    hatch.acquire(key, -3).resolve(true);
    helper.assertTrue(
        hatch.available(key) == 0 && hatch.insert(aspect, 1, Direction.UP, false) == 0,
        "Emptying the buffer reopened a settled request");
    helper.succeed();
  }
}
