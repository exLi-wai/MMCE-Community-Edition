package com.lw.modularmachine.machine.recipe.resource.keyed;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource.ReplenishmentProof;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * One bounded persisted local stock; keys never share balances, only capacity.
 */
@ApiStatus.Internal
public final class KeyedStock {
  private final long capacity;
  private final Map<ResourceLocation, Long> balances = new LinkedHashMap<>();
  private Set<ReplenishmentProof> inputProofs;
  private Set<ReplenishmentProof> outputProofs;
  private long total;

  public KeyedStock(long capacity) {
    this.capacity = capacity;
  }

  public List<ResourceLocation> keys() {
    return List.copyOf(balances.keySet());
  }

  public long amount(ResourceLocation key) {
    return balances.getOrDefault(key, 0L);
  }

  public long total() {
    return total;
  }

  public long space() {
    return capacity - total;
  }

  public void watchInput(ReplenishmentProof proof) {
    if (inputProofs == null) {
      inputProofs = Collections.newSetFromMap(new WeakHashMap<>());
    }
    inputProofs.add(proof);
  }

  public void watchOutput(ReplenishmentProof proof) {
    if (outputProofs == null) {
      outputProofs = Collections.newSetFromMap(new WeakHashMap<>());
    }
    outputProofs.add(proof);
  }

  public void invalidateProofs() {
    invalidate(inputProofs);
    invalidate(outputProofs);
    inputProofs = null;
    outputProofs = null;
  }

  private static void invalidate(Set<ReplenishmentProof> proofs) {
    if (proofs != null) {
      proofs.forEach(ReplenishmentProof::invalidate);
    }
  }

  public long move(ResourceLocation key, long delta, boolean simulate) {
    long count = amount(key);
    long actual = Math.clamp(delta, -count, space());
    if (!simulate && actual != 0) {
      if (count + actual == 0) {
        balances.remove(key);
      } else {
        balances.put(key, count + actual);
      }
      total += actual;
      if (actual > 0) {
        invalidate(outputProofs);
        outputProofs = null;
      } else {
        invalidate(inputProofs);
        inputProofs = null;
      }
    }
    return actual;
  }

  public RecipeResourceParticipant acquire(ResourceLocation key, long delta) {
    if (move(key, delta, true) != delta) {
      throw new IllegalStateException("Keyed hatch has insufficient stock or space");
    }
    move(key, delta, false);
    return commit -> {
      if (!commit && move(key, -delta, false) != -delta) {
        throw new IllegalStateException("Keyed hatch rollback could not restore exact stock");
      }
    };
  }

  public CompoundTag save() {
    var tag = new CompoundTag();
    balances.forEach((key, value) -> tag.putLong(key.toString(), value));
    return tag;
  }

  public void load(CompoundTag tag) {
    invalidateProofs();
    balances.clear();
    total = 0;
    for (var name : tag.getAllKeys()) {
      var key = ResourceLocation.tryParse(name);
      if (key != null && tag.getLong(name) > 0) {
        move(key, tag.getLong(name), false);
      }
    }
  }
}
