package com.lw.modularmachine.machine.recipe.search.index.fluid;

import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.Objects;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal necessary-type filter; exact components and quantities are checked
 * later.
 */
@ApiStatus.Internal
public final class FluidRecipeIndex {
  private final RequiredInputIndex<Key> inputs;

  public FluidRecipeIndex(List<String> recipeIds, Function<String, List<FluidRequirement>> inputs, Function<String, RecipeSelectors> selectors) {
    var requirements = new ArrayList<RequiredInputIndex.RecipeInputs<Key>>(recipeIds.size());
    for (var recipeId : recipeIds) {
      var keys = new LinkedHashSet<Key>();
      String defaultSelector = selectors.apply(recipeId).fluidInput();
      for (var input : inputs.apply(recipeId)) {
        keys.add(new Key(input.stack().getFluid(), input.selector(defaultSelector)));
      }
      requirements.add(new RequiredInputIndex.RecipeInputs<>(recipeId, List.copyOf(keys)));
    }
    this.inputs = new RequiredInputIndex<>(requirements);
  }

  public RequiredInputIndex<Key> inputs() {
    return inputs;
  }

  public record Key(Fluid fluid, String selector) {
    public Key {
      Objects.requireNonNull(fluid, "fluid");
      ResourceSelector.require(selector);
    }

    @Override
    public boolean equals(Object other) {
      return this == other || other instanceof Key key && fluid == key.fluid && selector.equals(key.selector);
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(fluid) + selector.hashCode();
    }
  }
}
