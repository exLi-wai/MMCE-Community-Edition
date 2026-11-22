package com.lw.modularmachine.machine.network;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal quantity-free container samples shared by server configuration and
 * recipe viewer drops.
 */
@ApiStatus.Internal
public final class NetworkResourceSamples {
  private static final Map<ResourceType, Function<ItemStack, Optional<NetworkPortResource>>> SAMPLERS = Map.of(
      ResourceType.ITEM,
      sample -> Optional.of(new NetworkPortResource.Item(sample)),
      ResourceType.FLUID,
      sample -> FluidUtil.getFluidContained(sample).filter(fluid -> !fluid.isEmpty())
          .map(NetworkPortResource.Fluid::new),
      ResourceType.CHEMICAL,
      sample -> {
        var chemical = ChemicalPlatform.sampleContainer(sample);

        return chemical.isEmpty() ? Optional.empty() : Optional
            .of(new NetworkPortResource.Chemical(chemical.chemical()));
      });

  private NetworkResourceSamples() {
  }

  public static Optional<NetworkPortResource> sample(ResourceType resource, ItemStack carried) {
    var sampler = SAMPLERS.get(resource);
    if (sampler == null || carried.isEmpty()) {
      return Optional.empty();
    }

    return sampler.apply(carried.copyWithCount(1));
  }
}
