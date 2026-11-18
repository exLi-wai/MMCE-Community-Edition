package com.lw.modularmachine.gametest;

import com.breakinblocks.neovitae.common.entity.projectile.EntityMeteor;
import com.lw.modularmachine.integration.neovitae.MeteorHatchBlockEntity;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_resource_regression")
@PrefixGameTestTemplate(false)
public final class MeteorHatchRemovalGameTests {
  private MeteorHatchRemovalGameTests() {
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void paid_meteor_launches_after_hatch_replacement(GameTestHelper helper) {
    if (!Mods.NEO_VITAE.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var position = helper.absolutePos(new BlockPos(6, 14, 6));
    var catalyst = ResourceLocation.withDefaultNamespace("iron_block");
    level.setBlockAndUpdate(position, MachineRegistries.METEOR_OUTPUT.get().defaultBlockState());
    var hatch = (MeteorHatchBlockEntity) level.getBlockEntity(position);
    hatch.acquire(catalyst).resolve(false);
    helper.assertTrue(
        !hatch.saveWithoutMetadata(level.registryAccess()).contains("meteor_launch"),
        "Rollback kept a launch");
    hatch.acquire(catalyst).resolve(true);
    hatch.publishLaunch();
    var launch = hatch.saveWithoutMetadata(level.registryAccess()).getCompound("meteor_launch");
    var id = launch.getUUID("id");
    level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
    helper.succeedWhen(() -> {
      var meteor = level.getEntity(id);
      helper.assertTrue(meteor instanceof EntityMeteor, "The paid meteor was lost after replacing its hatch");
      meteor.discard();
    });
  }
}
