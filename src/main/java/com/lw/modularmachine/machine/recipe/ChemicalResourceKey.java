package com.lw.modularmachine.machine.recipe;

import java.util.Objects;
import com.lw.modularmachine.api.component.ChemicalStackData;
import net.minecraft.resources.ResourceLocation;

/**
 * Internal immutable Chemical resource identity; no external registry or world
 * access.
 */
record ChemicalResourceKey(ResourceLocation chemical, String selector) {
  ChemicalResourceKey {
    Objects.requireNonNull(chemical);
    ResourceSelector.require(selector);
  }

  ChemicalStackData amount(long amount) {
    return new ChemicalStackData(chemical, amount);
  }
}
