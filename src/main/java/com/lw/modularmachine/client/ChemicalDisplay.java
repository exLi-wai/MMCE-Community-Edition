package com.lw.modularmachine.client;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.integration.mekanism.client.MekanismChemicalDisplay;
import com.lw.modularmachine.util.Mods;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal client boundary; core screens never resolve optional chemical
 * classes.
 */
@ApiStatus.Internal
public final class ChemicalDisplay {
  public record Appearance(ResourceLocation texture, int tint, Component name) {
  }

  public static Appearance describe(ChemicalStackData stack) {
    if (stack.isEmpty()) {
      return new Appearance(
          MissingTextureAtlasSprite.getLocation(),
          0xFFFFFF,
          Component.translatable("message.mmcece.chemical_empty"));
    }
    if (Mods.MEKANISM.isLoaded()) {
      return MekanismChemicalDisplay.describe(stack.chemical());
    }

    return new Appearance(
        MissingTextureAtlasSprite.getLocation(),
        0xFFFFFF,
        Component.literal(stack.chemical().toString()));
  }

  private ChemicalDisplay() {
  }
}
