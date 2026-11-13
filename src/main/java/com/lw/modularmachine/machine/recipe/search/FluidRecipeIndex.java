package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal necessary-type filter; exact components and quantities are checked
 * later.
 */
@ApiStatus.Internal
final class FluidRecipeIndex {
  private final Map<Key, Set<String>> recipesByAnchor;
  private final Map<String, Key> anchorsByRecipe;
  private final Set<String> recipesWithoutAnchor;

  FluidRecipeIndex(List<RecipeCandidate<ItemResourceKey>> candidates, Map<String, List<FluidStack>> inputs, Map<String, RecipeSelectors> selectors) {
    var requirements = new LinkedHashMap<String, Set<Key>>();
    var frequency = new HashMap<Key, Integer>();
    for (var candidate : candidates) {
      var keys = new LinkedHashSet<Key>();
      String selector = selectors.getOrDefault(candidate.id(), RecipeSelectors.NONE).fluidInput();
      for (var input : inputs.getOrDefault(candidate.id(), List.of())) {
        if (!input.isEmpty()) {
          keys.add(new Key(input.getFluid(), selector));
        }
      }
      for (var key : keys) {
        frequency.merge(key, 1, Math::addExact);
      }
      requirements.put(candidate.id(), keys);
    }
    var anchored = new HashMap<Key, Set<String>>();
    var byRecipe = new HashMap<String, Key>();
    var unanchored = new HashSet<String>();
    for (var entry : requirements.entrySet()) {
      Key anchor = null;
      for (var key : entry.getValue()) {
        if (anchor == null || frequency.get(key) < frequency.get(anchor)) {
          anchor = key;
        }
      }
      if (anchor == null) {
        unanchored.add(entry.getKey());
      } else {
        byRecipe.put(entry.getKey(), anchor);
        anchored.computeIfAbsent(anchor, ignored -> new HashSet<>()).add(entry.getKey());
      }
    }
    var frozen = new HashMap<Key, Set<String>>();
    anchored.forEach((key, ids) -> frozen.put(key, Set.copyOf(ids)));
    recipesByAnchor = Map.copyOf(frozen);
    anchorsByRecipe = Map.copyOf(byRecipe);
    recipesWithoutAnchor = byRecipe.isEmpty() ? Set.of() : Set.copyOf(unanchored);
  }

  Set<String> candidatesFor(Set<Key> available, Set<String> allowedIds) {
    if (anchorsByRecipe.isEmpty()) {
      return allowedIds;
    }
    var selected = new HashSet<String>();
    if (allowedIds != null) {
      for (var id : allowedIds) {
        var anchor = anchorsByRecipe.get(id);
        if (anchor == null ? recipesWithoutAnchor.contains(id) : available.contains(anchor)) {
          selected.add(id);
        }
      }
    } else {
      selected.addAll(recipesWithoutAnchor);
      for (var key : available) {
        selected.addAll(recipesByAnchor.getOrDefault(key, Set.of()));
      }
    }

    return Set.copyOf(selected);
  }

  int candidateCount(Set<Key> available) {
    if (anchorsByRecipe.isEmpty()) {
      return Integer.MAX_VALUE;
    }
    int count = recipesWithoutAnchor.size();
    for (var key : available) {
      count += recipesByAnchor.getOrDefault(key, Set.of()).size();
    }

    return count;
  }

  boolean matches(String recipeId, Set<Key> available) {
    var anchor = anchorsByRecipe.get(recipeId);

    return anchor == null || available.contains(anchor);
  }

  record Key(Fluid fluid, String selector) {
    Key {
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
