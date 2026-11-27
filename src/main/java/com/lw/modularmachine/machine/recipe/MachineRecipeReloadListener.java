package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.resource.ContextAwareReloadListener;
import net.neoforged.neoforge.common.conditions.ICondition;
import org.slf4j.Logger;

/**
 * Dedicated strict recipe/mmcece directory; native tags/conditions are resolved
 * after the reload barrier.
 */
public final class MachineRecipeReloadListener extends ContextAwareReloadListener {
  private static final Logger LOGGER = LogUtils.getLogger();
  public static final int MAX_FILES = 16_384;
  private static final int MAX_CACHED_FILE_BYTES = 128 * 1024;
  private static final int MAX_CACHED_TOTAL_BYTES = 32 * 1024 * 1024;
  private final RecipeManager target;
  private final SourceCache sourceCache;

  MachineRecipeReloadListener(RecipeManager target, SourceCache sourceCache) {
    this.target = target;
    this.sourceCache = sourceCache;
  }

  /** The service supplies a fresh cache for each server lifetime. */
  static final class SourceCache {
    private final AtomicReference<SourceSnapshot> snapshot = new AtomicReference<>(
        new SourceSnapshot(Set.of(), Map.of()));
  }

  private record CachedSource(byte[] bytes, JsonElement root) {
  }

  private record SourceSnapshot(Set<String> resourceFields, Map<ResourceLocation, CachedSource> files) {
  }

  private record PreparedSources(
      Map<ResourceLocation, JsonElement> roots,
      Set<ResourceLocation> skippedIds,
      Map<ResourceLocation, ResourceLocation> skippedMachines,
      SourceSnapshot snapshot) {
  }

  @Override
  public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier barrier, ResourceManager resources, ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler, Executor background, Executor game) {
    var previous = sourceCache.snapshot.get();
    // Directory-wide failures reject the prospective manager before the barrier;
    // the previous catalog only belongs to the previously installed manager.
    return CompletableFuture.supplyAsync(() -> read(resources, previous), background).thenCompose(barrier::wait)
        .thenAcceptAsync(prepared -> {
          var scripts = RecipeScriptSources.snapshot(target);
          var nativeOwners = new RecipeDeclarationOwners(prepared.skippedIds(), prepared.skippedMachines());
          var scriptOwners = new RecipeDeclarationOwners(scripts.skippedIds(), scripts.skippedMachines());
          var decoded = new LinkedHashMap<ResourceLocation, MachineRecipeService.DeclaredRecipe>();
          var acceptedScriptIds = new LinkedHashSet<ResourceLocation>();
          var suppliedHolders = new LinkedHashSet<ResourceLocation>();
          var conditionalOps = makeConditionalOps();
          for (var entry : prepared.roots().entrySet()) {
            var id = entry.getKey();
            var root = entry.getValue();
            if (scripts.removedNativeIds().contains(id)) {
              nativeOwners.skip(id, MachineRecipeJson.declaredMachine(root));
              continue;
            }
            var recipe = decodeMachineRecipe(id, root, conditionalOps);
            if (recipe == null) {
              nativeOwners.skip(id, MachineRecipeJson.declaredMachine(root));
            } else {
              nativeOwners.accept(id, recipe.source().machineId());
              decoded.put(id, recipe);
            }
          }
          for (var entry : scripts.declarations().entrySet()) {
            var id = entry.getKey();
            var root = entry.getValue();
            if (decoded.containsKey(id)) {
              // Vanilla may have applied the script's holder at this ID.
              scriptOwners.skip(id, MachineRecipeJson.declaredMachine(root));
              LOGGER.warn("Skipping duplicate native/KubeJS machine recipe {}", id);
              continue;
            }
            var recipe = decodeMachineRecipe(id, root, conditionalOps);
            boolean missingHolder = target.byKey(id).isEmpty();
            if (recipe == null || missingHolder && !scripts.correctedStackPrototypes().contains(id)) {
              scriptOwners.skip(id, MachineRecipeJson.declaredMachine(root));
            } else {
              scriptOwners.accept(id, recipe.source().machineId());
              decoded.put(id, recipe);
              acceptedScriptIds.add(id);
              if (missingHolder) {
                suppliedHolders.add(id);
              }
            }
          }
          var ordinaryFallbacks = new LinkedHashMap<ResourceLocation, RecipeHolder<?>>();
          scripts.ordinaryFallbacks().forEach((id, root) -> {
            if (!scriptOwners.skipped().contains(id) || decoded.containsKey(id)) {
              return;
            }
            try {
              var recipe = decodeRecipe(id, root, conditionalOps);
              if (recipe != null && !(recipe instanceof MachineRecipe)) {
                ordinaryFallbacks.put(id, new RecipeHolder<>(id, recipe));
              }
            } catch (RuntimeException invalid) {
              LOGGER.warn("Skipping invalid original recipe shadowed by KubeJS {}", id, invalid);
            }
          });
          var itemTags = new LinkedHashMap<ResourceLocation, List<Item>>();
          getContext().getAllTags(Registries.ITEM)
              .forEach((id, holders) -> itemTags.put(id, holders.stream().map(holder -> holder.value()).toList()));
          // A manager without a validated catalog must not be installed as a success.
          MachineRecipeService.stage(
              target,
              decoded,
              getRegistryLookup(),
              new RecipeAdapterTags(itemTags),
              nativeOwners,
              scriptOwners,
              ordinaryFallbacks,
              scripts.recipeOrder(),
              scripts.callbackGeneration(),
              acceptedScriptIds,
              suppliedHolders);
          sourceCache.snapshot.compareAndSet(previous, prepared.snapshot());
        }, game).whenComplete((ignored, failure) -> RecipeScriptSources.clear(target));
  }

  private MachineRecipeService.DeclaredRecipe decodeMachineRecipe(ResourceLocation id, JsonElement root, DynamicOps<JsonElement> ops) {
    try {
      if (!ICondition.conditionsMatched(ops, root)) {
        return null;
      }
      var declaration = MachineRecipeJson.decode(root.getAsJsonObject(), ops);
      return MachineRecipeService.DeclaredRecipe.compile(declaration);
    } catch (RuntimeException invalid) {
      LOGGER.warn("Skipping invalid MMCE machine recipe {}", id, invalid);
      return null;
    }
  }

  private static Recipe<?> decodeRecipe(ResourceLocation id, JsonElement root, DynamicOps<JsonElement> ops) {
    return Recipe.CONDITIONAL_CODEC.parse(ops, root).getOrThrow(message -> new JsonParseException(id + ": " + message))
        .map(value -> value.carrier()).orElse(null);
  }

  private static PreparedSources read(ResourceManager resources, SourceSnapshot previous) {
    var selected = new FileToIdConverter("recipe/mmcece", ".json").listMatchingResources(resources);
    if (selected.size() > MAX_FILES) {
      throw new JsonParseException("Too many native MMCE recipe files; at most " + MAX_FILES);
    }
    var nativeIds = new FileToIdConverter("recipe", ".json");
    var fields = RecipeResourceDefinitions.fieldNames();
    var cached = fields.equals(previous.resourceFields()) ? previous.files() : Map.<ResourceLocation, CachedSource>of();
    var roots = new LinkedHashMap<ResourceLocation, JsonElement>();
    var skippedIds = new LinkedHashSet<ResourceLocation>();
    var skippedMachines = new LinkedHashMap<ResourceLocation, ResourceLocation>();
    var next = new LinkedHashMap<ResourceLocation, CachedSource>();
    int remainingBytes = MAX_CACHED_TOTAL_BYTES;
    var entries = selected.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    for (var entry : entries) {
      var id = nativeIds.fileToId(entry.getKey());
      try (var input = new BufferedInputStream(entry.getValue().open())) {
        int limit = Math.min(remainingBytes, MAX_CACHED_FILE_BYTES);
        if (limit == 0) {
          var root = parseIfValid(id, input, skippedMachines);
          if (root != null) {
            roots.put(id, root);
          } else {
            skippedIds.add(id);
          }
          continue;
        }
        input.mark(limit + 1);
        var bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) {
          input.reset();
          var root = parseIfValid(id, input, skippedMachines);
          if (root != null) {
            roots.put(id, root);
          } else {
            skippedIds.add(id);
          }
          continue;
        }
        var source = cached.get(id);
        if (source == null || !Arrays.equals(source.bytes(), bytes)) {
          var root = parseIfValid(id, new ByteArrayInputStream(bytes), skippedMachines);
          if (root == null) {
            skippedIds.add(id);
            continue;
          }
          source = new CachedSource(bytes, root);
        }
        next.put(id, source);
        remainingBytes -= bytes.length;
        // Loading and third-party codecs receive a copy of the cached tree.
        roots.put(id, source.root().deepCopy());
      } catch (IOException | RuntimeException invalid) {
        LOGGER.warn("Skipping unreadable native machine recipe {}", id, invalid);
        roots.remove(id);
        next.remove(id);
        skippedIds.add(id);
      }
    }
    return new PreparedSources(
        Map.copyOf(roots),
        Set.copyOf(skippedIds),
        Map.copyOf(skippedMachines),
        new SourceSnapshot(fields, Map.copyOf(next)));
  }

  static JsonElement parseIfValid(ResourceLocation id, InputStream input, Map<ResourceLocation, ResourceLocation> skippedMachines) {
    JsonElement root = null;
    try {
      var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
      root = JsonParser.parseReader(reader);
      MachineRecipeJson.validate(root);
      if (!root.getAsJsonObject().has("type")) {
        throw new JsonParseException("$.type: Required native recipe type");
      }

      return root;
    } catch (RuntimeException invalid) {
      LOGGER.warn("Skipping invalid native machine recipe {}", id, invalid);
      var machine = MachineRecipeJson.declaredMachine(root);
      if (machine != null) {
        skippedMachines.put(id, machine);
      }
      return null;
    }
  }
}
