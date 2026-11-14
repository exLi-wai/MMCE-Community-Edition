package com.lw.modularmachine.registry;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.MachineCasingBlock;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.block.NetworkPortBlock;
import com.lw.modularmachine.block.PatternProviderBlock;
import com.lw.modularmachine.block.PatternMirrorBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.block.SmartInterfaceBlock;
import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineCasingBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.blockentity.PatternMirrorBlockEntity;
import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.item.DedicatedControllerItem;
import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.item.MachineConstructionTool;
import com.lw.modularmachine.item.MachineControllerItem;
import com.lw.modularmachine.item.ControllerBlockItem;
import com.lw.modularmachine.item.MachineWrenchItem;
import com.lw.modularmachine.item.NetworkPortBlockItem;
import com.lw.modularmachine.item.PatternProviderBlockItem;
import com.lw.modularmachine.item.PatternMirrorBlockItem;
import com.lw.modularmachine.util.Mods;
import com.lw.modularmachine.item.ConfigurationCardItem;
import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.ParallelControllerTier;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.machine.network.MachineNetworkPlatform;
import com.lw.modularmachine.machine.UpgradeBusTier;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import com.lw.modularmachine.menu.FactoryControllerMenu;
import com.lw.modularmachine.menu.GroupInputMenu;
import com.lw.modularmachine.menu.MachineControllerMenu;
import com.lw.modularmachine.menu.MachinePortMenu;
import com.lw.modularmachine.menu.NetworkPortMenu;
import com.lw.modularmachine.menu.PatternProviderMenu;
import com.lw.modularmachine.menu.MachineResourceMenu;
import com.lw.modularmachine.menu.ParallelControllerMenu;
import com.lw.modularmachine.menu.SmartInterfaceMenu;
import com.lw.modularmachine.menu.UpgradeBusMenu;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MachineRegistries {

  public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ModularMachine.MODID);
  public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModularMachine.MODID);
  public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister
      .create(Registries.BLOCK_ENTITY_TYPE, ModularMachine.MODID);
  public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister
      .create(Registries.MENU, ModularMachine.MODID);
  public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister
      .create(Registries.CREATIVE_MODE_TAB, ModularMachine.MODID);
  public static final Map<String, DeferredBlock<MachineControllerBlock>> DEDICATED_CONTROLLERS = new LinkedHashMap<>();
  public static final Map<String, DeferredBlock<FactoryControllerBlock>> DEDICATED_FACTORIES = new LinkedHashMap<>();
  public static final Map<ResourceLocation, DeferredItem<MachineBlueprintItem>> BLUEPRINTS = new LinkedHashMap<>();
  public static final Map<String, DeferredBlock<? extends Block>> CASINGS = new LinkedHashMap<>();
  public static final Map<String, DeferredBlock<MachinePortBlock>> PORTS = new LinkedHashMap<>();
  public static final Map<String, DeferredBlock<NetworkPortBlock>> NETWORK_PORTS = new LinkedHashMap<>();
  public static final Map<String, DeferredBlock<UpgradeBusBlock>> UPGRADE_BUSES = new LinkedHashMap<>();
  public static final Map<String, DeferredBlock<ParallelControllerBlock>> PARALLEL_CONTROLLERS = new LinkedHashMap<>();

  public static final DeferredBlock<MachineControllerBlock> CONTROLLER = BLOCKS
      .register("machine_controller", () -> new MachineControllerBlock(properties().strength(5F, 10F)));
  public static final DeferredBlock<FactoryControllerBlock> FACTORY_CONTROLLER = BLOCKS
      .register("factory_controller", () -> new FactoryControllerBlock(properties().strength(5F, 10F)));
  public static final DeferredItem<MachineWrenchItem> WRENCH = ITEMS
      .register("machine_wrench", () -> new MachineWrenchItem(new Item.Properties().stacksTo(1)));
  public static final DeferredItem<ConfigurationCardItem> CONFIGURATION_CARD = ITEMS
      .register("configuration_card", () -> new ConfigurationCardItem(new Item.Properties().stacksTo(1)));
  public static final DeferredItem<MachineConstructionTool> PROJECTOR = ITEMS
      .register("machine_projector", () -> new MachineConstructionTool(new Item.Properties().stacksTo(1)));
  /**
   * Ranged tool identity with local selection previews; export is not connected.
   */
  public static final DeferredItem<RangedConstructToolItem> RANGED_CONSTRUCT_TOOL = ITEMS
      .register("ranged_construct_tool", () -> new RangedConstructToolItem(new Item.Properties().stacksTo(1)));
  public static final DeferredBlock<SmartInterfaceBlock> SMART_INTERFACE = BLOCKS
      .register("smart_interface", () -> new SmartInterfaceBlock(properties().strength(3F, 10F)));

  public static final DeferredBlock<PatternProviderBlock> ME_PATTERN_PROVIDER = Mods.AE2.isLoaded() ? BLOCKS
      .register("me_pattern_provider", () -> new PatternProviderBlock(properties().strength(3F, 10F))) : null;
  public static final DeferredBlock<PatternMirrorBlock> ME_PATTERN_MIRROR = Mods.AE2.isLoaded() ? BLOCKS
      .register("me_pattern_mirror", () -> new PatternMirrorBlock(properties().strength(3F, 10F))) : null;

  static {
    if (ME_PATTERN_PROVIDER != null) {
      ITEMS.register(
          "me_pattern_provider",
          () -> new PatternProviderBlockItem(ME_PATTERN_PROVIDER.get(), new Item.Properties()));
      ITEMS.register(
          "me_pattern_mirror",
          () -> new PatternMirrorBlockItem(ME_PATTERN_MIRROR.get(), new Item.Properties()));
    }
    for (var machine : DedicatedControllerCatalog.machines()) {
      String path = "blueprint/" + machine.id().getNamespace() + "/" + machine.id().getPath();
      var blueprint = ITEMS.register(path, () -> new MachineBlueprintItem(new Item.Properties().stacksTo(16), machine));
      BLUEPRINTS.put(machine.id(), blueprint);
    }
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
    ITEMS.register("machine_controller", () -> new MachineControllerItem(CONTROLLER.get(), new Item.Properties()));
    ITEMS
        .register("factory_controller", () -> new ControllerBlockItem(FACTORY_CONTROLLER.get(), new Item.Properties()));
    ITEMS.registerSimpleBlockItem(SMART_INTERFACE);
    for (var tier : ParallelControllerTier.values()) {
      String id = "parallel_controller_" + tier.path();
      var block = BLOCKS.register(id, () -> new ParallelControllerBlock(properties().strength(5F, 10F), tier));
      PARALLEL_CONTROLLERS.put(id, block);
      ITEMS.registerSimpleBlockItem(block);
    }
    for (var tier : UpgradeBusTier.values()) {
      String id = "upgrade_bus_" + tier.name().toLowerCase(Locale.ROOT);
      var block = BLOCKS.register(id, () -> new UpgradeBusBlock(properties().strength(3F, 10F), tier));
      UPGRADE_BUSES.put(id, block);
      ITEMS.registerSimpleBlockItem(block);
    }
    for (String name : new String[]{"plain", "vent", "firebox", "gearbox", "reinforced", "circuitry"}) {
      String id = "machine_casing_" + name;
      DeferredBlock<MachineCasingBlock> block = BLOCKS
          .register(id, () -> new MachineCasingBlock(properties().strength(2F, 10F)));
      CASINGS.put(name, block);
      ITEMS.registerSimpleBlockItem(block);
    }
    for (PortKind kind : PortKind.values()) {
      if (kind.resource() == PortKind.Resource.CHEMICAL && !ChemicalPlatform.available()) {
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
    if (MachineNetworkPlatform.available()) {
      for (PortKind kind : new PortKind[]{PortKind.ITEM_INPUT, PortKind.ITEM_OUTPUT, PortKind.FLUID_INPUT,
          PortKind.FLUID_OUTPUT, PortKind.CHEMICAL_INPUT, PortKind.CHEMICAL_OUTPUT}) {
        if (kind.resource() == PortKind.Resource.CHEMICAL && !MachineNetworkPlatform.chemicalAvailable()) {
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

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternProviderBlockEntity>> PATTERN_PROVIDER_ENTITY = BLOCK_ENTITIES
      .register(
          "pattern_provider",
          () -> BlockEntityType.Builder.of(
              PatternProviderBlockEntity::new,
              ME_PATTERN_PROVIDER == null ? new Block[0] : new Block[]{ME_PATTERN_PROVIDER.get()}).build(null));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternMirrorBlockEntity>> PATTERN_MIRROR_ENTITY = BLOCK_ENTITIES
      .register(
          "pattern_mirror",
          () -> BlockEntityType.Builder.of(
              PatternMirrorBlockEntity::new,
              ME_PATTERN_MIRROR == null ? new Block[0] : new Block[]{ME_PATTERN_MIRROR.get()}).build(null));
  public static final DeferredHolder<MenuType<?>, MenuType<PatternProviderMenu>> PATTERN_PROVIDER_MENU = MENUS
      .register("pattern_provider", () -> IMenuTypeExtension.create(PatternProviderMenu::fromNetwork));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachinePortBlockEntity>> PORT_ENTITY = BLOCK_ENTITIES
      .register(
          "machine_port",
          () -> BlockEntityType.Builder
              .of(MachinePortBlockEntity::new, PORTS.values().stream().map(DeferredBlock::get).toArray(Block[]::new))
              .build(null));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineCasingBlockEntity>> CASING_ENTITY = BLOCK_ENTITIES
      .register(
          "machine_casing",
          () -> BlockEntityType.Builder.of(
              MachineCasingBlockEntity::new,
              CASINGS.values().stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkPortBlockEntity>> NETWORK_PORT_ENTITY = BLOCK_ENTITIES
      .register(
          "network_port",
          () -> BlockEntityType.Builder.of(
              NetworkPortBlockEntity::new,
              NETWORK_PORTS.values().stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineControllerBlockEntity>> CONTROLLER_ENTITY = BLOCK_ENTITIES
      .register(
          "machine_controller",
          () -> BlockEntityType.Builder
              .of(
                  MachineControllerBlockEntity::new,
                  Stream.concat(
                      Stream.of(CONTROLLER.get()),
                      DEDICATED_CONTROLLERS.values().stream().map(DeferredBlock::get)).toArray(Block[]::new))
              .build(null));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactoryControllerBlockEntity>> FACTORY_CONTROLLER_ENTITY = BLOCK_ENTITIES
      .register(
          "factory_controller",
          () -> BlockEntityType.Builder
              .of(
                  FactoryControllerBlockEntity::new,
                  Stream.concat(
                      Stream.of(FACTORY_CONTROLLER.get()),
                      DEDICATED_FACTORIES.values().stream().map(DeferredBlock::get)).toArray(Block[]::new))
              .build(null));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<UpgradeBusBlockEntity>> UPGRADE_BUS_ENTITY = BLOCK_ENTITIES
      .register(
          "upgrade_bus",
          () -> BlockEntityType.Builder.of(
              UpgradeBusBlockEntity::new,
              UPGRADE_BUSES.values().stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ParallelControllerBlockEntity>> PARALLEL_CONTROLLER_ENTITY = BLOCK_ENTITIES
      .register(
          "parallel_controller",
          () -> BlockEntityType.Builder.of(
              ParallelControllerBlockEntity::new,
              PARALLEL_CONTROLLERS.values().stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));

  public static final DeferredHolder<MenuType<?>, MenuType<MachinePortMenu>> PORT_MENU = MENUS
      .register("machine_port", () -> IMenuTypeExtension.create(MachinePortMenu::new));
  public static final DeferredHolder<MenuType<?>, MenuType<NetworkPortMenu>> NETWORK_PORT_MENU = MENUS
      .register("network_port", () -> IMenuTypeExtension.create(NetworkPortMenu::new));
  public static final DeferredHolder<MenuType<?>, MenuType<GroupInputMenu>> GROUP_INPUT_MENU = MENUS
      .register("input_group", () -> IMenuTypeExtension.create(GroupInputMenu::new));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SmartInterfaceBlockEntity>> SMART_INTERFACE_ENTITY = BLOCK_ENTITIES
      .register(
          "smart_interface",
          () -> BlockEntityType.Builder.of(SmartInterfaceBlockEntity::new, SMART_INTERFACE.get()).build(null));
  public static final DeferredHolder<MenuType<?>, MenuType<SmartInterfaceMenu>> SMART_INTERFACE_MENU = MENUS
      .register("smart_interface", () -> IMenuTypeExtension.create(SmartInterfaceMenu::new));

  public static final DeferredHolder<MenuType<?>, MenuType<MachineControllerMenu>> CONTROLLER_MENU = MENUS
      .register("machine_controller", () -> IMenuTypeExtension.create(MachineControllerMenu::new));

  public static final DeferredHolder<MenuType<?>, MenuType<MachineResourceMenu>> RESOURCE_MENU = MENUS
      .register("machine_resource", () -> IMenuTypeExtension.create(MachineResourceMenu::new));

  public static final DeferredHolder<MenuType<?>, MenuType<FactoryControllerMenu>> FACTORY_CONTROLLER_MENU = MENUS
      .register("factory_controller", () -> IMenuTypeExtension.create(FactoryControllerMenu::new));
  public static final DeferredHolder<MenuType<?>, MenuType<UpgradeBusMenu>> UPGRADE_BUS_MENU = MENUS
      .register("upgrade_bus", () -> IMenuTypeExtension.create(UpgradeBusMenu::new));
  public static final DeferredHolder<MenuType<?>, MenuType<ParallelControllerMenu>> PARALLEL_CONTROLLER_MENU = MENUS
      .register("parallel_controller", () -> IMenuTypeExtension.create(ParallelControllerMenu::new));

  public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register(
      "machines",
      () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.mmcece.machines"))
          .icon(() -> CONTROLLER.asItem().getDefaultInstance()).displayItems((parameters, output) -> {
            output.accept(CONTROLLER.get());
            output.accept(FACTORY_CONTROLLER.get());
            DEDICATED_CONTROLLERS.values().forEach(block -> output.accept(block.get()));
            DEDICATED_FACTORIES.values().forEach(block -> output.accept(block.get()));
            output.accept(SMART_INTERFACE.get());
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
            NETWORK_PORTS.values().forEach(block -> output.accept(block.get()));
          }).build());

  private MachineRegistries() {
  }

  private static BlockBehaviour.Properties properties() {
    return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).sound(SoundType.METAL).requiresCorrectToolForDrops();
  }

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    BLOCK_ENTITIES.register(bus);
    MENUS.register(bus);
    TABS.register(bus);
  }

}
