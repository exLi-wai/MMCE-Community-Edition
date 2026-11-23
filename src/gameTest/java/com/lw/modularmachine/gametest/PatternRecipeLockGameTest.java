package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.pattern.PatternDefinition;
import com.lw.modularmachine.machine.network.pattern.PatternRecipeDefinition;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class PatternRecipeLockGameTest {
  @GameTest(template = "empty16", timeoutTicks = 160)
  public static void provider_rechecks_factory_recipe_lock_and_pattern_amounts(GameTestHelper helper) {
    if (!Mods.AE2.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var pos = helper.absolutePos(new BlockPos(7, 5, 7));
    level.setBlockAndUpdate(pos, MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState());
    level.setBlockAndUpdate(pos.offset(-1, 0, 1), MachineRegistries.ME_PATTERN_PROVIDER.get().defaultBlockState());
    level.setBlockAndUpdate(pos.offset(1, 0, 1), MachineRegistries.ME_PATTERN_MIRROR.get().defaultBlockState());
    var factory = (FactoryControllerBlockEntity) level.getBlockEntity(pos);
    var provider = (PatternProviderBlockEntity) level.getBlockEntity(pos.offset(-1, 0, 1));
    var machine = ResourceLocation.fromNamespaceAndPath("mmcece", "test_pattern_color");
    var recipe = ResourceLocation.fromNamespaceAndPath("mmcece", "mmcece/test_pattern_lock");
    factory.bindMachine(machine);
    var thread = new FactoryThreadState(
        "pattern",
        machine,
        recipe,
        true,
        0,
        1,
        0,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        UpgradeSnapshot.identity(0));
    factory.threadConfiguration().replaceThreads(List.of(thread));
    helper.runAfterDelay(3, () -> {
      for (int attempt = 0; attempt < 200 && !factory.canCommitStructure(); attempt++) {
        StructureService.advance(level, 512, 64, 50_000_000L);
      }
      helper.assertTrue(factory.canCommitStructure(), "Factory must own the pattern gateway");
      var catalog = MachineRecipeService
          .catalogForFactory(factory, thread.threadId(), MachineRecipeService.catalogFor(factory).orElseThrow())
          .orElseThrow();
      var registries = level.registryAccess();
      var definition = PatternDefinition.capture(new ItemStack(Items.PAPER), registries);
      var input = PatternResourceKey.capture(new NetworkPortResource.Item(new ItemStack(Items.DIRT)), registries);
      var output = PatternResourceKey
          .capture(new NetworkPortResource.Item(new ItemStack(Items.IRON_INGOT)), registries);
      var valid = new PatternRecipeDefinition(definition, Map.of(input, 1L), Map.of(output, 1L));
      var wrongAmount = new PatternRecipeDefinition(definition, Map.of(input, 2L), Map.of(output, 1L));
      PatternProductionService.observe(factory, catalog, thread.threadId());
      helper.assertTrue(PatternProductionService.isCompatible(provider, valid), "Locked candidate should match");
      helper.assertTrue(!PatternProductionService.isCompatible(provider, wrongAmount), "Lock bypassed input amount");
      factory.threadConfiguration().replaceThreads(List.of(thread.withLockedRecipe(recipe.withSuffix("_missing"))));
      PatternProductionService.observe(factory, catalog, thread.threadId());
      helper
          .assertTrue(!PatternProductionService.isCompatible(provider, valid), "Missing lock fell back to all recipes");
      factory.threadConfiguration().replaceThreads(List.of(thread.withLockedRecipe(null)));
      PatternProductionService.observe(factory, catalog, thread.threadId());
      helper.assertTrue(
          PatternProductionService.isCompatible(provider, valid),
          "Unlock should allow the native recipe again");
      helper.succeed();
    });
  }

  private PatternRecipeLockGameTest() {
  }
}
