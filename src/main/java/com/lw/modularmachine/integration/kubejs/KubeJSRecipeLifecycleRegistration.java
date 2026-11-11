package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.api.recipe.RecipeCommandActions;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Registration builder for read-only recipe lifecycle notifications and bounded
 * failure policies.
 */
public final class KubeJSRecipeLifecycleRegistration implements KubeEvent {
  private static final Set<RecipeLifecycleEvents.Type> DEFAULT_TYPES = Set
      .of(RecipeLifecycleEvents.Type.STARTED, RecipeLifecycleEvents.Type.FINISHED);

  private final Map<ResourceLocation, Handler> handlers = new LinkedHashMap<>();
  private final Map<ResourceLocation, RecipeLifecycleEvents.FailurePolicy> failurePolicies = new LinkedHashMap<>();
  private final Set<ResourceLocation> identifiers = new LinkedHashSet<>();
  private final Thread registrationThread = Thread.currentThread();
  private final Map<ResourceLocation, RecipeCommandActions.Action> commands = new LinkedHashMap<>();
  private boolean valid = true;
  private boolean open = true;

  public void onEvent(String text, RecipeLifecycleEvents.Listener listener) {
    add(text, DEFAULT_TYPES, listener);
  }

  public void onType(String text, RecipeLifecycleEvents.Type type, RecipeLifecycleEvents.Listener listener) {
    add(text, type == null ? Set.of() : Set.of(type), listener);
  }

  public void onResourceFailure(String text, RecipeLifecycleEvents.FailurePolicy policy) {
    requireOpen();
    ResourceLocation id = KubeJSRegistrationIds.parse(text);
    if (id == null
        || policy == null
        || !KubeJSRegistrationIds.claim(identifiers, id, KubeJSRegistrationIds.MAX_HANDLERS)) {
      valid = false;
      return;
    }
    failurePolicies.put(id, policy);
  }

  private void add(String text, Set<RecipeLifecycleEvents.Type> types, RecipeLifecycleEvents.Listener listener) {
    requireOpen();
    ResourceLocation id = KubeJSRegistrationIds.parse(text);
    if (id == null
        || listener == null
        || types.isEmpty()
        || !KubeJSRegistrationIds.claim(identifiers, id, KubeJSRegistrationIds.MAX_HANDLERS)) {
      valid = false;
      return;
    }
    handlers.put(id, new Handler(id, types, listener));
  }

  public CommandBuilder onStartedCommand(String id, String machine, String command) {
    return command(id, machine, RecipeLifecycleEvents.Type.STARTED, 1, command);
  }

  public CommandBuilder onTickCommand(String id, String machine, int interval, String command) {
    return command(id, machine, RecipeLifecycleEvents.Type.TICK, interval, command);
  }

  public CommandBuilder onFinishedCommand(String id, String machine, String command) {
    return command(id, machine, RecipeLifecycleEvents.Type.FINISHED, 1, command);
  }

  private CommandBuilder command(String text, String machine, RecipeLifecycleEvents.Type type, int interval, String command) {
    requireOpen();
    var id = KubeJSRegistrationIds.parse(text);
    if (id == null) {
      valid = false;
      throw new IllegalArgumentException("Invalid KubeJS recipe command ID");
    }
    try {
      return new CommandBuilder(id, ResourceLocation.parse(machine), type, interval, command);
    } catch (RuntimeException invalid) {
      valid = false;
      throw invalid;
    }
  }

  private void requireOpen() {
    if (Thread.currentThread() != registrationThread) {
      throw new IllegalStateException("Recipe lifecycle registration used outside its creating thread");
    }
    if (!open) {
      throw new IllegalStateException("Recipe lifecycle registration is closed");
    }
  }

  void seal() {
    requireOpen();
    open = false;
  }

  List<RecipeCommandActions.Action> commands() {
    return valid ? commands.values().stream().sorted(Comparator.comparing(action -> action.id().toString()))
        .toList() : List.of();
  }

  public final class CommandBuilder {
    private final ResourceLocation id, machine;
    private final RecipeLifecycleEvents.Type type;
    private final int interval;
    private final String command;
    private Optional<ResourceLocation> recipe = Optional.empty();
    private RecipeCommandActions.Source source = RecipeCommandActions.Source.CONTROLLER;
    private int permission = 2;
    private boolean built;

    private CommandBuilder(ResourceLocation id, ResourceLocation machine, RecipeLifecycleEvents.Type type, int interval, String command) {
      this.id = id;
      this.machine = machine;
      this.type = type;
      this.interval = interval;
      this.command = command;
    }

    private void editable() {
      requireOpen();
      if (built) {
        throw new IllegalStateException("Recipe command already built");
      }
    }

    public CommandBuilder recipe(String id) {
      editable();
      recipe = Optional.of(ResourceLocation.parse(id));
      return this;
    }

    public CommandBuilder source(String value) {
      editable();
      source = RecipeCommandActions.Source.valueOf(value.toUpperCase(Locale.ROOT));
      return this;
    }

    public CommandBuilder permissionLevel(int value) {
      editable();
      if (value < 0 || value > 2) {
        throw new IllegalArgumentException("Recipe command permission must be between 0 and 2");
      }
      permission = value;
      return this;
    }

    public void build() {
      editable();
      try {
        var action = new RecipeCommandActions.Action(id, machine, recipe, type, interval, command, source, permission);
        if (!KubeJSRegistrationIds.claim(identifiers, id, KubeJSRegistrationIds.MAX_HANDLERS)) {
          throw new IllegalArgumentException("Duplicate or excessive recipe lifecycle registration");
        }
        commands.put(id, action);
        built = true;
      } catch (RuntimeException invalid) {
        valid = false;
        throw invalid;
      }
    }
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
