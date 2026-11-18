package com.lw.modularmachine.api.recipe;

import com.mojang.serialization.MapCodec;
import java.util.List;

/** All rows must pass; different rows may match different hatches. */
public record EnvironmentRecipeData(List<EnvironmentRequirement> requirements) {
  public static final EnvironmentRecipeData NONE = new EnvironmentRecipeData(List.of());
  public static final MapCodec<EnvironmentRecipeData> CODEC = EnvironmentRequirement.CODEC.sizeLimitedListOf(16)
      .optionalFieldOf("environment", List.of()).xmap(EnvironmentRecipeData::new, EnvironmentRecipeData::requirements);

  public EnvironmentRecipeData {
    requirements = List.copyOf(requirements);
    if (requirements.size() > 16) {
      throw new IllegalArgumentException("Too many environment predicates");
    }
  }
}
