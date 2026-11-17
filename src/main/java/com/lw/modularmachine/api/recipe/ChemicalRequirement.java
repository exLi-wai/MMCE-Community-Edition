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
public record ChemicalRequirement(ResourceLocation chemical, long amount, String selector, double chance) {
  public static final Codec<ChemicalRequirement> CODEC = RecordCodecBuilder.create(
      instance -> instance
          .group(
              ResourceLocation.CODEC.fieldOf("chemical").forGetter(ChemicalRequirement::chemical),
              Codec.LONG.fieldOf("amount").forGetter(ChemicalRequirement::amount),
              Codec.STRING.optionalFieldOf("selector", "").forGetter(ChemicalRequirement::selector),
              Codec.doubleRange(0, 1).optionalFieldOf("chance", 1.0).forGetter(ChemicalRequirement::chance))
          .apply(instance, ChemicalRequirement::new));

  public ChemicalRequirement(ResourceLocation chemical, long amount) {
    this(chemical, amount, "", 1);
  }

  public ChemicalRequirement(ResourceLocation chemical, long amount, String selector) {
    this(chemical, amount, selector, 1);
  }

  public ChemicalRequirement {
    Objects.requireNonNull(chemical, "chemical");
    if (amount <= 0 || !Double.isFinite(chance) || chance < 0 || chance > 1) {
      throw new IllegalArgumentException("Chemical amount must be positive and chance within 0..1");
    }
    selector = ResourceSelector.require(selector);
  }

  public ChemicalRequirement withAmount(long value) {
    return new ChemicalRequirement(chemical, value, selector, chance);
  }

  public ChemicalRequirement scaled(int instances) {
    if (instances < 1) {
      throw new IllegalArgumentException("Chemical instances must be positive");
    }

    return withAmount(Math.multiplyExact(amount, instances));
  }
}
