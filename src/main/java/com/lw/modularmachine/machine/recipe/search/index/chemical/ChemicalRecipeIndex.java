package com.lw.modularmachine.machine.recipe.search.index.chemical;

import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.machine.recipe.search.ChemicalResourceKey;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Necessary chemical identity and selector anchors for startup inputs.
 * Quantities and shared physical tanks still require exact checking; per-tick
 * fees and outputs must not be supplied as startup requirements.
 */
@ApiStatus.Internal
public final class ChemicalRecipeIndex {
  private final RequiredInputIndex<ChemicalResourceKey> inputs;

  public ChemicalRecipeIndex(List<String> recipeIds, Map<String, List<ChemicalRequirement>> inputs) {
    var requirements = new ArrayList<RequiredInputIndex.RecipeInputs<ChemicalResourceKey>>(recipeIds.size());
    for (var recipeId : recipeIds) {
      var keys = inputs.getOrDefault(recipeId, List.of()).stream()
          .map(input -> new ChemicalResourceKey(input.chemical(), input.selector())).toList();
      requirements.add(new RequiredInputIndex.RecipeInputs<>(recipeId, keys));
    }
    this.inputs = new RequiredInputIndex<>(requirements);
  }

  public RequiredInputIndex<ChemicalResourceKey> inputs() {
    return inputs;
  }
}
