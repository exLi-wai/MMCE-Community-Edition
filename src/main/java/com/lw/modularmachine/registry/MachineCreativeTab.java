package com.lw.modularmachine.registry;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import org.jetbrains.annotations.ApiStatus;
import static com.lw.modularmachine.registry.MachineRegistries.BLUEPRINTS;
import static com.lw.modularmachine.registry.MachineRegistries.CASINGS;
import static com.lw.modularmachine.registry.MachineRegistries.CONFIGURATION_CARD;
import static com.lw.modularmachine.registry.MachineRegistries.CONTROLLER;
import static com.lw.modularmachine.registry.MachineRegistries.DEDICATED_CONTROLLERS;
import static com.lw.modularmachine.registry.MachineRegistries.DEDICATED_FACTORIES;
import static com.lw.modularmachine.registry.MachineRegistries.FACTORY_CONTROLLER;
import static com.lw.modularmachine.registry.MachineRegistries.ME_PATTERN_MIRROR;
import static com.lw.modularmachine.registry.MachineRegistries.ME_PATTERN_PROVIDER;
import static com.lw.modularmachine.registry.MachineRegistries.NETWORK_PORTS;
import static com.lw.modularmachine.registry.MachineRegistries.PARALLEL_CONTROLLERS;
import static com.lw.modularmachine.registry.MachineRegistries.PORTS;
import static com.lw.modularmachine.registry.MachineRegistries.PROJECTOR;
import static com.lw.modularmachine.registry.MachineRegistries.RANGED_CONSTRUCT_TOOL;
import static com.lw.modularmachine.registry.MachineRegistries.SMART_INTERFACE;
import static com.lw.modularmachine.registry.MachineRegistries.UPGRADE_BUSES;
import static com.lw.modularmachine.registry.MachineRegistries.WRENCH;

/**
 * Creative inventory ordering is independent of block registration ordering.
 */
@ApiStatus.Internal
final class MachineCreativeTab {
  private MachineCreativeTab() {
  }

  static CreativeModeTab create() {
    return CreativeModeTab.builder().title(Component.translatable("itemGroup.mmcece.machines"))
        .icon(() -> CONTROLLER.asItem().getDefaultInstance()).displayItems(MachineCreativeTab::displayItems).build();
  }

  private static void displayItems(CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) {
    output.accept(CONTROLLER.get());
    output.accept(FACTORY_CONTROLLER.get());
    DEDICATED_CONTROLLERS.values().forEach(block -> output.accept(block.get()));
    DEDICATED_FACTORIES.values().forEach(block -> output.accept(block.get()));
    output.accept(SMART_INTERFACE.get());
    if (MachineRegistries.LASER_INPUT_HATCH != null) {
      output.accept(MachineRegistries.LASER_INPUT_HATCH.get());
    }
    if (ME_PATTERN_PROVIDER != null) {
      output.accept(ME_PATTERN_PROVIDER.get());
      output.accept(ME_PATTERN_MIRROR.get());
    }
    PARALLEL_CONTROLLERS.values().forEach(block -> output.accept(block.get()));
    UPGRADE_BUSES.values().forEach(block -> output.accept(block.get()));
    output.accept(WRENCH.get());
    output.accept(CONFIGURATION_CARD.get());
    BLUEPRINTS.values().forEach(blueprint -> output.accept(blueprint.get()));
    output.accept(PROJECTOR.get());
    output.accept(RANGED_CONSTRUCT_TOOL.get());
    CASINGS.values().forEach(block -> output.accept(block.get()));
    PORTS.values().forEach(block -> output.accept(block.get()));
    MachineRegistries.EMBER_INPUT_HATCHES.values().forEach(block -> output.accept(block.get()));
    MachineRegistries.TICKING_RECIPE_HATCHES.values().forEach(block -> output.accept(block.get()));
    NETWORK_PORTS.values().forEach(block -> output.accept(block.get()));
  }
}
