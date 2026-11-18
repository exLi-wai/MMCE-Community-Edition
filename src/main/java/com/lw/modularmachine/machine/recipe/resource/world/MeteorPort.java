package com.lw.modularmachine.machine.recipe.resource.world;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface MeteorPort {
  boolean available(ResourceLocation catalyst);

  RecipeResourceParticipant acquire(ResourceLocation catalyst);

  void publishLaunch();
}
