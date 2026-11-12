package com.lw.modularmachine.integration.mekanism.client;

import com.lw.modularmachine.client.ChemicalDisplay;
import mekanism.api.MekanismAPI;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal optional Mekanism client adapter, loaded only after the mod gate.
 */
@ApiStatus.Internal
public final class MekanismChemicalDisplay {
  public static ChemicalDisplay.Appearance describe(ResourceLocation id) {
    if (!MekanismAPI.CHEMICAL_REGISTRY.containsKey(id)) {
      return new ChemicalDisplay.Appearance(
          MissingTextureAtlasSprite.getLocation(),
          0xFFFFFF,
          Component.literal(id.toString()));
    }
    var chemical = MekanismAPI.CHEMICAL_REGISTRY.get(id);

    return new ChemicalDisplay.Appearance(chemical.getIcon(), chemical.getTint(), chemical.getTextComponent());
  }

  private MekanismChemicalDisplay() {
  }
}
