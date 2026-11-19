package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.resource.ContextAwareReloadListener;

/**
 * Dedicated strict recipe/mmcece directory; native tags/conditions are resolved
 * after the reload barrier.
 */
public final class MachineRecipeReloadListener extends ContextAwareReloadListener {
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

  private record PreparedSources(Map<ResourceLocation, JsonElement> roots, SourceSnapshot snapshot) {
  }

  @Override
  public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier barrier, ResourceManager resources, ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler, Executor background, Executor game) {
    var previous = sourceCache.snapshot.get();
    return CompletableFuture.supplyAsync(() -> read(resources, previous), background).thenCompose(barrier::wait)
        .thenAcceptAsync(prepared -> {
          try {
            var sources = new LinkedHashMap<>(prepared.roots());
            for (var entry : RecipeScriptSources.snapshot().entrySet()) {
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
            sourceCache.snapshot.compareAndSet(previous, prepared.snapshot());
          } finally {
            RecipeScriptSources.clear();
          }
        }, game);
  }

  private static PreparedSources read(ResourceManager resources, SourceSnapshot previous) {
    var selected = new FileToIdConverter("recipe/mmcece", ".json").listMatchingResources(resources);
    if (selected.size() > MAX_FILES) {
      throw new JsonParseException("Too many native MMCE recipe files");
    }
    var nativeIds = new FileToIdConverter("recipe", ".json");
    var fields = RecipeResourceDefinitions.Fields.names();
    var cached = fields.equals(previous.resourceFields()) ? previous.files() : Map.<ResourceLocation, CachedSource>of();
    var roots = new LinkedHashMap<ResourceLocation, JsonElement>();
    var next = new LinkedHashMap<ResourceLocation, CachedSource>();
    int remainingBytes = MAX_CACHED_TOTAL_BYTES;
    for (var entry : selected.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      var id = nativeIds.fileToId(entry.getKey());
      try (var input = new BufferedInputStream(entry.getValue().open())) {
        int limit = Math.min(remainingBytes, MAX_CACHED_FILE_BYTES);
        if (limit == 0) {
          roots.put(id, parse(input));
          continue;
        }
        input.mark(limit + 1);
        var bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) {
          input.reset();
          roots.put(id, parse(input));
          continue;
        }
        var source = cached.get(id);
        if (source == null || !Arrays.equals(source.bytes(), bytes)) {
          source = new CachedSource(bytes, parse(new ByteArrayInputStream(bytes)));
        }
        next.put(id, source);
        remainingBytes -= bytes.length;
        // Loading and third-party codecs receive a copy of the cached tree.
        roots.put(id, source.root().deepCopy());
      } catch (IOException | RuntimeException invalid) {
        throw new JsonParseException(id + ": Invalid native machine recipe", invalid);
      }
    }
    return new PreparedSources(Map.copyOf(roots), new SourceSnapshot(fields, Map.copyOf(next)));
  }

  private static JsonElement parse(InputStream input) {
    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    var root = JsonParser.parseReader(reader);
    MachineRecipeJson.validate(root);
    if (!root.getAsJsonObject().has("type")) {
      throw new JsonParseException("$.type: Required native recipe type");
    }
    return root;
  }
}
