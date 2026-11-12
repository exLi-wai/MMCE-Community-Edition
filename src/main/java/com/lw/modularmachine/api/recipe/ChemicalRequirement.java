package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable chemical identity and exact amount, independent of any integration
 * API.
 */
public record ChemicalRequirement(ResourceLocation chemical, long amount, String selector) {
  public static final Codec<ChemicalRequirement> CODEC = RecordCodecBuilder.create(
      instance -> instance
          .group(
              ResourceLocation.CODEC.fieldOf("chemical").forGetter(ChemicalRequirement::chemical),
              Codec.LONG.fieldOf("amount").forGetter(ChemicalRequirement::amount),
              Codec.STRING.optionalFieldOf("selector", "").forGetter(ChemicalRequirement::selector))
          .apply(instance, ChemicalRequirement::new));

  public ChemicalRequirement(ResourceLocation chemical, long amount) {
    this(chemical, amount, "");
  }

  public ChemicalRequirement {
    Objects.requireNonNull(chemical, "chemical");
    if (amount <= 0) {
      throw new IllegalArgumentException("Chemical amount must be a positive long");
    }
    selector = ResourceSelector.require(selector);
  }

  public ChemicalRequirement withAmount(long value) {
    return new ChemicalRequirement(chemical, value, selector);
  }

  public ChemicalRequirement scaled(int instances) {
    if (instances < 1) {
      throw new IllegalArgumentException("Chemical instances must be positive");
    }

    return withAmount(Math.multiplyExact(amount, instances));
  }
}
