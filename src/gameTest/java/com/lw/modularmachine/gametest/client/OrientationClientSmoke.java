package com.lw.modularmachine.gametest.client;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Opt-in, test-source-only native model bake smoke. Stops only the flagged
 * isolated client.
 */
@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class OrientationClientSmoke {
  private static boolean completed;

  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    if (completed || !Boolean.getBoolean("mmcece.or24.smoke")) {
      return;
    }
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.getOverlay() != null || minecraft.screen == null) {
      return;
    }
    var manager = minecraft.getModelManager();
    if (manager.getMissingModel() == null) {
      return;
    }
    int checked = 0;
    for (var state : MachineRegistries.CONTROLLER.get().getStateDefinition().getPossibleStates()) {
      var model = manager.getBlockModelShaper().getBlockModel(state);
      if (model == manager.getMissingModel()) {
        throw new IllegalStateException("Missing controller model: " + state);
      }
      var front = state.getValue(MachineControllerBlock.FACING);
      boolean overlay = model.getQuads(state, front, RandomSource.create(0)).stream()
          .anyMatch(quad -> quad.getSprite().contents().name().getPath().endsWith("overlay_controller"));
      if (!overlay) {
        throw new IllegalStateException("Controller front overlay missing: " + state);
      }
      boolean arrow = model.getQuads(state, front, RandomSource.create(0)).stream()
          .anyMatch(quad -> quad.getSprite().contents().name().getPath().endsWith("controller_up_arrow"));
      if (arrow) {
        throw new IllegalStateException("Controller yellow arrow should be absent: " + state);
      }
      checked++;
    }
    if (checked != 24) {
      throw new IllegalStateException("Expected 24 controller states, got " + checked);
    }
    if (minecraft.getItemRenderer()
        .getModel(MachineRegistries.WRENCH.get().getDefaultInstance(), null, null, 0) == manager.getMissingModel()) {
      throw new IllegalStateException("Machine wrench item model is missing");
    }
    completed = true;
    LogUtils.getLogger().info("OR24_CLIENT_BAKE_PASSED: 24 controller face overlays verified without arrows");
    LogUtils.getLogger().info("OR24_PLAYER_ASSETS_PASSED: controller models and wrench item verified");
    minecraft.stop();
  }
}
