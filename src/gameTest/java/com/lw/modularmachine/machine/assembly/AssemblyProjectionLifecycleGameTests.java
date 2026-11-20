package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.authlib.GameProfile;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class AssemblyProjectionLifecycleGameTests {
  @GameTest(templateNamespace = "mmcece_structure_nbt", template = "empty16", timeoutTicks = 200)
  public static void projection_permission_loss_clears_only_the_current_request(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 4, 7));
    level.setBlockAndUpdate(origin, MachineRegistries.CONTROLLER.get().defaultBlockState());
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
    var machine = ResourceLocation.parse("mmcece:test_schema2_axis");
    controller.bindMachine(machine);
    var definition = StructureService.definition(machine);
    var orientation = controller.structureOrientation(level);
    var projections = new AssemblyProjectionCoordinator();
    var player = FakePlayerFactory.get(level, new GameProfile(new UUID(0, 0xAD21L), "projection_test"));
    player.setGameMode(GameType.SURVIVAL);
    player.setPos(origin.getCenter());
    projections.request(player, controller, definition, orientation, Map.of(), 1);
    player.setGameMode(GameType.SPECTATOR);
    projections.prepare(player, Long.MAX_VALUE);
    helper.assertFalse(
        projections.pendingPlayers().contains(player.getUUID()),
        "Spectator retained a pending projection");

    player.setGameMode(GameType.SURVIVAL);
    projections.request(player, controller, definition, orientation, Map.of(), 1);
    helper.startSequence().thenWaitUntil(() -> {
      projections.prepare(player, Long.MAX_VALUE);
      helper.assertTrue(projections.inspect(player).isPresent(), "Projection is not ready");
    }).thenExecute(() -> {
      long oldRequest = projections.inspect(player).orElseThrow().request();
      helper.assertTrue(projections.send(player, Long.MAX_VALUE, 1) == 1, "Ready projection was not delivered");
      player.setGameMode(GameType.SPECTATOR);
      projections.send(player, Long.MAX_VALUE, 0);
      player.setGameMode(GameType.SURVIVAL);
      helper.assertFalse(projections.players().contains(player.getUUID()), "Old projection survived permission loss");
      projections.request(player, controller, definition, orientation, Map.of(), 1);
      projections.stop(player, oldRequest);
      helper.assertTrue(projections.pendingPlayers().contains(player.getUUID()), "A late stop removed the replacement");
      projections.clear();
    }).thenSucceed();
  }
}
