package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ResourceSelector;
import java.util.Objects;
import com.lw.modularmachine.api.component.ChemicalStackData;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal immutable Chemical resource identity; no external registry or world
 * access.
 */
@ApiStatus.Internal
public record ChemicalResourceKey(ResourceLocation chemical, String selector) {
  public ChemicalResourceKey {
    Objects.requireNonNull(chemical);
    ResourceSelector.require(selector);
  }

  public ChemicalStackData amount(long amount) {
    return new ChemicalStackData(chemical, amount);
  }
}
