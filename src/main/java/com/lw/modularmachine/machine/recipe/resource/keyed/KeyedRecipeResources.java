package com.lw.modularmachine.machine.recipe.resource.keyed;

import com.lw.modularmachine.api.recipe.KeyedAmountRecipeData;
import com.lw.modularmachine.integration.astral.StarlightHatch;
import com.lw.modularmachine.integration.thaumaturge.EssentiaHatch;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class KeyedRecipeResources {
  public static final KeyedRecipeExecution ESSENTIA = new KeyedRecipeExecution(
      RecipeResourceDefinitions.ESSENTIA,
      () -> blocks(
          MachineRegistries.ESSENTIA_INPUT,
          MachineRegistries.ME_ESSENTIA_INPUT,
          MachineRegistries.ME_ESSENTIA_STOCK),
      () -> blocks(MachineRegistries.ESSENTIA_OUTPUT, MachineRegistries.ME_ESSENTIA_OUTPUT),
      null);
  public static final KeyedRecipeExecution STARLIGHT = new KeyedRecipeExecution(
      RecipeResourceDefinitions.STARLIGHT,
      () -> blocks(
          MachineRegistries.STARLIGHT_INPUT,
          MachineRegistries.STARLIGHT_INPUT_TRAIT,
          MachineRegistries.STARLIGHT_INPUT_BRILLIANT),
      () -> blocks(MachineRegistries.STARLIGHT_OUTPUT),
      KeyedAmountRecipeData.ANY_STARLIGHT);

  private KeyedRecipeResources() {
  }

  public static void register() {
    ESSENTIA.register((data, registries) -> {
      if (!Mods.THAUMATURGE.isLoaded()) {
        throw new IllegalArgumentException("Essentia requires Thaumaturge");
      }
      java.util.stream.Stream.concat(data.input().keySet().stream(), data.output().keySet().stream()).forEach(key -> {
        if (EssentiaHatch.resolveAspect(registries, key) == null) {
          throw new IllegalArgumentException("Unknown essentia aspect: " + key);
        }
      });
    });
    STARLIGHT.register((data, registries) -> {
      if (!Mods.ASTRAL_SORCERY.isLoaded()) {
        throw new IllegalArgumentException("Starlight requires Astral Sorcery");
      }
      data.input().keySet().forEach(key -> {
        if (!KeyedAmountRecipeData.ANY_STARLIGHT.equals(key)
            && !StarlightHatch.SKY.equals(key)
            && !StarlightHatch.UNATTUNED.equals(key)
            && StarlightHatch.constellation(key) == null) {
          throw new IllegalArgumentException("Unknown starlight source: " + key);
        }
      });
      data.output().keySet().forEach(key -> {
        if (!StarlightHatch.UNATTUNED.equals(key) && StarlightHatch.constellation(key) == null) {
          throw new IllegalArgumentException("Unknown starlight output source: " + key);
        }
      });
    });
  }

  @SafeVarargs
  private static Set<Block> blocks(Supplier<? extends Block>... holders) {
    var blocks = new LinkedHashSet<Block>();
    for (var holder : holders) {
      if (holder != null) {
        blocks.add(holder.get());
      }
    }
    return blocks;
  }
}
