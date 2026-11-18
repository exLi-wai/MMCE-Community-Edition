package com.lw.modularmachine.integration.botania;

import com.lw.modularmachine.registry.MachineRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.ApiStatus;
import vazkii.botania.api.block.WandBindable;
import vazkii.botania.api.block.Wandable;
import vazkii.botania.api.mana.ManaReceiver;
import vazkii.botania.api.mana.spark.ManaSparkAttachable;
import static vazkii.botania.api.neoforge.BotaniaNeoForgeCapabilities.getBlockApiLookupById;

@ApiStatus.Internal
public final class BotaniaIntegration {
  private BotaniaIntegration() {
  }

  public static void initialize(IEventBus bus) {
    bus.addListener(BotaniaIntegration::registerCapabilities);
    if (FMLEnvironment.dist.isClient()) {
      bus.addListener(BotaniaHatchHud::register);
    }
  }

  private static void registerCapabilities(RegisterCapabilitiesEvent event) {
    var type = MachineRegistries.SPARK_MANA_ENTITY.get();
    event.registerBlockEntity(getBlockApiLookupById(ManaReceiver.LOOKUP), type, (hatch, side) -> hatch);
    event.registerBlockEntity(getBlockApiLookupById(ManaSparkAttachable.LOOKUP), type, (hatch, side) -> hatch);
    event.registerBlockEntity(getBlockApiLookupById(WandBindable.LOOKUP), type, (hatch, side) -> hatch);
    event.registerBlockEntity(
        getBlockApiLookupById(Wandable.LOOKUP),
        type,
        (hatch, side) -> (player, wand, clickedSide) -> hatch.showHud());
  }
}
