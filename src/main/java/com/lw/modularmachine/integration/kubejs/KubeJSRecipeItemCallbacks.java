package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.machine.recipe.RecipeItemCallbackService;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal recipe-reload capture; functions are installed only with a
 * successfully staged catalog.
 */
@ApiStatus.Internal
public final class KubeJSRecipeItemCallbacks {
  private static final int MAX_CALLBACKS = ServerCallbackRegistry.MAX_HANDLERS;
  private static final Map<ResourceLocation, RecipeItemCallbacks.Checker> CHECKERS = new LinkedHashMap<>();
  private static final Map<ResourceLocation, RecipeItemCallbacks.Modifier> MODIFIERS = new LinkedHashMap<>();
  private static final Map<ResourceLocation, RecipeItemCallbacks.InputModifier> INPUT_MODIFIERS = new LinkedHashMap<>();
  private static final Map<ResourceLocation, RecipeItemCallbacks.Checker> ADAPTER_CHECKERS = new LinkedHashMap<>();
  private static final Map<ResourceLocation, RecipeItemCallbacks.Modifier> ADAPTER_MODIFIERS = new LinkedHashMap<>();
  private static final Map<ResourceLocation, RecipeItemCallbacks.InputModifier> ADAPTER_INPUT_MODIFIERS = new LinkedHashMap<>();
  private static final Map<ResourceLocation, KubeJSRecipeBuilderHandlers.Handler> LIFECYCLE = new LinkedHashMap<>();
  private static long generation;
  private static boolean capturing;
  private static boolean initialized;

  public record Snapshot(
      long generation,
      boolean active,
      Map<ResourceLocation, RecipeItemCallbacks.Checker> checkers,
      Map<ResourceLocation, RecipeItemCallbacks.Modifier> modifiers,
      Map<ResourceLocation, RecipeItemCallbacks.InputModifier> inputModifiers,
      List<KubeJSRecipeBuilderHandlers.Handler> lifecycle) {
    public Snapshot {
      checkers = Map.copyOf(checkers);
      modifiers = Map.copyOf(modifiers);
      inputModifiers = Map.copyOf(inputModifiers);
      lifecycle = List.copyOf(lifecycle);
      if (checkers.size() + modifiers.size() + inputModifiers.size() + lifecycle.size() > MAX_CALLBACKS) {
        throw new IllegalArgumentException("Too many KubeJS recipe item callbacks");
      }
      checkers.keySet().forEach(KubeJSRecipeItemCallbacks::requireScriptId);
      modifiers.keySet().forEach(KubeJSRecipeItemCallbacks::requireScriptId);
      inputModifiers.keySet().forEach(KubeJSRecipeItemCallbacks::requireScriptId);
      lifecycle.forEach(handler -> requireScriptId(handler.id()));
    }
  }

  private KubeJSRecipeItemCallbacks() {
  }

  public static synchronized void begin() {
    generation = Math.incrementExact(generation);
    capturing = true;
    initialized = true;
    CHECKERS.clear();
    MODIFIERS.clear();
    INPUT_MODIFIERS.clear();
    LIFECYCLE.clear();
  }

  public static ResourceLocation identity(ResourceLocation recipe, String role) {
    return ResourceLocation.fromNamespaceAndPath(
        "kubejs",
        "mmcece_item_" + UUID.nameUUIDFromBytes((recipe + "/" + role).getBytes(StandardCharsets.UTF_8)).toString()
            .replace("-", ""));
  }

  public static synchronized void checker(ResourceLocation id, RecipeItemCallbacks.Checker checker) {
    requireCapture();
    CHECKERS.put(id, checker);
  }

  public static synchronized void modifier(ResourceLocation id, RecipeItemCallbacks.Modifier modifier) {
    requireCapture();
    MODIFIERS.put(id, modifier);
  }

  public static synchronized void inputModifier(ResourceLocation id, RecipeItemCallbacks.InputModifier modifier) {
    requireCapture();
    INPUT_MODIFIERS.put(id, modifier);
  }

  public static synchronized void lifecycle(KubeJSRecipeBuilderHandlers.Handler handler) {
    requireCapture();
    LIFECYCLE.put(handler.id(), handler);
  }

  public static synchronized void beginAdapters() {
    generation = Math.incrementExact(generation);
    initialized = true;
    capturing = false;
    CHECKERS.clear();
    MODIFIERS.clear();
    INPUT_MODIFIERS.clear();
    LIFECYCLE.clear();
    ADAPTER_CHECKERS.clear();
    ADAPTER_MODIFIERS.clear();
    ADAPTER_INPUT_MODIFIERS.clear();
  }

  public static synchronized void adapterChecker(ResourceLocation id, RecipeItemCallbacks.Checker checker) {
    requireAdapterCapacity();
    ADAPTER_CHECKERS.put(id, checker);
  }

  public static synchronized void adapterModifier(ResourceLocation id, RecipeItemCallbacks.Modifier modifier) {
    requireAdapterCapacity();
    ADAPTER_MODIFIERS.put(id, modifier);
  }

  public static synchronized void adapterInputModifier(ResourceLocation id, RecipeItemCallbacks.InputModifier modifier) {
    requireAdapterCapacity();
    ADAPTER_INPUT_MODIFIERS.put(id, modifier);
  }

  private static void requireAdapterCapacity() {
    if (callbackCount() >= MAX_CALLBACKS) {
      throw new IllegalStateException("Too many recipe adapter item callbacks");
    }
  }

  private static void requireCapture() {
    if (!capturing || callbackCount() >= MAX_CALLBACKS) {
      throw new IllegalStateException("Recipe item callbacks require an active bounded recipe reload");
    }
  }

  private static int callbackCount() {
    return CHECKERS.size() + MODIFIERS.size() + INPUT_MODIFIERS.size() + ADAPTER_CHECKERS.size() + ADAPTER_MODIFIERS
        .size() + ADAPTER_INPUT_MODIFIERS.size() + LIFECYCLE.size();
  }

  private static void requireScriptId(ResourceLocation id) {
    if (!id.getNamespace().equals("kubejs") || id.toString().length() > 256) {
      throw new IllegalArgumentException("KubeJS item callbacks require bounded kubejs IDs");
    }
  }

  public static synchronized void install() {
    install(snapshot());
  }

  public static synchronized Snapshot snapshot() {
    var checkers = new LinkedHashMap<>(ADAPTER_CHECKERS);
    checkers.putAll(CHECKERS);
    var modifiers = new LinkedHashMap<>(ADAPTER_MODIFIERS);
    modifiers.putAll(MODIFIERS);
    var inputModifiers = new LinkedHashMap<>(ADAPTER_INPUT_MODIFIERS);
    inputModifiers.putAll(INPUT_MODIFIERS);
    return new Snapshot(generation, initialized, checkers, modifiers, inputModifiers, List.copyOf(LIFECYCLE.values()));
  }

  public static synchronized boolean isCurrent(Snapshot snapshot) {
    return snapshot.generation() == generation;
  }

  public static synchronized void install(Snapshot snapshot) {
    if (!isCurrent(snapshot)) {
      throw new IllegalStateException("Recipe item callback capture changed before catalog publication");
    }
    if (snapshot.active()) {
      RecipeItemCallbackService
          .installKubeJS(snapshot.generation(), snapshot.checkers(), snapshot.modifiers(), snapshot.inputModifiers());
      KubeJSRecipeBuilderHandlers.install(snapshot.lifecycle());
    }
  }

  public static synchronized void end() {
    capturing = false;
  }

  /**
   * Drops declarations between servers; staged snapshots from the old server
   * become stale.
   */
  public static synchronized void reset() {
    generation = Math.incrementExact(generation);
    capturing = false;
    initialized = false;
    CHECKERS.clear();
    MODIFIERS.clear();
    INPUT_MODIFIERS.clear();
    LIFECYCLE.clear();
    ADAPTER_CHECKERS.clear();
    ADAPTER_MODIFIERS.clear();
    ADAPTER_INPUT_MODIFIERS.clear();
  }
}
