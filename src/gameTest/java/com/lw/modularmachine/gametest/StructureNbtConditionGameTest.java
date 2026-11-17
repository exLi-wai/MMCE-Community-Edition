package com.lw.modularmachine.gametest;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.structure.MachineDefinitionParser;
import com.lw.modularmachine.machine.structure.StructureScriptChecks;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_structure_nbt")
@PrefixGameTestTemplate(false)
public final class StructureNbtConditionGameTest {
  @GameTest(template = "empty16", batch = "structure_nbt", timeoutTicks = 160)
  public static void vanilla_chest_nbt_and_checker_changes_revoke_structure_proof(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(6, 4, 6));
    level.setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    var chestPosition = origin.offset(1, 0, 0);
    level.setBlockAndUpdate(chestPosition, Blocks.CHEST.defaultBlockState());
    var chest = (ChestBlockEntity) level.getBlockEntity(chestPosition);
    rename(chest, "allowed");
    var required = new CompoundTag();
    required.putString("CustomName", chest.saveWithFullMetadata(level.registryAccess()).getString("CustomName"));
    var condition = new StructureScriptChecks.Condition(
        required,
        null,
        context -> context.blockData().getList("Items", Tag.TAG_COMPOUND).isEmpty());
    var machine = ResourceLocation.parse("mmcece:test_structure_nbt_condition");
    var source = JsonParser.parseString("""
        {"parts":[{"offset":[1,0,0],"block":"minecraft:chest","script_condition":"chest"}]}
        """).getAsJsonObject();
    var definition = StructureScriptChecks
        .capture(Map.of("chest", condition), () -> MachineDefinitionParser.parse(machine, source));
    var previous = StructureService.definitionsSnapshot();
    var next = new HashMap<>(previous);
    next.put(machine, definition);
    var restored = new AtomicBoolean();
    Runnable restore = () -> {
      if (restored.compareAndSet(false, true)) {
        StructureService.replaceDefinitions(previous);
      }
    };
    helper.testInfo.addListener(new GameTestListener() {
      @Override
      public void testStructureLoaded(GameTestInfo test) {
      }

      @Override
      public void testPassed(GameTestInfo test, GameTestRunner runner) {
        restore.run();
      }

      @Override
      public void testFailed(GameTestInfo test, GameTestRunner runner) {
        restore.run();
      }

      @Override
      public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) {
      }
    });
    StructureService.replaceDefinitions(next);
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
    controller.bindMachine(machine);
    helper.startSequence()
        .thenWaitUntil(
            () -> helper.assertTrue(controller.canCommitProduction(), "Initial chest condition did not form"))
        .thenExecute(() -> {
          chest.setItem(0, new ItemStack(Items.STONE));
          chest.setChanged();
          helper.assertTrue(
              !controller.canCommitProduction(),
              "Vanilla inventory setChanged retained an outdated checker proof");
          chest.setItem(0, ItemStack.EMPTY);
          chest.setChanged();
        })
        .thenWaitUntil(
            () -> helper.assertTrue(controller.canCommitProduction(), "Empty chest did not re-form after invalidation"))
        .thenExecute(() -> {
          rename(chest, "denied");
          chest.setChanged();
          helper.assertTrue(!controller.canCommitProduction(), "Vanilla NBT setChanged retained an outdated NBT proof");
        }).thenSucceed();
  }

  private static void rename(ChestBlockEntity chest, String name) {
    var stack = new ItemStack(Items.CHEST);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    chest.applyComponentsFromItemStack(stack);
  }
}
