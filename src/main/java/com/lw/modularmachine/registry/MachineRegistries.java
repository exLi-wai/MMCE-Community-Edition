package com.lw.modularmachine.registry;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.block.NetworkPortBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.block.PatternMirrorBlock;
import com.lw.modularmachine.block.PatternProviderBlock;
import com.lw.modularmachine.block.SmartInterfaceBlock;
import com.lw.modularmachine.block.MachineCasingBlock;
import com.lw.modularmachine.block.LaserInputHatchBlock;
import com.lw.modularmachine.block.EmberInputHatchBlock;
import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.integration.botania.SparkManaHatchBlock;
import com.lw.modularmachine.integration.botania.SparkManaHatchBlockEntity;
import com.lw.modularmachine.integration.mekanism.HeatHatchBlockEntity;
import com.lw.modularmachine.integration.naturesaura.AuraInputHatchBlockEntity;
import com.lw.modularmachine.integration.neovitae.LifeEssenceHatchBlockEntity;
import com.lw.modularmachine.integration.neovitae.MeteorHatchBlock;
import com.lw.modularmachine.integration.neovitae.MeteorHatchBlockEntity;
import com.lw.modularmachine.integration.thaumaturge.PipeEssentiaHatch;
import com.lw.modularmachine.integration.thaumaturge.EssentiaHatchBlock;
import com.lw.modularmachine.integration.thaumaturge.MeEssentiaHatch;
import com.lw.modularmachine.integration.astral.StarlightHatch;
import com.lw.modularmachine.integration.pneumaticcraft.CompressedAirHatchBlockEntity;
import com.lw.modularmachine.integration.pneumaticcraft.CompressedAirHatchBlock;
import com.lw.modularmachine.blockentity.LaserInputHatchBlockEntity;
import com.lw.modularmachine.blockentity.EmberInputHatchBlockEntity;
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
import com.lw.modularmachine.menu.LegacyHatchMenu;
import net.minecraft.world.flag.FeatureFlags;
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
import org.jetbrains.annotations.ApiStatus;

/**
 * Ordered registration identities; declaration implementations live in focused
 * registry helpers.
 */
@ApiStatus.Internal
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
  public static final Map<String, DeferredBlock<EmberInputHatchBlock>> EMBER_INPUT_HATCHES = new LinkedHashMap<>();
  public static final Map<String, DeferredBlock<TickingRecipeHatchBlock>> TICKING_RECIPE_HATCHES = new LinkedHashMap<>();
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
  public static final DeferredBlock<MachineCasingBlock> ENVIRONMENT_HATCH = BLOCKS.register(
      "environment_hatch",
      () -> new MachineCasingBlock(MachineContentRegistration.properties().strength(3F, 10F)));
  public static final DeferredBlock<LaserInputHatchBlock> LASER_INPUT_HATCH = Mods.MEKANISM.isLoaded() ? BLOCKS
      .register(
          "laser_input_hatch",
          () -> new LaserInputHatchBlock(MachineContentRegistration.properties().strength(3F, 10F))) : null;
  public static final DeferredBlock<TickingRecipeHatchBlock> SPARK_MANA_INPUT = Mods.BOTANIA
      .isLoaded() ? MachineContentRegistration.registerTickingHatch(
          "spark_mana_input_hatch",
          () -> new SparkManaHatchBlock(MachineContentRegistration.hatchProperties())) : null;
  public static final DeferredBlock<TickingRecipeHatchBlock> SPARK_MANA_OUTPUT = Mods.BOTANIA
      .isLoaded() ? MachineContentRegistration.registerTickingHatch(
          "spark_mana_output_hatch",
          () -> new SparkManaHatchBlock(MachineContentRegistration.hatchProperties())) : null;
  public static final DeferredBlock<TickingRecipeHatchBlock> AURA_INPUT = Mods.NATURES_AURA
      .isLoaded() ? MachineContentRegistration
          .registerTickingHatch("aura_input_hatch", AuraInputHatchBlockEntity::new) : null;

  public static final DeferredBlock<TickingRecipeHatchBlock> HEAT_INPUT = Mods.MEKANISM
      .isLoaded() ? MachineContentRegistration
          .registerTickingHatch("heat_input_hatch", HeatHatchBlockEntity::new) : null;
  public static final DeferredBlock<TickingRecipeHatchBlock> HEAT_OUTPUT = Mods.MEKANISM
      .isLoaded() ? MachineContentRegistration
          .registerTickingHatch("heat_output_hatch", HeatHatchBlockEntity::new) : null;

  public static final DeferredBlock<PatternProviderBlock> ME_PATTERN_PROVIDER = Mods.AE2.isLoaded() ? BLOCKS.register(
      "me_pattern_provider",
      () -> new PatternProviderBlock(MachineContentRegistration.properties().strength(3F, 10F))) : null;
  public static final DeferredBlock<TickingRecipeHatchBlock> COMPRESSED_AIR_INPUT = Mods.PNEUMATIC_CRAFT
      .isLoaded() ? MachineContentRegistration.registerTickingHatch(
          "compressed_air_input_hatch",
          () -> new CompressedAirHatchBlock(MachineContentRegistration.hatchProperties())) : null;
  public static final DeferredBlock<TickingRecipeHatchBlock> COMPRESSED_AIR_OUTPUT = Mods.PNEUMATIC_CRAFT
      .isLoaded() ? MachineContentRegistration.registerTickingHatch(
          "compressed_air_output_hatch",
          () -> new CompressedAirHatchBlock(MachineContentRegistration.hatchProperties())) : null;
  public static final DeferredBlock<PatternMirrorBlock> ME_PATTERN_MIRROR = Mods.AE2.isLoaded() ? BLOCKS.register(
      "me_pattern_mirror",
      () -> new PatternMirrorBlock(MachineContentRegistration.properties().strength(3F, 10F))) : null;

  static {
    MachineContentRegistration.registerVariants();
  }

  public static final DeferredBlock<MachineCasingBlock> AURA_OUTPUT = MachineContentRegistration
      .registerWorldHatch(Mods.NATURES_AURA, "aura_output_hatch");
  public static final DeferredBlock<MachineCasingBlock> WILL_INPUT = MachineContentRegistration
      .registerWorldHatch(Mods.NEO_VITAE, "will_input_hatch");
  public static final DeferredBlock<MachineCasingBlock> WILL_OUTPUT = MachineContentRegistration
      .registerWorldHatch(Mods.NEO_VITAE, "will_output_hatch");
  public static final DeferredBlock<MachineCasingBlock> MULTI_WILL_INPUT = MachineContentRegistration
      .registerWorldHatch(Mods.NEO_VITAE, "multi_will_input_hatch");
  public static final DeferredBlock<MachineCasingBlock> MULTI_WILL_OUTPUT = MachineContentRegistration
      .registerWorldHatch(Mods.NEO_VITAE, "multi_will_output_hatch");
  public static final DeferredBlock<MachineCasingBlock> VIS_INPUT = MachineContentRegistration
      .registerWorldHatch(Mods.THAUMATURGE, "vis_input_hatch");
  public static final DeferredBlock<MachineCasingBlock> VIS_OUTPUT = MachineContentRegistration
      .registerWorldHatch(Mods.THAUMATURGE, "vis_output_hatch");
  public static final DeferredBlock<MachineCasingBlock> FLUX_INPUT = MachineContentRegistration
      .registerWorldHatch(Mods.THAUMATURGE, "flux_input_hatch");
  public static final DeferredBlock<MachineCasingBlock> FLUX_OUTPUT = MachineContentRegistration
      .registerWorldHatch(Mods.THAUMATURGE, "flux_output_hatch");
  public static final DeferredBlock<MachineCasingBlock> CONSTELLATION = MachineContentRegistration
      .registerWorldHatch(Mods.ASTRAL_SORCERY, "constellation_hatch");
  public static final DeferredBlock<TickingRecipeHatchBlock> STARLIGHT_INPUT = MachineContentRegistration
      .registerStarlightHatch("starlight_input_hatch", true);
  public static final DeferredBlock<TickingRecipeHatchBlock> STARLIGHT_INPUT_TRAIT = MachineContentRegistration
      .registerStarlightHatch("starlight_input_hatch_trait", true);
  public static final DeferredBlock<TickingRecipeHatchBlock> STARLIGHT_INPUT_BRILLIANT = MachineContentRegistration
      .registerStarlightHatch("starlight_input_hatch_brilliant", true);
  public static final DeferredBlock<TickingRecipeHatchBlock> STARLIGHT_OUTPUT = MachineContentRegistration
      .registerStarlightHatch("starlight_output_hatch", false);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StarlightHatch.Input>> STARLIGHT_INPUT_ENTITY = STARLIGHT_INPUT == null ? null : BLOCK_ENTITIES
      .register(
          "starlight_input_hatch",
          () -> BlockEntityType.Builder.of(
              StarlightHatch.Input::new,
              STARLIGHT_INPUT.get(),
              STARLIGHT_INPUT_TRAIT.get(),
              STARLIGHT_INPUT_BRILLIANT.get()).build(null));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StarlightHatch.Output>> STARLIGHT_OUTPUT_ENTITY = STARLIGHT_OUTPUT == null ? null : BLOCK_ENTITIES
      .register(
          "starlight_output_hatch",
          () -> BlockEntityType.Builder.of(StarlightHatch.Output::new, STARLIGHT_OUTPUT.get()).build(null));
  public static final DeferredBlock<TickingRecipeHatchBlock> ESSENTIA_INPUT = Mods.THAUMATURGE
      .isLoaded() ? MachineContentRegistration.registerTickingHatch(
          "essentia_input_hatch",
          () -> new EssentiaHatchBlock(MachineContentRegistration.hatchProperties(), PipeEssentiaHatch::new)) : null;
  public static final DeferredBlock<TickingRecipeHatchBlock> ESSENTIA_OUTPUT = Mods.THAUMATURGE
      .isLoaded() ? MachineContentRegistration.registerTickingHatch(
          "essentia_output_hatch",
          () -> new EssentiaHatchBlock(MachineContentRegistration.hatchProperties(), PipeEssentiaHatch::new)) : null;
  private static final boolean ME_ESSENTIA_LOADED = Mods.AE2.isLoaded()
      && Mods.THAUMATURGE.isLoaded()
      && Mods.APPLIED_THAUMATURGE.isLoaded();
  public static final DeferredBlock<TickingRecipeHatchBlock> ME_ESSENTIA_INPUT = ME_ESSENTIA_LOADED ? MachineContentRegistration
      .registerTickingHatch(
          "me_essentia_input_bus",
          () -> new EssentiaHatchBlock(MachineContentRegistration.hatchProperties(), MeEssentiaHatch::new)) : null;
  public static final DeferredBlock<TickingRecipeHatchBlock> ME_ESSENTIA_STOCK = ME_ESSENTIA_LOADED ? MachineContentRegistration
      .registerTickingHatch(
          "me_essentia_stock_bus",
          () -> new EssentiaHatchBlock(MachineContentRegistration.hatchProperties(), MeEssentiaHatch::new)) : null;
  public static final DeferredBlock<TickingRecipeHatchBlock> ME_ESSENTIA_OUTPUT = ME_ESSENTIA_LOADED ? MachineContentRegistration
      .registerTickingHatch(
          "me_essentia_output_bus",
          () -> new EssentiaHatchBlock(MachineContentRegistration.hatchProperties(), MeEssentiaHatch::new)) : null;
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeEssentiaHatch>> PIPE_ESSENTIA_ENTITY = ESSENTIA_INPUT == null ? null : BLOCK_ENTITIES
      .register(
          "essentia_hatch",
          () -> BlockEntityType.Builder.of(PipeEssentiaHatch::new, ESSENTIA_INPUT.get(), ESSENTIA_OUTPUT.get())
              .build(null));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MeEssentiaHatch>> ME_ESSENTIA_ENTITY = !ME_ESSENTIA_LOADED ? null : BLOCK_ENTITIES
      .register(
          "me_essentia_bus",
          () -> BlockEntityType.Builder
              .of(MeEssentiaHatch::new, ME_ESSENTIA_INPUT.get(), ME_ESSENTIA_STOCK.get(), ME_ESSENTIA_OUTPUT.get())
              .build(null));
  public static final DeferredBlock<TickingRecipeHatchBlock> LIFE_ESSENCE_INPUT = MachineContentRegistration
      .registerLifeEssenceHatch("life_essence_input_hatch");
  public static final DeferredBlock<TickingRecipeHatchBlock> LIFE_ESSENCE_OUTPUT = MachineContentRegistration
      .registerLifeEssenceHatch("life_essence_output_hatch");
  public static final DeferredBlock<TickingRecipeHatchBlock> METEOR_OUTPUT = Mods.NEO_VITAE
      .isLoaded() ? MachineContentRegistration.registerTickingHatch(
          "meteor_output_hatch",
          () -> new MeteorHatchBlock(MachineContentRegistration.hatchProperties())) : null;
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MeteorHatchBlockEntity>> METEOR_ENTITY = METEOR_OUTPUT == null ? null : BLOCK_ENTITIES
      .register(
          "meteor_output_hatch",
          () -> BlockEntityType.Builder.of(MeteorHatchBlockEntity::new, METEOR_OUTPUT.get()).build(null));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LifeEssenceHatchBlockEntity>> LIFE_ESSENCE_ENTITY = LIFE_ESSENCE_INPUT == null ? null : BLOCK_ENTITIES
      .register(
          "life_essence_hatch",
          () -> BlockEntityType.Builder
              .of(LifeEssenceHatchBlockEntity::new, LIFE_ESSENCE_INPUT.get(), LIFE_ESSENCE_OUTPUT.get()).build(null));

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
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LaserInputHatchBlockEntity>> LASER_INPUT_ENTITY = LASER_INPUT_HATCH == null ? null : BLOCK_ENTITIES
      .register(
          "laser_input_hatch",
          () -> BlockEntityType.Builder.of(LaserInputHatchBlockEntity::new, LASER_INPUT_HATCH.get()).build(null));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EmberInputHatchBlockEntity>> EMBER_INPUT_ENTITY = EMBER_INPUT_HATCHES
      .isEmpty() ? null : BLOCK_ENTITIES.register("ember_input_hatch", MachineBlockEntityFactories::emberInputHatch);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SparkManaHatchBlockEntity>> SPARK_MANA_ENTITY = SPARK_MANA_INPUT == null ? null : BLOCK_ENTITIES
      .register(
          "spark_mana_hatch",
          () -> BlockEntityType.Builder
              .of(SparkManaHatchBlockEntity::new, SPARK_MANA_INPUT.get(), SPARK_MANA_OUTPUT.get()).build(null));
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AuraInputHatchBlockEntity>> AURA_INPUT_ENTITY = AURA_INPUT == null ? null : BLOCK_ENTITIES
      .register(
          "aura_input_hatch",
          () -> BlockEntityType.Builder.of(AuraInputHatchBlockEntity::new, AURA_INPUT.get()).build(null));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HeatHatchBlockEntity>> HEAT_ENTITY = HEAT_INPUT == null ? null : BLOCK_ENTITIES
      .register(
          "heat_hatch",
          () -> BlockEntityType.Builder.of(HeatHatchBlockEntity::new, HEAT_INPUT.get(), HEAT_OUTPUT.get()).build(null));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkPortBlockEntity>> NETWORK_PORT_ENTITY = BLOCK_ENTITIES
      .register("network_port", MachineBlockEntityFactories::networkPort);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CompressedAirHatchBlockEntity>> COMPRESSED_AIR_ENTITY = COMPRESSED_AIR_INPUT == null ? null : BLOCK_ENTITIES
      .register(
          "compressed_air_hatch",
          () -> BlockEntityType.Builder
              .of(CompressedAirHatchBlockEntity::new, COMPRESSED_AIR_INPUT.get(), COMPRESSED_AIR_OUTPUT.get())
              .build(null));

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
  public static final DeferredHolder<MenuType<?>, MenuType<LegacyHatchMenu>> LIFE_ESSENCE_MENU = MENUS.register(
      "life_essence_hatch",
      () -> new MenuType<>(LegacyHatchMenu.Kind.LIFE_ESSENCE::clientMenu, FeatureFlags.DEFAULT_FLAGS));
  public static final DeferredHolder<MenuType<?>, MenuType<LegacyHatchMenu>> COMPRESSED_AIR_MENU = MENUS.register(
      "compressed_air_hatch",
      () -> new MenuType<>(LegacyHatchMenu.Kind.COMPRESSED_AIR::clientMenu, FeatureFlags.DEFAULT_FLAGS));
  public static final DeferredHolder<MenuType<?>, MenuType<LegacyHatchMenu>> STARLIGHT_MENU = MENUS.register(
      "starlight_hatch",
      () -> new MenuType<>(LegacyHatchMenu.Kind.STARLIGHT::clientMenu, FeatureFlags.DEFAULT_FLAGS));

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
