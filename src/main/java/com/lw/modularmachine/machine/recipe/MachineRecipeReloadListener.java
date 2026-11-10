package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.integration.kubejs.MachineItemRecipeKubeJSSources;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.resource.ContextAwareReloadListener;

/**
 * Dedicated strict recipe/mmcece directory; native tags/conditions are resolved
 * after the reload barrier.
 */
public final class MachineRecipeReloadListener extends ContextAwareReloadListener {
  public static final int MAX_FILES = 16_384;
  private final RecipeManager target;

  public MachineRecipeReloadListener(RecipeManager target) {
    this.target = target;
  }

  @Override
  public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier barrier, ResourceManager resources, ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler, Executor background, Executor game) {
    return CompletableFuture.supplyAsync(() -> read(resources), background).thenCompose(barrier::wait)
        .thenAcceptAsync(roots -> {
          try {
            var sources = new LinkedHashMap<>(roots);
            for (var entry : MachineItemRecipeKubeJSSources.snapshot().entrySet()) {
              if (sources.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new JsonParseException(entry.getKey() + ": Duplicate native/KubeJS machine recipe ID");
              }
            }
            var decoded = new LinkedHashMap<ResourceLocation, MachineRecipe>();
            for (var entry : sources.entrySet()) {
              var result = Recipe.CONDITIONAL_CODEC.parse(makeConditionalOps(), entry.getValue())
                  .getOrThrow(message -> new JsonParseException(entry.getKey() + ": " + message));
              result.ifPresent(value -> {
                if (!(value.carrier() instanceof MachineRecipe machine)) {
                  throw new JsonParseException(entry.getKey() + ": Expected machine recipe in reserved directory");
                }
                decoded.put(entry.getKey(), machine);
              });
            }
            var itemTags = new LinkedHashMap<ResourceLocation, List<Item>>();
            getContext().getAllTags(Registries.ITEM)
                .forEach((id, holders) -> itemTags.put(id, holders.stream().map(holder -> holder.value()).toList()));
            MachineRecipeService.stage(target, decoded, sources, getRegistryLookup(), new RecipeAdapterTags(itemTags));
          } finally {
            MachineItemRecipeKubeJSSources.clear();
          }
        }, game);
  }

  private static Map<ResourceLocation, JsonElement> read(ResourceManager resources) {
    var selected = new FileToIdConverter("recipe/mmcece", ".json").listMatchingResources(resources);
    if (selected.size() > MAX_FILES) {
      throw new JsonParseException("Too many native MMCE recipe files");
    }
    var nativeIds = new FileToIdConverter("recipe", ".json");
    var result = new LinkedHashMap<ResourceLocation, JsonElement>();
    selected.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
      var id = nativeIds.fileToId(entry.getKey());
      try (var reader = entry.getValue().openAsReader()) {
        JsonElement root = JsonParser.parseReader(reader);
        MachineRecipeJson.validate(root);
        if (!root.getAsJsonObject().has("type")) {
          throw new JsonParseException("$.type: Required native recipe type");
        }
        result.put(id, root);
      } catch (IOException | RuntimeException invalid) {
        throw new JsonParseException(id + ": Invalid native machine recipe", invalid);
      }
    });
    return Map.copyOf(result);
  }
}
