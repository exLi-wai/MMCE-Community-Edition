package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.controller.ControllerOwnershipApi;
import com.lw.modularmachine.api.controller.ControllerScriptDataApi;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.RunSnapshot;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents.Type;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import java.util.ArrayList;

/**
 * Internal inline check functions, published together with their recipe
 * catalog.
 */
@ApiStatus.Internal
public final class RecipePreparationHandlers {
  public record Handler(
      ResourceLocation id,
      ResourceLocation machine,
      ResourceLocation recipe,
      Type type,
      boolean factoryOnly,
      RecipePreparationEvents.Handler callback) {
  }

  private record Key(ResourceLocation machine, ResourceLocation recipe, Type type) {
  }

  private static Map<Key, List<Handler>> installed = Map.of();
  private static final Set<ResourceLocation> REPORTED = new HashSet<>();
  private static long epoch;
  private static boolean dispatching;

  private RecipePreparationHandlers() {
  }

  static void install(List<Handler> handlers) {
    var grouped = new HashMap<Key, ArrayList<Handler>>();
    for (var handler : handlers) {
      grouped.computeIfAbsent(new Key(handler.machine(), handler.recipe(), handler.type()), key -> new ArrayList<>())
          .add(handler);
    }
    var next = new HashMap<Key, List<Handler>>();
    grouped.forEach((key, values) -> next.put(key, List.copyOf(values)));
    installed = Map.copyOf(next);
    epoch = Math.incrementExact(epoch);
    REPORTED.clear();
  }

  public static long epoch() {
    return epoch;
  }

  public static boolean isDispatching() {
    return dispatching;
  }

  public static boolean has(ResourceLocation machine, ResourceLocation recipe, Type type) {
    return !installed.isEmpty() && installed.containsKey(new Key(machine, recipe, type));
  }

  public static int parallelLimit(RecipeExecutionTicket ticket, String threadId) {
    return ticket.owner() instanceof FactoryControllerBlockEntity factory ? factory
        .effectiveParallelLimit(threadId) : ticket.parallelLimit();
  }

  public static Session begin(StructureService.StructureSubject owner, ResourceLocation recipe, String threadId, Type type, int parallelism, FrozenRecipeRun run) {
    var level = (ServerLevel) owner.structureLevel();
    ServerThreadGuard.require(level, "Recipe preparation");
    boolean factory = owner instanceof FactoryControllerBlockEntity;
    var thread = factory ? Optional.of(threadId) : Optional.<String>empty();
    var registered = installed.isEmpty() ? List.<Handler>of() : installed
        .getOrDefault(new Key(owner.structureMachineId(), recipe, type), List.of());
    var handlers = factory || registered.isEmpty() ? registered : registered.stream()
        .filter(handler -> !handler.factoryOnly()).toList();
    if (handlers.isEmpty()) {
      return new Session(null, type, parallelism, handlers);
    }
    var context = new RecipePreparationEvents.Context(
        level.dimension().location(),
        owner.structurePosition(),
        owner.structureMachineId(),
        recipe,
        factory,
        thread,
        level.getGameTime(),
        Optional.ofNullable(run).map(
            value -> new RunSnapshot(
                value.runId(),
                value.recipeId(),
                value.state(),
                value.parallelInstances(),
                value.progress(),
                value.duration(),
                value.lastProgressTick())),
        ControllerOwnershipApi.inspect(level, owner.structurePosition()).orElseThrow(),
        ControllerScriptDataApi.inspect(level, owner.structurePosition()));
    return new Session(context, type, parallelism, handlers);
  }

  public static final class Session {
    private final RecipePreparationEvents.Context context;
    private final Type type;
    private final List<Handler> handlers;
    private final long generation = epoch;
    private int cursor;
    private int parallelism;
    private boolean rejected;

    private Session(RecipePreparationEvents.Context context, Type type, int parallelism, List<Handler> handlers) {
      this.context = context;
      this.type = type;
      this.parallelism = parallelism;
      this.handlers = handlers;
    }

    public int advance(int budget) {
      if (budget < 0 || dispatching || !current()) {
        throw new IllegalStateException("Invalid recipe preparation continuation");
      }
      int used = 0;
      while (!complete() && used < budget) {
        var handler = handlers.get(cursor++);
        var event = new RecipePreparationEvents.Event(context, type, parallelism);
        dispatching = true;
        try {
          handler.callback().handle(event);
          parallelism = event.getParallelism();
          rejected |= event.isCanceled();
        } catch (RuntimeException failure) {
          rejected = true;
          if (REPORTED.add(handler.id())) {
            ModularMachine.LOGGER.warn("Recipe preparation handler {} failed", handler.id(), failure);
          }
        } finally {
          event.seal();
          dispatching = false;
        }
        used++;
        if (!current()) {
          throw new IllegalStateException("Recipe preparation generation changed during callback");
        }
      }
      return used;
    }

    public boolean current() {
      return generation == epoch;
    }

    public boolean complete() {
      return rejected || cursor == handlers.size();
    }

    public boolean rejected() {
      return rejected;
    }

    public int parallelism() {
      return parallelism;
    }
  }
}
