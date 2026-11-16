package com.lw.modularmachine.registry;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineCasingBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.block.NetworkPortBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.item.ControllerBlockItem;
import com.lw.modularmachine.item.DedicatedControllerItem;
import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.item.MachineControllerItem;
import com.lw.modularmachine.item.NetworkPortBlockItem;
import com.lw.modularmachine.item.PatternMirrorBlockItem;
import com.lw.modularmachine.item.PatternProviderBlockItem;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.network.MachineNetworkPlatform;
import com.lw.modularmachine.machine.ParallelControllerTier;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.UpgradeBusTier;
import java.util.Locale;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.ApiStatus;
import static com.lw.modularmachine.registry.MachineRegistries.BLOCKS;
import static com.lw.modularmachine.registry.MachineRegistries.BLUEPRINTS;
import static com.lw.modularmachine.registry.MachineRegistries.CASINGS;
import static com.lw.modularmachine.registry.MachineRegistries.CONTROLLER;
import static com.lw.modularmachine.registry.MachineRegistries.DEDICATED_CONTROLLERS;
import static com.lw.modularmachine.registry.MachineRegistries.DEDICATED_FACTORIES;
import static com.lw.modularmachine.registry.MachineRegistries.FACTORY_CONTROLLER;
import static com.lw.modularmachine.registry.MachineRegistries.ITEMS;
import static com.lw.modularmachine.registry.MachineRegistries.ME_PATTERN_MIRROR;
import static com.lw.modularmachine.registry.MachineRegistries.ME_PATTERN_PROVIDER;
import static com.lw.modularmachine.registry.MachineRegistries.NETWORK_PORTS;
import static com.lw.modularmachine.registry.MachineRegistries.PARALLEL_CONTROLLERS;
import static com.lw.modularmachine.registry.MachineRegistries.PORTS;
import static com.lw.modularmachine.registry.MachineRegistries.SMART_INTERFACE;
import static com.lw.modularmachine.registry.MachineRegistries.UPGRADE_BUSES;

/**
 * Ordered block/item variants; called from the identity table after its base
 * holders exist.
 */
@ApiStatus.Internal
final class MachineContentRegistration {
  private MachineContentRegistration() {
  }

  static void registerVariants() {
    registerPatternItems();
    registerBlueprints();
    registerDedicatedControllers();
    registerBaseItems();
    registerParallelControllers();
    registerUpgradeBuses();
    registerCasings();
    registerResourcePorts();
    registerNetworkPorts();
  }

  private static void registerPatternItems() {
    if (ME_PATTERN_PROVIDER != null) {
      ITEMS.register(
          "me_pattern_provider",
          () -> new PatternProviderBlockItem(ME_PATTERN_PROVIDER.get(), new Item.Properties()));
      ITEMS.register(
          "me_pattern_mirror",
          () -> new PatternMirrorBlockItem(ME_PATTERN_MIRROR.get(), new Item.Properties()));
    }
  }

  private static void registerBlueprints() {
    for (var machine : DedicatedControllerCatalog.machines()) {
      String path = "blueprint/" + machine.id().getNamespace() + "/" + machine.id().getPath();
      var blueprint = ITEMS.register(path, () -> new MachineBlueprintItem(new Item.Properties().stacksTo(16), machine));
      BLUEPRINTS.put(machine.id(), blueprint);
    }
  }

  private static void registerDedicatedControllers() {
    for (var entry : DedicatedControllerCatalog.entries()) {
      String path = entry.registryPath();
      if (entry.factory()) {
        var block = BLOCKS.register(path, () -> new FactoryControllerBlock(properties().strength(5F, 10F), entry));
        DEDICATED_FACTORIES.put(entry.id(), block);
        ITEMS.register(path, () -> new DedicatedControllerItem(block.get(), new Item.Properties(), entry));
      } else {
        var block = BLOCKS.register(path, () -> new MachineControllerBlock(properties().strength(5F, 10F), entry));
        DEDICATED_CONTROLLERS.put(entry.id(), block);
        ITEMS.register(path, () -> new DedicatedControllerItem(block.get(), new Item.Properties(), entry));
      }
    }
  }

  private static void registerBaseItems() {
    ITEMS.register("machine_controller", () -> new MachineControllerItem(CONTROLLER.get(), new Item.Properties()));
    ITEMS
        .register("factory_controller", () -> new ControllerBlockItem(FACTORY_CONTROLLER.get(), new Item.Properties()));
    ITEMS.registerSimpleBlockItem(SMART_INTERFACE);
  }

  private static void registerParallelControllers() {
    for (var tier : ParallelControllerTier.values()) {
      String id = "parallel_controller_" + tier.path();
      var block = BLOCKS.register(id, () -> new ParallelControllerBlock(properties().strength(5F, 10F), tier));
      PARALLEL_CONTROLLERS.put(id, block);
      ITEMS.registerSimpleBlockItem(block);
    }
  }

  private static void registerUpgradeBuses() {
    for (var tier : UpgradeBusTier.values()) {
      String id = "upgrade_bus_" + tier.name().toLowerCase(Locale.ROOT);
      var block = BLOCKS.register(id, () -> new UpgradeBusBlock(properties().strength(3F, 10F), tier));
      UPGRADE_BUSES.put(id, block);
      ITEMS.registerSimpleBlockItem(block);
    }
  }

  private static void registerCasings() {
    for (String name : new String[]{"plain", "vent", "firebox", "gearbox", "reinforced", "circuitry"}) {
      String id = "machine_casing_" + name;
      DeferredBlock<MachineCasingBlock> block = BLOCKS
          .register(id, () -> new MachineCasingBlock(properties().strength(2F, 10F)));
      CASINGS.put(name, block);
      ITEMS.registerSimpleBlockItem(block);
    }
  }

  private static void registerResourcePorts() {
    for (PortKind kind : PortKind.values()) {
      if (kind.resource() == ResourceType.CHEMICAL && !ChemicalPlatform.available()) {
        continue;
      }
      for (HatchTier tier : HatchTier.forResource(kind.resource())) {
        String id = kind.path() + "_" + tier.name();
        DeferredBlock<MachinePortBlock> block = BLOCKS
            .register(id, () -> new MachinePortBlock(properties().strength(3F, 10F), kind, tier));
        PORTS.put(id, block);
        ITEMS.registerSimpleBlockItem(block);
      }
    }
  }

  private static void registerNetworkPorts() {
    if (MachineNetworkPlatform.available()) {
      for (PortKind kind : new PortKind[]{PortKind.ITEM_INPUT, PortKind.ITEM_OUTPUT, PortKind.FLUID_INPUT,
          PortKind.FLUID_OUTPUT, PortKind.CHEMICAL_INPUT, PortKind.CHEMICAL_OUTPUT}) {
        if (kind.resource() == ResourceType.CHEMICAL && !MachineNetworkPlatform.chemicalAvailable()) {
          continue;
        }
        String direction = kind.isInput() ? "input" : "output";
        String id = "me_" + kind.resource().name().toLowerCase(Locale.ROOT) + "_" + direction + "_bus";
        var block = BLOCKS.register(id, () -> new NetworkPortBlock(properties().strength(3F, 10F), kind));
        NETWORK_PORTS.put(id, block);
        ITEMS.register(id, () -> new NetworkPortBlockItem(block.get(), new Item.Properties()));
      }
    }
  }

  static BlockBehaviour.Properties properties() {
    return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).sound(SoundType.METAL).requiresCorrectToolForDrops();
  }
}
