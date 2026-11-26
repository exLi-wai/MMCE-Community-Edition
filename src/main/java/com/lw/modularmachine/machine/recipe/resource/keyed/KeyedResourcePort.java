package com.lw.modularmachine.machine.recipe.resource.keyed;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource.ReplenishmentProof;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface KeyedResourcePort {
  /** Physical buffer or current network identity, null if inaccessible. */
  Object resourceAddress();

  long available(ResourceLocation key);

  default List<ResourceLocation> inputKeys(ResourceLocation requested) {
    return List.of(requested);
  }

  long space(ResourceLocation key);

  long totalSpace();

  boolean watchInput(ReplenishmentProof proof);

  default boolean watchInputAmount(ReplenishmentProof proof) {
    return false;
  }

  void watchOutput(ReplenishmentProof proof);

  default long totalAvailable() {
    return Long.MAX_VALUE;
  }

  /** Capacity available for new requests after existing promises. */
  default long requestSpace() {
    return 0;
  }

  /** Whether this port can receive a replenishment request for this key. */
  default boolean canRequest(ResourceLocation key) {
    return true;
  }

  default long requestSupply(ResourceLocation key, long maximum) {
    return 0;
  }

  /**
   * Pending amount regardless of free request space. A supplied proof observes
   * additions and expiry.
   */
  default long outstandingRequest(ResourceLocation key, ReplenishmentProof proof) {
    return 0;
  }

  /**
   * Returns the additional amount accepted by the expiring replenishment hint.
   */
  default long request(ResourceLocation key, long additional, ReplenishmentProof publisher) {
    return 0;
  }

  RecipeResourceParticipant acquire(ResourceLocation key, long delta);

  void publish();
}
