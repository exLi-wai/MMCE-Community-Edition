package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonElement;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.ApiStatus;

/**
 * Reload-scoped declarations; integrations normalize their live maps at the
 * native apply barrier.
 */
@ApiStatus.Internal
public final class RecipeScriptSources {
  public static final long NO_CALLBACK_GENERATION = 0;

  public record Snapshot(
      Map<ResourceLocation, JsonElement> declarations,
      Set<ResourceLocation> removedNativeIds,
      Set<ResourceLocation> correctedStackPrototypes,
      Set<ResourceLocation> skippedIds,
      Map<ResourceLocation, ResourceLocation> skippedMachines,
      Map<ResourceLocation, JsonElement> ordinaryFallbacks,
      List<ResourceLocation> recipeOrder,
      long callbackGeneration) {
    private static final Snapshot EMPTY = new Snapshot(
        Map.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        Map.of(),
        Map.of(),
        List.of(),
        NO_CALLBACK_GENERATION);

    public Snapshot {
      declarations = Map.copyOf(declarations);
      removedNativeIds = Set.copyOf(removedNativeIds);
      correctedStackPrototypes = Set.copyOf(correctedStackPrototypes);
      skippedIds = Set.copyOf(skippedIds);
      skippedMachines = Map.copyOf(skippedMachines);
      ordinaryFallbacks = Map.copyOf(ordinaryFallbacks);
      recipeOrder = List.copyOf(recipeOrder);
    }
  }

  private record Capture(LongFunction<Snapshot> source, long callbackGeneration) {
  }

  private static final Map<RecipeManager, Capture> CAPTURED = new IdentityHashMap<>();

  private RecipeScriptSources() {
  }

  /**
   * Called by the platform recipe hook; no snapshot is taken before its recipe
   * event.
   */
  public static synchronized void capture(RecipeManager manager, Function<ResourceLocation, JsonElement> selectedDeclaration, LongFunction<Snapshot> source) {
    Objects.requireNonNull(manager, "manager");
    Objects.requireNonNull(source, "source");
    CAPTURED.put(manager, new Capture(source, RecipeScriptCallbacks.begin(selectedDeclaration)));
  }

  public static Snapshot snapshot(RecipeManager manager) {
    Capture current;
    synchronized (RecipeScriptSources.class) {
      current = CAPTURED.get(manager);
    }
    return current == null ? Snapshot.EMPTY : current.source().apply(current.callbackGeneration());
  }

  /**
   * Ends this manager's capture while retaining its staged callback values.
   */
  public static synchronized void clear(RecipeManager manager) {
    var current = CAPTURED.remove(manager);
    if (current != null) {
      RecipeScriptCallbacks.end(current.callbackGeneration());
    }
  }

  public static synchronized void clear() {
    CAPTURED.values().forEach(current -> RecipeScriptCallbacks.end(current.callbackGeneration()));
    CAPTURED.clear();
  }
}
