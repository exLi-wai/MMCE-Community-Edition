package com.lw.modularmachine.registry;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineCasingBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.blockentity.PatternMirrorBlockEntity;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.blockentity.EmberInputHatchBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import java.util.stream.Stream;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.ApiStatus;
import static com.lw.modularmachine.registry.MachineRegistries.CASINGS;
import static com.lw.modularmachine.registry.MachineRegistries.CONTROLLER;
import static com.lw.modularmachine.registry.MachineRegistries.DEDICATED_CONTROLLERS;
import static com.lw.modularmachine.registry.MachineRegistries.DEDICATED_FACTORIES;
import static com.lw.modularmachine.registry.MachineRegistries.FACTORY_CONTROLLER;
import static com.lw.modularmachine.registry.MachineRegistries.ME_PATTERN_MIRROR;
import static com.lw.modularmachine.registry.MachineRegistries.ME_PATTERN_PROVIDER;
import static com.lw.modularmachine.registry.MachineRegistries.NETWORK_PORTS;
import static com.lw.modularmachine.registry.MachineRegistries.PARALLEL_CONTROLLERS;
import static com.lw.modularmachine.registry.MachineRegistries.PORTS;
import static com.lw.modularmachine.registry.MachineRegistries.SMART_INTERFACE;
import static com.lw.modularmachine.registry.MachineRegistries.UPGRADE_BUSES;

/**
 * Deferred entity factories; valid block sets are resolved only during the
 * registry event.
 */
@ApiStatus.Internal
final class MachineBlockEntityFactories {
  private MachineBlockEntityFactories() {
  }

  static BlockEntityType<PatternProviderBlockEntity> patternProvider() {
    return BlockEntityType.Builder.of(
        PatternProviderBlockEntity::new,
        ME_PATTERN_PROVIDER == null ? new Block[0] : new Block[]{ME_PATTERN_PROVIDER.get()}).build(null);
  }

  static BlockEntityType<PatternMirrorBlockEntity> patternMirror() {
    return BlockEntityType.Builder.of(
        PatternMirrorBlockEntity::new,
        ME_PATTERN_MIRROR == null ? new Block[0] : new Block[]{ME_PATTERN_MIRROR.get()}).build(null);
  }

  static BlockEntityType<MachinePortBlockEntity> machinePort() {
    return BlockEntityType.Builder
        .of(MachinePortBlockEntity::new, PORTS.values().stream().map(DeferredBlock::get).toArray(Block[]::new))
        .build(null);
  }

  static BlockEntityType<MachineCasingBlockEntity> machineCasing() {
    return BlockEntityType.Builder
        .of(MachineCasingBlockEntity::new, CASINGS.values().stream().map(DeferredBlock::get).toArray(Block[]::new))
        .build(null);
  }

  static BlockEntityType<NetworkPortBlockEntity> networkPort() {
    return BlockEntityType.Builder
        .of(NetworkPortBlockEntity::new, NETWORK_PORTS.values().stream().map(DeferredBlock::get).toArray(Block[]::new))
        .build(null);
  }

  static BlockEntityType<MachineControllerBlockEntity> machineController() {
    return BlockEntityType.Builder.of(
        MachineControllerBlockEntity::new,
        Stream.concat(Stream.of(CONTROLLER.get()), DEDICATED_CONTROLLERS.values().stream().map(DeferredBlock::get))
            .toArray(Block[]::new))
        .build(null);
  }

  static BlockEntityType<FactoryControllerBlockEntity> factoryController() {
    return BlockEntityType.Builder.of(
        FactoryControllerBlockEntity::new,
        Stream
            .concat(Stream.of(FACTORY_CONTROLLER.get()), DEDICATED_FACTORIES.values().stream().map(DeferredBlock::get))
            .toArray(Block[]::new))
        .build(null);
  }

  static BlockEntityType<UpgradeBusBlockEntity> upgradeBus() {
    return BlockEntityType.Builder
        .of(UpgradeBusBlockEntity::new, UPGRADE_BUSES.values().stream().map(DeferredBlock::get).toArray(Block[]::new))
        .build(null);
  }

  static BlockEntityType<ParallelControllerBlockEntity> parallelController() {
    return BlockEntityType.Builder.of(
        ParallelControllerBlockEntity::new,
        PARALLEL_CONTROLLERS.values().stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null);
  }

  static BlockEntityType<SmartInterfaceBlockEntity> smartInterface() {
    return BlockEntityType.Builder.of(SmartInterfaceBlockEntity::new, SMART_INTERFACE.get()).build(null);
  }

  static BlockEntityType<EmberInputHatchBlockEntity> emberInputHatch() {
    return BlockEntityType.Builder
        .of(
            EmberInputHatchBlockEntity::new,
            MachineRegistries.EMBER_INPUT_HATCHES.values().stream().map(DeferredBlock::get).toArray(Block[]::new))
        .build(null);
  }

}
