package com.lw.modularmachine.integration;

import com.lw.modularmachine.integration.ae2.Ae2Integration;
import com.lw.modularmachine.integration.astral.AstralIntegration;
import com.lw.modularmachine.integration.thaumaturge.ThaumaturgeIntegration;
import com.lw.modularmachine.integration.botania.BotaniaIntegration;
import com.lw.modularmachine.integration.mekanism.MekanismIntegration;
import com.lw.modularmachine.integration.neovitae.MeteorLaunchQueue;
import com.lw.modularmachine.integration.pneumaticcraft.PneumaticCraftIntegration;
import com.lw.modularmachine.integration.embers.EmbersIntegration;
import com.lw.modularmachine.util.Mods;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.ApiStatus;

/**
 * Optional platform adapters are installed before machine declarations are
 * initialized.
 */
@ApiStatus.Internal
public final class MachineIntegrations {
  private MachineIntegrations() {
  }

  public static void initialize(IEventBus modEventBus) {
    if (Mods.ASTRAL_SORCERY.isLoaded()) {
      AstralIntegration.initialize(modEventBus);
    }
    if (Mods.THAUMATURGE.isLoaded()) {
      ThaumaturgeIntegration.initialize(modEventBus);
    }
    if (Mods.PNEUMATIC_CRAFT.isLoaded()) {
      PneumaticCraftIntegration.initialize(modEventBus);
    }
    if (Mods.NEO_VITAE.isLoaded()) {
      NeoForge.EVENT_BUS.addListener(MeteorLaunchQueue::tick);
    }
    if (Mods.BOTANIA.isLoaded()) {
      BotaniaIntegration.initialize(modEventBus);
    }
    if (Mods.EMBERS.isLoaded()) {
      EmbersIntegration.initialize(modEventBus);
    }
    if (Mods.MEKANISM.isLoaded()) {
      MekanismIntegration.initialize(modEventBus);
    }
    if (Mods.AE2.isLoaded()) {
      Ae2Integration.initialize(modEventBus);
    }
  }
}
