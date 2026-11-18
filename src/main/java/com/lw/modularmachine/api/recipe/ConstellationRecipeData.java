package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Active constellations required independently of starlight stock. */
public record ConstellationRecipeData(List<ResourceLocation> active) {
  public static final ConstellationRecipeData NONE = new ConstellationRecipeData(List.of());
  public static final MapCodec<ConstellationRecipeData> CODEC = ResourceLocation.CODEC.listOf(0, 16)
      .optionalFieldOf("constellations", List.of()).xmap(ConstellationRecipeData::new, ConstellationRecipeData::active);

  public ConstellationRecipeData {
    active = active.stream().distinct().toList();
    if (active.size() > 16) {
      throw new IllegalArgumentException("Too many constellation conditions");
    }
  }
}
