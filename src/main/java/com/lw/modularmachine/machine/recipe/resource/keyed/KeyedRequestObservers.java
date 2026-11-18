package com.lw.modularmachine.machine.recipe.resource.keyed;

import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource.ReplenishmentProof;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class KeyedRequestObservers {
  private final Map<ReplenishmentProof, Set<ResourceLocation>> observed = new WeakHashMap<>();

  public void watch(ResourceLocation key, ReplenishmentProof proof) {
    if (proof != null && proof.current()) {
      observed.computeIfAbsent(proof, unused -> new HashSet<>()).add(key);
    }
  }

  public void changed(ResourceLocation key, ReplenishmentProof publisher) {
    observed.entrySet().removeIf(entry -> {
      var proof = entry.getKey();
      if (proof != publisher && entry.getValue().contains(key)) {
        proof.invalidate();
      }
      return !proof.current();
    });
  }

  public void invalidate() {
    observed.keySet().forEach(ReplenishmentProof::invalidate);
    observed.clear();
  }
}
