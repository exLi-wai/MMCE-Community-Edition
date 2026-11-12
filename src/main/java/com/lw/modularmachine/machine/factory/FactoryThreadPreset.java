package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import com.lw.modularmachine.config.MachineConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Definition-time channel intent. No live world, recipe registry, or inventory
 * access.
 */
public record FactoryThreadPreset(List<Thread> threads) {
  public record Thread(String id, ResourceLocation lockedRecipe, boolean enabled, int priority, int parallelLimit) {
    public Thread {
      if (id == null
          || !id.matches("[a-zA-Z0-9_.-]{1,64}")
          || lockedRecipe != null && lockedRecipe.toString().length() > 256
          || priority < FactoryThreadState.MIN_PRIORITY
          || priority > FactoryThreadState.MAX_PRIORITY
          || parallelLimit < 1) {
        throw new IllegalArgumentException("Invalid factory thread preset");
      }
    }

    public FactoryThreadState instantiate(ResourceLocation machine) {
      return new FactoryThreadState(
          id,
          machine,
          lockedRecipe,
          enabled,
          priority,
          parallelLimit,
          0,
          FactoryThreadState.State.IDLE,
          FactoryThreadState.Reason.NONE,
          0,
          new FactoryUpgradeSnapshot(0, parallelLimit, 1, 1, 1, 1, 1, 1));
    }
  }

  public FactoryThreadPreset {
    threads = List.copyOf(Objects.requireNonNull(threads, "threads"));
    if (threads.size() > FactoryThreadStateCodec.MAX_THREADS) {
      throw new IllegalArgumentException("Too many factory thread presets");
    }
    var ids = new HashSet<String>();
    for (var thread : threads) {
      if (!ids.add(thread.id())) {
        throw new IllegalArgumentException("Duplicate factory preset thread: " + thread.id());
      }
    }
  }

  public static FactoryThreadPreset defaults() {
    return new FactoryThreadPreset(List.of(new Thread("main", null, true, 0, 1)));
  }

  public List<FactoryThreadState> instantiate(ResourceLocation machine) {
    Objects.requireNonNull(machine, "machine");

    return threads.stream().map(thread -> thread.instantiate(machine)).toList();
  }

  public static FactoryThreadPreset parse(JsonElement source, String path) {
    return parse(source, path, MachineConfig.defaultFactoryMaxThreads());
  }

  public static FactoryThreadPreset parse(JsonElement source, String path, int defaultMaximumThreads) {
    try {
      if (source == null || !source.isJsonObject()) {
        throw new IllegalArgumentException("Expected factory object");
      }
      var root = source.getAsJsonObject();
      if (!Set.of("threads", "maximum_threads").containsAll(root.keySet())
          || root.has("threads") && !root.get("threads").isJsonArray()) {
        throw new IllegalArgumentException("factory accepts a threads array and maximum_threads");
      }
      int maximumThreads = integer(root, "maximum_threads", defaultMaximumThreads);
      if (maximumThreads < 1 || maximumThreads > FactoryThreadStateCodec.MAX_THREADS) {
        throw new IllegalArgumentException("Factory business thread limit is outside the supported range");
      }
      if (!root.has("threads")) {
        return defaults();
      }
      if (root.getAsJsonArray("threads").size() > maximumThreads) {
        throw new IllegalArgumentException("Too many factory thread presets");
      }
      var result = new ArrayList<Thread>();
      for (var element : root.getAsJsonArray("threads")) {
        if (!element.isJsonObject()) {
          throw new IllegalArgumentException("Expected factory thread object");
        }
        var thread = element.getAsJsonObject();
        if (!Set.of("id", "locked_recipe", "enabled", "priority", "parallel_limit").containsAll(thread.keySet())) {
          throw new IllegalArgumentException("Unknown factory thread field");
        }
        var recipe = thread.get("locked_recipe");
        result.add(
            new Thread(
                string(thread.get("id")),
                recipe == null || recipe.isJsonNull() ? null : ResourceLocation.parse(string(recipe)),
                bool(thread, "enabled", true),
                integer(thread, "priority", 0),
                integer(thread, "parallel_limit", 1)));
      }

      return new FactoryThreadPreset(result);
    } catch (RuntimeException invalid) {
      throw new JsonParseException(path + ": " + invalid.getMessage(), invalid);
    }
  }

  private static String string(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException("Expected string identifier");
    }

    return value.getAsString();
  }

  private static int integer(JsonObject root, String field, int fallback) {
    if (!root.has(field)) {
      return fallback;
    }
    var value = root.get(field);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }

    return value.getAsBigDecimal().intValueExact();
  }

  private static boolean bool(JsonObject root, String field, boolean fallback) {
    if (!root.has(field)) {
      return fallback;
    }
    var value = root.get(field);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw new IllegalArgumentException(field + " must be a boolean");
    }

    return value.getAsBoolean();
  }
}
