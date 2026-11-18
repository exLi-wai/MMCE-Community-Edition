package com.lw.modularmachine.integration.botania;

import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import vazkii.botania.api.block.WandHUD;
import vazkii.botania.client.gui.HUDHandler;
import static vazkii.botania.api.neoforge.BotaniaNeoForgeCapabilities.getBlockApiLookupById;

final class BotaniaHatchHud {
  private BotaniaHatchHud() {
  }

  static void register(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        getBlockApiLookupById(WandHUD.BLOCK_LOOKUP),
        MachineRegistries.SPARK_MANA_ENTITY.get(),
        (hatch, side) -> (graphics, window, font, partialTicks) -> HUDHandler.drawComplexManaHUD(
            0x4444FF,
            graphics,
            window,
            font,
            (int) Math.min(hatch.getCurrentMana(), hatch.capacity()),
            (int) hatch.capacity(),
            hatch.getBlockState().getBlock().getName().getString(),
            ItemStack.EMPTY,
            false));
  }
}
