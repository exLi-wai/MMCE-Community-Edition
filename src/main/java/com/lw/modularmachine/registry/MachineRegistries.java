package com.lw.modularmachine.registry;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.block.NetworkPortBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.block.PatternMirrorBlock;
import com.lw.modularmachine.block.PatternProviderBlock;
import com.lw.modularmachine.block.SmartInterfaceBlock;
import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineCasingBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.blockentity.PatternMirrorBlockEntity;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.item.ConfigurationCardItem;
import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.item.MachineConstructionTool;
import com.lw.modularmachine.item.MachineWrenchItem;
import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.menu.FactoryControllerMenu;
import com.lw.modularmachine.menu.GroupInputMenu;
import com.lw.modularmachine.menu.MachineControllerMenu;
import com.lw.modularmachine.menu.MachinePortMenu;
import com.lw.modularmachine.menu.MachineResourceMenu;
import com.lw.modularmachine.menu.NetworkPortMenu;
import com.lw.modularmachine.menu.ParallelControllerMenu;
import com.lw.modularmachine.menu.PatternProviderMenu;
import com.lw.modularmachine.menu.SmartInterfaceMenu;
import com.lw.modularmachine.menu.UpgradeBusMenu;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.util.Mods;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Ordered registration identities; declaration implementations live in focused
 * registry helpers.
 */
@org.jetbrains.annotations.ApiStatus.Internal
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

  public static final DeferredBlock<MachineControllerBlock> CONTROLLER = BLOCKS.register(
      "machine_controller",
      () -> new MachineControllerBlock(MachineContentRegistration.properties().strength(5F, 10F)));
  public static final DeferredBlock<FactoryControllerBlock> FACTORY_CONTROLLER = BLOCKS.register(
      "factory_controller",
      () -> new FactoryControllerBlock(MachineContentRegistration.properties().strength(5F, 10F)));
  public static final DeferredItem<MachineWrenchItem> WRENCH = ITEMS
      .register("machine_wrench", () -> new MachineWrenchItem(new Item.Properties().stacksTo(1)));
  public static final DeferredItem<ConfigurationCardItem> CONFIGURATION_CARD = ITEMS
      .register("configuration_card", () -> new ConfigurationCardItem(new Item.Properties().stacksTo(1)));
  public static final DeferredItem<MachineConstructionTool> PROJECTOR = ITEMS
      .register("machine_projector", () -> new MachineConstructionTool(new Item.Properties().stacksTo(1)));
  /**
   * Ranged tool identity with local selection previews and config export.
   */
  public static final DeferredItem<RangedConstructToolItem> RANGED_CONSTRUCT_TOOL = ITEMS
      .register("ranged_construct_tool", () -> new RangedConstructToolItem(new Item.Properties().stacksTo(1)));
  public static final DeferredBlock<SmartInterfaceBlock> SMART_INTERFACE = BLOCKS.register(
      "smart_interface",
      () -> new SmartInterfaceBlock(MachineContentRegistration.properties().strength(3F, 10F)));

  public static final DeferredBlock<PatternProviderBlock> ME_PATTERN_PROVIDER = Mods.AE2.isLoaded() ? BLOCKS.register(
      "me_pattern_provider",
      () -> new PatternProviderBlock(MachineContentRegistration.properties().strength(3F, 10F))) : null;
  public static final DeferredBlock<PatternMirrorBlock> ME_PATTERN_MIRROR = Mods.AE2.isLoaded() ? BLOCKS.register(
      "me_pattern_mirror",
      () -> new PatternMirrorBlock(MachineContentRegistration.properties().strength(3F, 10F))) : null;

  static {
    MachineContentRegistration.registerVariants();
  }

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternProviderBlockEntity>> PATTERN_PROVIDER_ENTITY = BLOCK_ENTITIES
      .register("pattern_provider", MachineBlockEntityFactories::patternProvider);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternMirrorBlockEntity>> PATTERN_MIRROR_ENTITY = BLOCK_ENTITIES
      .register("pattern_mirror", MachineBlockEntityFactories::patternMirror);
  public static final DeferredHolder<MenuType<?>, MenuType<PatternProviderMenu>> PATTERN_PROVIDER_MENU = MENUS
      .register("pattern_provider", () -> IMenuTypeExtension.create(PatternProviderMenu::fromNetwork));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachinePortBlockEntity>> PORT_ENTITY = BLOCK_ENTITIES
      .register("machine_port", MachineBlockEntityFactories::machinePort);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineCasingBlockEntity>> CASING_ENTITY = BLOCK_ENTITIES
      .register("machine_casing", MachineBlockEntityFactories::machineCasing);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkPortBlockEntity>> NETWORK_PORT_ENTITY = BLOCK_ENTITIES
      .register("network_port", MachineBlockEntityFactories::networkPort);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineControllerBlockEntity>> CONTROLLER_ENTITY = BLOCK_ENTITIES
      .register("machine_controller", MachineBlockEntityFactories::machineController);

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactoryControllerBlockEntity>> FACTORY_CONTROLLER_ENTITY = BLOCK_ENTITIES
      .register("factory_controller", MachineBlockEntityFactories::factoryController);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<UpgradeBusBlockEntity>> UPGRADE_BUS_ENTITY = BLOCK_ENTITIES
      .register("upgrade_bus", MachineBlockEntityFactories::upgradeBus);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ParallelControllerBlockEntity>> PARALLEL_CONTROLLER_ENTITY = BLOCK_ENTITIES
      .register("parallel_controller", MachineBlockEntityFactories::parallelController);

  public static final DeferredHolder<MenuType<?>, MenuType<MachinePortMenu>> PORT_MENU = MENUS
      .register("machine_port", () -> IMenuTypeExtension.create(MachinePortMenu::new));
  public static final DeferredHolder<MenuType<?>, MenuType<NetworkPortMenu>> NETWORK_PORT_MENU = MENUS
      .register("network_port", () -> IMenuTypeExtension.create(NetworkPortMenu::new));
  public static final DeferredHolder<MenuType<?>, MenuType<GroupInputMenu>> GROUP_INPUT_MENU = MENUS
      .register("input_group", () -> IMenuTypeExtension.create(GroupInputMenu::new));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SmartInterfaceBlockEntity>> SMART_INTERFACE_ENTITY = BLOCK_ENTITIES
      .register("smart_interface", MachineBlockEntityFactories::smartInterface);
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

  public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS
      .register("machines", MachineCreativeTab::create);

  private MachineRegistries() {
  }

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    BLOCK_ENTITIES.register(bus);
    MENUS.register(bus);
    TABS.register(bus);
  }

}
