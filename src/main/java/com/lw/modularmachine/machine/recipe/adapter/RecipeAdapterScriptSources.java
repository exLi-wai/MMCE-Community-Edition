package com.lw.modularmachine.machine.recipe.adapter;

import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal, immutable import declarations from one server-script generation.
 */
@ApiStatus.Internal
public final class RecipeAdapterScriptSources {
  public record Snapshot(
      long scriptGeneration,
      long revision,
      boolean valid,
      Map<ResourceLocation, List<RecipeAdapterImport>> imports) {
  }

  private static Snapshot current = new Snapshot(0, 0, true, Map.of());

  private RecipeAdapterScriptSources() {
  }

  public static synchronized void install(long generation, Map<ResourceLocation, List<RecipeAdapterImport>> imports, boolean valid) {
    if (generation <= current.scriptGeneration()) {
      return;
    }
    var copied = new LinkedHashMap<ResourceLocation, List<RecipeAdapterImport>>();
    if (valid) {
      for (var entry : imports.entrySet()) {
        Objects.requireNonNull(entry.getKey(), "machine");
        var values = List.copyOf(entry.getValue());
        RecipeAdapterDefinitions.validate(values);
        copied.put(entry.getKey(), values);
      }
    }
    var next = Map.copyOf(copied);
    boolean changed = current.valid() != valid || !current.imports().equals(next);
    long revision = changed ? Math.incrementExact(current.revision()) : current.revision();
    current = new Snapshot(generation, revision, valid, next);
  }

  public static synchronized Snapshot snapshot() {
    if (!current.valid()) {
      throw new IllegalStateException("KubeJS recipe adapter import registration failed");
    }
    return current;
  }

  public static synchronized long revision() {
    return current.revision();
  }

  public static synchronized boolean isCurrent(long revision) {
    return current.valid() && current.revision() == revision;
  }

  public static synchronized void clear() {
    boolean changed = !current.valid() || !current.imports().isEmpty();
    current = new Snapshot(
        current.scriptGeneration(),
        changed ? Math.incrementExact(current.revision()) : current.revision(),
        true,
        Map.of());
  }
}
