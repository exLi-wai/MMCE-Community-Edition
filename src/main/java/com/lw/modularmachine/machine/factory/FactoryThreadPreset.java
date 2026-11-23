package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
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
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.stream.Collectors;
import org.jetbrains.annotations.ApiStatus;

/**
 * Definition-time channel intent. No live world, recipe registry, or inventory
 * access.
 */
public record FactoryThreadPreset(List<Thread> threads, int maximumExtraThreads) {
  public FactoryThreadPreset(List<Thread> threads) {
    this(threads, -1);
  }
  public record Thread(
      String id,
      ResourceLocation lockedRecipe,
      boolean enabled,
      int priority,
      int parallelLimit,
      Set<ResourceLocation> allowedRecipes,
      boolean core) {
    public Thread(String id, ResourceLocation lockedRecipe, boolean enabled, int priority, int parallelLimit) {
      this(id, lockedRecipe, enabled, priority, parallelLimit, Set.of(), false);
    }

    public Thread {
      allowedRecipes = Set.copyOf(allowedRecipes);
      if (id == null
          || !RecipeExecutionOptions.validThreadName(id)
          || lockedRecipe != null && lockedRecipe.toString().length() > 256
          || priority < FactoryThreadState.MIN_PRIORITY
          || priority > FactoryThreadState.MAX_PRIORITY
          || parallelLimit < 1
          || allowedRecipes.size() > 1024
          || lockedRecipe != null && core && !allowedRecipes.contains(lockedRecipe)) {
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
          new UpgradeSnapshot(0, parallelLimit, 1, 1, 1, 1, 1, 1));
    }
  }

  public FactoryThreadPreset {
    threads = List.copyOf(Objects.requireNonNull(threads, "threads"));
    if (threads.size() > FactoryThreadStateCodec.MAX_THREADS
        || maximumExtraThreads < -1
        || maximumExtraThreads > FactoryThreadStateCodec.MAX_THREADS
        || maximumExtraThreads >= 0
            && threads.stream().filter(thread -> !thread.core()).count() > maximumExtraThreads) {
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
      if (!Set.of("threads", "maximum_threads", "maximum_extra_threads").containsAll(root.keySet())
          || root.has("threads") && !root.get("threads").isJsonArray()) {
        throw new IllegalArgumentException("factory accepts a threads array and maximum_threads");
      }
      int maximumThreads = integer(root, "maximum_threads", defaultMaximumThreads);
      if (maximumThreads < 0 || maximumThreads > FactoryThreadStateCodec.MAX_THREADS) {
        throw new IllegalArgumentException("Factory business thread limit is outside the supported range");
      }
      if (!root.has("threads")) {
        return root.has("maximum_extra_threads")
            && integer(root, "maximum_extra_threads", -1) == 0 ? new FactoryThreadPreset(
                List.of(),
                0) : new FactoryThreadPreset(defaults().threads(), integer(root, "maximum_extra_threads", -1));
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
        if (!Set.of("id", "locked_recipe", "enabled", "priority", "parallel_limit", "allowed_recipes", "core")
            .containsAll(thread.keySet())) {
          throw new IllegalArgumentException("Unknown factory thread field");
        }
        var recipe = thread.get("locked_recipe");
        var allowed = new HashSet<ResourceLocation>();
        if (thread.has("allowed_recipes")) {
          if (!thread.get("allowed_recipes").isJsonArray() || thread.getAsJsonArray("allowed_recipes").size() > 1024) {
            throw new IllegalArgumentException("Invalid core recipe allowlist");
          }
          for (var value : thread.getAsJsonArray("allowed_recipes")) {
            allowed.add(ResourceLocation.parse(string(value)));
          }
        }
        result.add(
            new Thread(
                string(thread.get("id")),
                recipe == null || recipe.isJsonNull() ? null : ResourceLocation.parse(string(recipe)),
                bool(thread, "enabled", true),
                integer(thread, "priority", 0),
                integer(thread, "parallel_limit", 1),
                allowed,
                bool(thread, "core", thread.has("allowed_recipes"))));
      }

      return new FactoryThreadPreset(result, integer(root, "maximum_extra_threads", -1));
    } catch (RuntimeException invalid) {
      throw new JsonParseException(path + ": " + invalid.getMessage(), invalid);
    }
  }

  /** Definition-owned core allowlists intersect user locks at every new start. */
  public static Set<String> allowedRecipes(ResourceLocation machine, String threadId, ResourceLocation lockedRecipe) {
    var preset = corePreset(machine, threadId);
    if (preset == null) {
      return lockedRecipe == null ? null : Set.of(lockedRecipe.toString());
    }
    if (lockedRecipe != null) {
      return preset.allowedRecipes().contains(lockedRecipe) ? Set.of(lockedRecipe.toString()) : Set.of();
    }
    return preset.allowedRecipes().stream().map(ResourceLocation::toString).collect(Collectors.toUnmodifiableSet());
  }

  public static boolean accepts(ResourceLocation machine, String threadId, ResourceLocation recipe) {
    var preset = corePreset(machine, threadId);
    return preset == null || preset.allowedRecipes().contains(recipe);
  }

  private static Thread corePreset(ResourceLocation machine, String threadId) {
    var definition = StructureService.definition(machine);
    if (definition != null) {
      for (var thread : definition.factoryPreset().threads()) {
        if (thread.core() && thread.id().equals(threadId)) {
          return thread;
        }
      }
    }
    return null;
  }

  public boolean isCore(String threadId) {
    return threads.stream().anyMatch(thread -> thread.core() && thread.id().equals(threadId));
  }

  /**
   * Checks new starts only. Explicit extra-thread quotas cannot borrow unused
   * core slots, while native total quotas retain their shared-slot semantics.
   * Occupied IDs include both paid batches and in-flight starts.
   */
  @ApiStatus.Internal
  public boolean startAllowed(String threadId, List<FactoryThreadState> configured, Set<String> occupied, int maximumTotalThreads) {
    var coreIds = threads.stream().filter(Thread::core).map(Thread::id).collect(Collectors.toUnmodifiableSet());
    boolean separate = maximumExtraThreads >= 0;
    boolean core = coreIds.contains(threadId);
    int limit = maximumTotalThreads;
    if (separate) {
      limit = core ? coreIds.size() : maximumExtraThreads;
    }
    int index = 0;
    boolean available = false;
    for (var thread : configured) {
      if (separate && coreIds.contains(thread.threadId()) != core) {
        continue;
      }
      if (thread.threadId().equals(threadId)) {
        available = index < limit;
        break;
      }
      index++;
    }
    if (!available) {
      return false;
    }
    int occupiedSlots = 0;
    for (String other : occupied) {
      if (!other.equals(threadId) && (!separate || coreIds.contains(other) == core)) {
        occupiedSlots++;
      }
    }

    return occupiedSlots < limit;
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
