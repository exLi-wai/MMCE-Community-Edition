package com.lw.modularmachine.machine.recipe.command;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeCommandActions;
import com.lw.modularmachine.api.recipe.RecipeCommandActions.Action;
import com.lw.modularmachine.api.recipe.RecipeCommandActions.Source;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Context;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Type;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.StructureService.StructureSubject;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal post-transaction dispatch, bounded claims and command source checks.
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = "mmcece")
public final class RecipeCommandService {
  private static final int MAX_ACTIONS = 128;
  private static final ServerCallbackRegistry<Action> JAVA_ACTIONS = new ServerCallbackRegistry<>(() -> {
  });
  private static Map<ResourceLocation, Action> kubejsActions = Map.of();
  private static Map<Route, List<Action>> routes = Map.of();
  private static Set<Type> activeTypes = Set.of();
  private static final ArrayDeque<ControllerCommandSubject> ready = new ArrayDeque<>();
  private static final Set<ControllerCommandSubject> scheduled = Collections.newSetFromMap(new IdentityHashMap<>());
  private static final Map<ControllerCommandSubject, Long> warned = new WeakHashMap<>();
  private static boolean running, kubejsReady, executing;

  private record Route(ResourceLocation machine, Type type) {
  }

  private RecipeCommandService() {
  }

  public static void register(Action action) {
    ServerCallbackRegistry.requireServer();
    java.util.Objects.requireNonNull(action, "action");
    if (kubejsActions.containsKey(action.id())
        || JAVA_ACTIONS.find(action.id()) == null
            && JAVA_ACTIONS.snapshot().size() + kubejsActions.size() >= MAX_ACTIONS) {
      throw new IllegalArgumentException("Duplicate or excessive recipe command registration");
    }
    if (!action.equals(JAVA_ACTIONS.find(action.id()))) {
      JAVA_ACTIONS.register(action.id(), action);
      rebuildRoutes();
    }
  }

  public static void remove(ResourceLocation id) {
    if (JAVA_ACTIONS.remove(id)) {
      rebuildRoutes();
    }
  }

  public static void installKubeJS(List<Action> actions) {
    ServerCallbackRegistry.requireServer();
    var next = new LinkedHashMap<ResourceLocation, Action>();
    for (var action : actions) {
      if (JAVA_ACTIONS.find(action.id()) != null || next.putIfAbsent(action.id(), action) != null) {
        throw new IllegalArgumentException("Duplicate recipe command ID: " + action.id());
      }
    }
    if (next.size() + JAVA_ACTIONS.snapshot().size() > MAX_ACTIONS) {
      throw new IllegalArgumentException("Recipe command registration limit exceeded");
    }
    kubejsActions = Map.copyOf(next);
    rebuildRoutes();
    kubejsReady = true;
  }

  private static void rebuildRoutes() {
    var actions = new ArrayList<>(JAVA_ACTIONS.snapshot().values());
    actions.addAll(kubejsActions.values());
    actions.sort(Comparator.comparing(action -> action.id().toString()));
    var next = new HashMap<Route, List<Action>>();
    var types = EnumSet.noneOf(Type.class);
    for (var action : actions) {
      types.add(action.type());
      next.computeIfAbsent(new Route(action.machine(), action.type()), ignored -> new ArrayList<>()).add(action);
    }
    next.replaceAll((ignored, values) -> List.copyOf(values));
    routes = Map.copyOf(next);
    activeTypes = Set.copyOf(types);
  }

  public static boolean hasActions(Type type) {
    return activeTypes.contains(type);
  }

  public static boolean wantsCapture(StructureSubject owner, Type type) {
    if (!(owner instanceof ControllerCommandSubject controller) || !controller.commandState().valid()) {
      return false;
    }

    return !routes.getOrDefault(new Route(owner.structureMachineId(), type), List.of()).isEmpty()
        || (type == Type.FINISHED || type == Type.DISCARDED) && controller.commandState().hasMarks();
  }

  public static void capture(StructureSubject owner, Context context) {
    if (!(owner instanceof ControllerCommandSubject controller)
        || !controller.commandState().valid()
        || !(owner.structureLevel() instanceof ServerLevel level)
        || UpgradeLifecyclePublisher.isDispatchingAny()
        || SmartInterfaceEventPublisher.isDispatching()) {
      return;
    }
    ServerThreadGuard.require(level, "Recipe command capture");
    if (owner.structureRemoved()
        || !level.hasChunkAt(owner.structurePosition())
        || level.getBlockEntity(owner.structurePosition()) != owner
        || !context.dimension().equals(level.dimension().location())
        || !context.controllerPosition().equals(owner.structurePosition())
        || !context.machine().equals(owner.structureMachineId())) {
      return;
    }
    var state = controller.commandState();
    var runs = context.type() == Type.FINISHED || context.type() == Type.DISCARDED ? context.before() : context.after();
    var actions = routes.getOrDefault(new Route(context.machine(), context.type()), List.of());
    boolean changed = false;
    for (var run : runs) {
      for (var action : actions) {
        if (action.recipe().isPresent() && !action.recipe().get().equals(run.recipeId())
            || action.type() == Type.TICK && run.progress() % action.interval() != 0) {
          continue;
        }
        try {
          if (!context.ownership().valid()) {
            throw new IllegalArgumentException("Invalid player ownership");
          }
          var command = render(
              action.command(),
              context.machine(),
              run.recipeId(),
              run.runId(),
              run.parallelInstances(),
              run.progress(),
              context.dimension(),
              context.controllerPosition(),
              context.ownership().ownerId());
          var request = new ControllerCommandState.Pending(
              action,
              run.runId(),
              run.recipeId(),
              run.progress(),
              run.parallelInstances(),
              context.tick(),
              context.dimension(),
              context.controllerPosition(),
              context.ownership().ownerId(),
              command);
          var admission = state.enqueue(request);
          changed |= admission != ControllerCommandState.Admission.DUPLICATE;
          if (admission == ControllerCommandState.Admission.REJECTED) {
            warn(controller, context.tick(), "command queue or marker capacity");
          }
        } catch (IllegalArgumentException invalid) {
          state.rejected();
          changed = true;
          warn(controller, context.tick(), "invalid source data or command template");
        }
      }
      if (context.type() == Type.FINISHED || context.type() == Type.DISCARDED) {
        state.retire(run.runId());
        changed = true;
      }
    }
    if (changed) {
      ((BlockEntity) controller).setChanged();
    }
    schedule(controller);
  }

  private static String render(String template, ResourceLocation machine, ResourceLocation recipe, UUID run, int parallel, int progress, ResourceLocation dimension, BlockPos position, Optional<UUID> owner) {
    String result = template.replace("${machine}", machine.toString()).replace("${recipe}", recipe.toString())
        .replace("${run_id}", run.toString()).replace("${parallel}", Integer.toString(parallel))
        .replace("${progress}", Integer.toString(progress)).replace("${dimension}", dimension.toString())
        .replace("${x}", Integer.toString(position.getX())).replace("${y}", Integer.toString(position.getY()))
        .replace("${z}", Integer.toString(position.getZ()));
    if (result.contains("${owner_uuid}")) {
      result = result.replace(
          "${owner_uuid}",
          owner.orElseThrow(() -> new IllegalArgumentException("Command requires an owner")).toString());
    }
    if (result.contains("${") || result.length() > RecipeCommandActions.MAX_COMMAND_LENGTH) {
      throw new IllegalArgumentException("Unknown or excessive command template");
    }

    return result;
  }

  public static void schedule(ControllerCommandSubject owner) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Recipe command scheduling");
    if (!owner.structureRemoved()
        && level.hasChunkAt(owner.structurePosition())
        && level.getBlockEntity(owner.structurePosition()) == owner
        && owner.commandState().hasPending()
        && scheduled.add(owner)) {
      ready.addLast(owner);
    }
  }

  public static void unschedule(ControllerCommandSubject owner) {
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Recipe command removal");
    if (scheduled.remove(owner)) {
      ready.remove(owner);
    }
    warned.remove(owner);
  }

  public static Optional<RecipeCommandActions.Status> inspect(ServerLevel level, BlockPos position) {
    ServerThreadGuard.require(level, "Recipe command status");
    if (level.hasChunkAt(position)
        && level.getBlockEntity(position) instanceof ControllerCommandSubject owner
        && !owner.structureRemoved()) {
      return Optional.of(owner.commandState().status());
    }

    return Optional.empty();
  }

  @SubscribeEvent
  public static void started(ServerStartedEvent event) {
    running = true;
  }

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post event) {
    if (!running
        || !kubejsReady
        || executing
        || UpgradeLifecyclePublisher.isDispatchingAny()
        || SmartInterfaceEventPublisher.isDispatching()) {
      return;
    }
    ServerThreadGuard.require(event.getServer(), "Recipe command dispatch");
    long deadline = System.nanoTime() + 2_000_000L;
    var visits = new IdentityHashMap<ControllerCommandSubject, Integer>();
    var deferred = new ArrayList<ControllerCommandSubject>();
    int work = 0;
    while (!ready.isEmpty() && work++ < 64 && System.nanoTime() < deadline) {
      var owner = ready.removeFirst();
      scheduled.remove(owner);
      if (!(owner.structureLevel() instanceof ServerLevel level)
          || level.getServer() != event.getServer()
          || owner.structureRemoved()
          || !level.hasChunkAt(owner.structurePosition())
          || level.getBlockEntity(owner.structurePosition()) != owner
          || !owner.commandState().hasPending()) {
        continue;
      }
      if (!owner.commandExecutionAllowed() || visits.getOrDefault(owner, 0) >= 8) {
        deferred.add(owner);
        continue;
      }
      visits.merge(owner, 1, Integer::sum);
      var state = owner.commandState();
      var request = state.claim();
      ((BlockEntity) owner).setChanged();
      var feedback = new Feedback();
      var source = source(level, owner, request, feedback);
      if (source == null) {
        state.skipped();
      } else {
        executing = true;
        try {
          level.getServer().getCommands().performPrefixedCommand(source, request.command());
          if (feedback.failed) {
            state.failed();
          }
        } catch (RuntimeException failure) {
          state.failed();
          ModularMachine.LOGGER
              .warn("Recipe command {} failed at {}", request.action().id(), owner.structurePosition(), failure);
        } finally {
          executing = false;
        }
      }
      ((BlockEntity) owner).setChanged();
      schedule(owner);
    }
    deferred.forEach(RecipeCommandService::schedule);
  }

  private static CommandSourceStack source(ServerLevel level, ControllerCommandSubject owner, ControllerCommandState.Pending request, Feedback feedback) {
    var current = JAVA_ACTIONS.find(request.action().id());
    if (current == null) {
      current = kubejsActions.get(request.action().id());
    }
    if (!request.action().equals(current)
        || !request.dimension().equals(level.dimension().location())
        || !request.position().equals(owner.structurePosition())
        || !request.action().machine().equals(owner.structureMachineId())
        || !owner.ownershipState().valid()
        || !request.owner().equals(owner.ownershipState().ownerId())
        || owner.structureRemoved()
        || !level.hasChunkAt(owner.structurePosition())
        || level.getBlockEntity(owner.structurePosition()) != owner) {
      return null;
    }
    try {
      if (!request.command().equals(
          render(
              request.action().command(),
              request.action().machine(),
              request.recipe(),
              request.run(),
              request.parallel(),
              request.progress(),
              request.dimension(),
              request.position(),
              request.owner()))) {
        return null;
      }
    } catch (IllegalArgumentException invalid) {
      return null;
    }
    int permission = Math.min(request.action().permissionLevel(), MachineConfig.recipeCommandPermissionLevel());
    if (request.action().source() == Source.OWNER) {
      var player = request.owner().map(id -> level.getServer().getPlayerList().getPlayer(id)).orElse(null);
      if (player == null) {
        return null;
      }
      var playerSource = player.createCommandSourceStack();
      while (permission > 0 && !playerSource.hasPermission(permission)) {
        permission--;
      }
      return new CommandSourceStack(
          feedback,
          Vec3.atLowerCornerOf(owner.structurePosition()),
          Vec2.ZERO,
          level,
          permission,
          player.getGameProfile().getName(),
          player.getDisplayName(),
          level.getServer(),
          player);
    }

    return new CommandSourceStack(
        feedback,
        Vec3.atLowerCornerOf(owner.structurePosition()),
        Vec2.ZERO,
        level,
        permission,
        "@[MMCECE-Controller]",
        Component.literal("@[MMCECE-Controller]"),
        level.getServer(),
        null);
  }

  private static void warn(ControllerCommandSubject owner, long tick, String reason) {
    if (!Long.valueOf(tick).equals(warned.put(owner, tick))) {
      ModularMachine.LOGGER.warn("Recipe command rejected at {}: {}", owner.structurePosition(), reason);
    }
  }

  private static final class Feedback implements CommandSource {
    private boolean failed;

    @Override
    public void sendSystemMessage(Component message) {
      failed = true;
    }

    @Override
    public boolean acceptsSuccess() {
      return false;
    }

    @Override
    public boolean acceptsFailure() {
      return true;
    }

    @Override
    public boolean shouldInformAdmins() {
      return false;
    }
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    JAVA_ACTIONS.clear();
    kubejsActions = Map.of();
    routes = Map.of();
    activeTypes = Set.of();
    ready.clear();
    scheduled.clear();
    warned.clear();
    running = false;
    kubejsReady = false;
    executing = false;
  }
}
