package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal recipe-reload capture; functions are installed only with a
 * successfully staged catalog.
 */
@ApiStatus.Internal
public final class RecipeScriptCallbacks {
  private static final int MAX_CALLBACKS = ServerCallbackRegistry.MAX_HANDLERS;
  private record Declaration(ResourceLocation id, JsonObject source, Bindings bindings) {
  }

  private static final List<Declaration> INLINE = new ArrayList<>();
  private static final Map<ResourceLocation, RecipeItemCallbacks.Checker> ADAPTER_CHECKERS = new LinkedHashMap<>();
  private static final Map<ResourceLocation, RecipeItemCallbacks.Modifier> ADAPTER_MODIFIERS = new LinkedHashMap<>();
  private static final Map<ResourceLocation, RecipeItemCallbacks.InputModifier> ADAPTER_INPUT_MODIFIERS = new LinkedHashMap<>();
  private static int inlineCount;
  private static long generation;
  private static Function<ResourceLocation, JsonElement> selectedDeclaration;
  private static boolean initialized;

  /** All inline functions from a single recipe declaration. */
  public record Bindings(
      Map<ResourceLocation, RecipeItemCallbacks.Checker> checkers,
      Map<ResourceLocation, RecipeItemCallbacks.Modifier> modifiers,
      Map<ResourceLocation, RecipeItemCallbacks.InputModifier> inputModifiers,
      List<RecipeDeclaredObservers.Handler> lifecycle,
      List<RecipePreparationHandlers.Handler> preparation) {
    public Bindings {
      checkers = Map.copyOf(checkers);
      modifiers = Map.copyOf(modifiers);
      inputModifiers = Map.copyOf(inputModifiers);
      lifecycle = List.copyOf(lifecycle);
      preparation = List.copyOf(preparation);
    }

    private int size() {
      return checkers.size() + modifiers.size() + inputModifiers.size() + lifecycle.size() + preparation.size();
    }
  }

  public record Snapshot(
      long generation,
      boolean active,
      Map<ResourceLocation, RecipeItemCallbacks.Checker> checkers,
      Map<ResourceLocation, RecipeItemCallbacks.Modifier> modifiers,
      Map<ResourceLocation, RecipeItemCallbacks.InputModifier> inputModifiers,
      List<RecipeDeclaredObservers.Handler> lifecycle,
      List<RecipePreparationHandlers.Handler> preparation) {
    public Snapshot {
      checkers = Map.copyOf(checkers);
      modifiers = Map.copyOf(modifiers);
      inputModifiers = Map.copyOf(inputModifiers);
      lifecycle = List.copyOf(lifecycle);
      preparation = List.copyOf(preparation);
      if (checkers.size() + modifiers.size() + inputModifiers.size() + lifecycle.size() + preparation
          .size() > MAX_CALLBACKS) {
        throw new IllegalArgumentException("Too many KubeJS recipe item callbacks");
      }
      checkers.keySet().forEach(RecipeScriptCallbacks::requireScriptId);
      modifiers.keySet().forEach(RecipeScriptCallbacks::requireScriptId);
      inputModifiers.keySet().forEach(RecipeScriptCallbacks::requireScriptId);
      lifecycle.forEach(handler -> requireScriptId(handler.id()));
      preparation.forEach(handler -> requireScriptId(handler.id()));
    }
  }

  private RecipeScriptCallbacks() {
  }

  static synchronized long begin(Function<ResourceLocation, JsonElement> selected) {
    generation = Math.incrementExact(generation);
    initialized = true;
    clearInlineCallbacks();
    selectedDeclaration = Objects.requireNonNull(selected);
    return generation;
  }

  public static synchronized void capture(ResourceLocation recipe, JsonObject source, Bindings bindings) {
    int size = bindings.size();
    if (size == 0) {
      return;
    }
    if (selectedDeclaration == null) {
      throw new IllegalStateException("Recipe item callbacks require an active recipe reload");
    }
    requireCapacity(size);
    INLINE.add(new Declaration(recipe, source, bindings));
    inlineCount += size;
  }

  public static synchronized void beginAdapters() {
    generation = Math.incrementExact(generation);
    initialized = true;
    clearInlineCallbacks();
    clearAdapterCallbacks();
  }

  public static synchronized void adapterChecker(ResourceLocation id, RecipeItemCallbacks.Checker checker) {
    requireCapacity(ADAPTER_CHECKERS, id);
    ADAPTER_CHECKERS.put(id, checker);
  }

  public static synchronized void adapterModifier(ResourceLocation id, RecipeItemCallbacks.Modifier modifier) {
    requireCapacity(ADAPTER_MODIFIERS, id);
    ADAPTER_MODIFIERS.put(id, modifier);
  }

  public static synchronized void adapterInputModifier(ResourceLocation id, RecipeItemCallbacks.InputModifier modifier) {
    requireCapacity(ADAPTER_INPUT_MODIFIERS, id);
    ADAPTER_INPUT_MODIFIERS.put(id, modifier);
  }

  private static <K> void requireCapacity(Map<K, ?> callbacks, K key) {
    requireCapacity(callbacks.containsKey(key) ? 0 : 1);
  }

  private static void requireCapacity(int additional) {
    if (inlineCount + ADAPTER_CHECKERS.size() + ADAPTER_MODIFIERS.size() + ADAPTER_INPUT_MODIFIERS
        .size() + additional > MAX_CALLBACKS) {
      throw new IllegalStateException("Too many KubeJS recipe callback bindings");
    }
  }

  private static void requireScriptId(ResourceLocation id) {
    if (!id.getNamespace().equals("kubejs") || id.toString().length() > 256) {
      throw new IllegalArgumentException("KubeJS item callbacks require bounded kubejs IDs");
    }
  }

  /**
   * An ID restored from a native source does not admit its skipped script's
   * callbacks.
   */
  static synchronized Snapshot snapshot(long callbackGeneration, Set<ResourceLocation> acceptedRecipes) {
    if (callbackGeneration != RecipeScriptSources.NO_CALLBACK_GENERATION
        && (callbackGeneration != generation || selectedDeclaration == null)) {
      throw new IllegalStateException("Recipe item callback capture changed before catalog staging");
    }
    var checkers = new LinkedHashMap<>(ADAPTER_CHECKERS);
    var modifiers = new LinkedHashMap<>(ADAPTER_MODIFIERS);
    var inputModifiers = new LinkedHashMap<>(ADAPTER_INPUT_MODIFIERS);
    var lifecycle = new ArrayList<RecipeDeclaredObservers.Handler>();
    var preparation = new ArrayList<RecipePreparationHandlers.Handler>();
    if (callbackGeneration != RecipeScriptSources.NO_CALLBACK_GENERATION) {
      for (var declaration : INLINE) {
        // Register only the exact declaration selected by the KubeJS recipe event.
        if (acceptedRecipes.contains(declaration.id())
            && selectedDeclaration.apply(declaration.id()) == declaration.source()) {
          var bindings = declaration.bindings();
          checkers.putAll(bindings.checkers());
          modifiers.putAll(bindings.modifiers());
          inputModifiers.putAll(bindings.inputModifiers());
          lifecycle.addAll(bindings.lifecycle());
          preparation.addAll(bindings.preparation());
        }
      }
    }

    return new Snapshot(generation, initialized, checkers, modifiers, inputModifiers, lifecycle, preparation);
  }

  public static synchronized boolean isCurrent(Snapshot snapshot) {
    return snapshot.generation() == generation;
  }

  public static synchronized void install(Snapshot snapshot) {
    if (!isCurrent(snapshot)) {
      throw new IllegalStateException("Recipe item callback capture changed before catalog publication");
    }
    if (snapshot.active()) {
      RecipeItemCallbackService.installScriptCallbacks(
          snapshot.generation(),
          snapshot.checkers(),
          snapshot.modifiers(),
          snapshot.inputModifiers());
      RecipeDeclaredObservers.install(snapshot.lifecycle());
      RecipePreparationHandlers.install(snapshot.preparation());
    }
  }

  public static synchronized void end(long callbackGeneration) {
    if (callbackGeneration == generation) {
      clearInlineCallbacks();
    }
  }

  /**
   * Drops declarations between servers; staged snapshots from the old server
   * become stale.
   */
  public static synchronized void reset() {
    generation = Math.incrementExact(generation);
    initialized = false;
    clearInlineCallbacks();
    clearAdapterCallbacks();
    RecipePreparationHandlers.install(List.of());
  }

  private static void clearInlineCallbacks() {
    INLINE.clear();
    inlineCount = 0;
    selectedDeclaration = null;
  }

  private static void clearAdapterCallbacks() {
    ADAPTER_CHECKERS.clear();
    ADAPTER_MODIFIERS.clear();
    ADAPTER_INPUT_MODIFIERS.clear();
  }
}
