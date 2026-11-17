package com.lw.modularmachine.gametest;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.structure.MachineDefinitionParser;
import com.lw.modularmachine.machine.structure.StructureScriptChecks;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_structure_nbt")
@PrefixGameTestTemplate(false)
public final class NetworkPortStructureConditionGameTest {
  @GameTest(template = "empty16", batch = "network_config", timeoutTicks = 160)
  public static void network_configuration_revokes_structure_proof_without_early_lease_notifications(GameTestHelper helper) {
    helper.assertTrue(Mods.AE2.isLoaded(), "This regression requires the real AE2 integration");
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(6, 4, 6));
    level.setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    var portPosition = origin.offset(1, 0, 0);
    level.setBlockAndUpdate(
        portPosition,
        MachineRegistries.NETWORK_PORTS.get("me_item_input_bus").get().defaultBlockState());
    var port = (NetworkPortBlockEntity) level.getBlockEntity(portPosition);
    var stone = new NetworkPortResource.Item(new ItemStack(Items.STONE));
    helper.assertTrue(port.configure(0, stone, 1, port.configRevision()), "Initial network configuration was rejected");
    var condition = new StructureScriptChecks.Condition(null, null, context -> {
      var data = context.blockData();
      var config = data.getCompound("network_port").getList("config", Tag.TAG_COMPOUND);
      return config.size() == 9
          && config.getCompound(0).getInt("amount") == 1
          && data.getCompound("mmcece").getCompound("items").getList("BulkItems", Tag.TAG_COMPOUND).isEmpty();
    });
    var machine = ResourceLocation.parse("mmcece:test_network_item_color");
    var source = JsonParser.parseString("""
        {"parts":[{"offset":[1,0,0],"block":"mmcece:me_item_input_bus","script_condition":"network"}]}
        """).getAsJsonObject();
    var definition = StructureScriptChecks
        .capture(Map.of("network", condition), () -> MachineDefinitionParser.parse(machine, source));
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
            () -> helper.assertTrue(controller.canCommitProduction(), "Initial network condition did not form"))
        .thenExecute(() -> {
          long revision = port.configRevision();
          helper.assertTrue(port.configure(0, stone, 1, revision), "Same-value network edit was rejected");
          helper.assertTrue(port.configRevision() == revision, "Same-value edit changed the configuration revision");
          helper.assertTrue(controller.canCommitProduction(), "Same-value edit revoked the structure proof");
          helper.assertTrue(!port.configure(0, stone, 2, revision - 1), "Stale network edit was accepted");
          helper.assertTrue(controller.canCommitProduction(), "Rejected network edit revoked the structure proof");
          helper.assertTrue(port.configure(0, stone, 2, revision), "Target change was rejected");
          helper.assertTrue(!controller.canCommitProduction(), "Target change retained the old structure proof");
          helper.assertTrue(port.configure(0, stone, 1, port.configRevision()), "Restoring target was rejected");
        })
        .thenWaitUntil(
            () -> helper.assertTrue(controller.canCommitProduction(), "Restored network target did not re-form"))
        .thenExecute(() -> {
          helper.assertTrue(
              port.configure(0, null, 0, port.configRevision()),
              "Clearing network configuration was rejected");
          helper
              .assertTrue(!controller.canCommitProduction(), "Cleared configuration retained the old structure proof");
          helper.assertTrue(
              port.configure(0, stone, 1, port.configRevision()),
              "Restoring cleared configuration was rejected");
        })
        .thenWaitUntil(
            () -> helper.assertTrue(controller.canCommitProduction(), "Restored network configuration did not re-form"))
        .thenExecute(() -> {
          var contents = NonNullList.withSize(port.inventory().getSlots(), ItemStack.EMPTY);
          contents.set(0, new ItemStack(Items.STONE));
          try (var lease = port.acquireItemStart(port.resourceRevision())) {
            helper.assertTrue(controller.canCommitProduction(), "Acquiring a resource lease revoked the proof");
            lease.replacePrepared(contents);
            helper.assertTrue(
                controller.canCommitProduction(),
                "Prepared resource write published an early notification");
            lease.resolve(true);
            helper.assertTrue(controller.canCommitProduction(), "Resolving before publication revoked the proof");
            lease.publish();
            helper.assertTrue(
                !controller.canCommitProduction(),
                "Published resource write retained the old checker proof");
          }
        }).thenSucceed();
  }
}
