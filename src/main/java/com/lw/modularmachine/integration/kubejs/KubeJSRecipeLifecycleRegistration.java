package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.machine.recipe.RecipeLifecycleEvents;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Registration builder for read-only recipe lifecycle notifications and bounded
 * failure policies.
 */
public final class KubeJSRecipeLifecycleRegistration implements KubeEvent {
  private static final int MAX_HANDLERS = 256;
  private static final Set<RecipeLifecycleEvents.Type> DEFAULT_TYPES = Set
      .of(RecipeLifecycleEvents.Type.STARTED, RecipeLifecycleEvents.Type.FINISHED);

  private final Map<ResourceLocation, Handler> handlers = new LinkedHashMap<>();
  private final Map<ResourceLocation, RecipeLifecycleEvents.FailurePolicy> failurePolicies = new LinkedHashMap<>();
  private final Set<ResourceLocation> identifiers = new LinkedHashSet<>();
  private boolean valid = true;

  public void onEvent(String text, RecipeLifecycleEvents.Listener listener) {
    add(text, DEFAULT_TYPES, listener);
  }

  public void onType(String text, RecipeLifecycleEvents.Type type, RecipeLifecycleEvents.Listener listener) {
    add(text, type == null ? Set.of() : Set.of(type), listener);
  }

  public void onResourceFailure(String text, RecipeLifecycleEvents.FailurePolicy policy) {
    ResourceLocation id = text == null ? null : ResourceLocation.tryParse(text);
    if (id == null
        || !id.getNamespace().equals("kubejs")
        || id.getPath().isEmpty()
        || id.toString().length() > 256
        || !id.toString().equals(text)
        || policy == null
        || !identifiers.add(id)
        || identifiers.size() > MAX_HANDLERS) {
      valid = false;
      return;
    }
    failurePolicies.put(id, policy);
  }

  private void add(String text, Set<RecipeLifecycleEvents.Type> types, RecipeLifecycleEvents.Listener listener) {
    ResourceLocation id = text == null ? null : ResourceLocation.tryParse(text);
    if (id == null
        || !id.getNamespace().equals("kubejs")
        || id.getPath().isEmpty()
        || id.toString().length() > 256
        || !id.toString().equals(text)
        || listener == null
        || types.isEmpty()
        || !identifiers.add(id)
        || identifiers.size() > MAX_HANDLERS) {
      valid = false;
      return;
    }
    handlers.put(id, new Handler(id, types, listener));
  }

  boolean valid() {
    return valid;
  }

  List<Handler> snapshot() {
    if (!valid) {
      return List.of();
    }
    var result = new ArrayList<>(handlers.values());
    result.sort(Comparator.comparing(handler -> handler.id().toString()));

    return List.copyOf(result);
  }

  List<FailurePolicy> failurePolicies() {
    if (!valid) {
      return List.of();
    }
    var result = new ArrayList<FailurePolicy>(failurePolicies.size());
    failurePolicies.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
        .forEach(entry -> result.add(new FailurePolicy(entry.getKey(), entry.getValue())));

    return List.copyOf(result);
  }

  record Handler(ResourceLocation id, Set<RecipeLifecycleEvents.Type> types, RecipeLifecycleEvents.Listener listener) {
    Handler {
      types = Set.copyOf(types);
    }
  }

  record FailurePolicy(ResourceLocation id, RecipeLifecycleEvents.FailurePolicy policy) {
  }
}
