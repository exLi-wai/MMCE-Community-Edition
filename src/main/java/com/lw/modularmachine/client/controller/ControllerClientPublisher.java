package com.lw.modularmachine.client.controller;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.client.controller.ControllerClientEvents;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.integration.kubejs.KubeJSControllerClientRegistration;
import com.lw.modularmachine.machine.appearance.ControllerAppearance;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal client visual proposal publisher. Script functions receive no live
 * rendering or world object.
 */
@ApiStatus.Internal
public final class ControllerClientPublisher {
  public static final int MODEL_VIEW_DISTANCE = 64;

  private record Selection(long tick, Optional<KubeJSControllerClientRegistration.Model> model) {
  }

  private static KubeJSControllerClientRegistration.Snapshot snapshot = KubeJSControllerClientRegistration.Snapshot.EMPTY;
  private static final Map<BlockEntity, Selection> SELECTIONS = new IdentityHashMap<>();
  /** Worker readers use only identity membership, never the live block entity. */
  private static final Set<BlockEntity> PUBLISHED_MODELS = ConcurrentHashMap.newKeySet();
  private static final class ModelDiscovery {
    final Set<BlockEntity> pending = ConcurrentHashMap.newKeySet();
    final ConcurrentLinkedQueue<BlockEntity> queue = new ConcurrentLinkedQueue<>();
  }

  private static final ModelDiscovery MODEL_DISCOVERY = new ModelDiscovery();
  private static final Set<ResourceLocation> WARNED = new HashSet<>();
  private static final Map<Long, BlockEntity> ACTIVE_MODELS = new ConcurrentHashMap<>();
  private static Object world;
  private static Runnable rendererInvalidation = () -> {
  };

  private ControllerClientPublisher() {
  }

  public static void install(KubeJSControllerClientRegistration.Snapshot next) {
    var minecraft = Minecraft.getInstance();
    minecraft.execute(() -> {
      snapshot = next;
      invalidate();
      WARNED.clear();
    });
  }

  public static void setRendererInvalidation(Runnable invalidation) {
    rendererInvalidation = invalidation;
  }

  public static void invalidate() {
    requireClientThread();
    var level = Minecraft.getInstance().level;
    var previous = List.copyOf(SELECTIONS.keySet());
    SELECTIONS.clear();
    PUBLISHED_MODELS.clear();
    // Keep pending identities across resets, including concurrent worker enqueues.
    // The client tick rejects old-world identities before selecting a model.
    ACTIVE_MODELS.clear();
    world = level;
    rendererInvalidation.run();
    if (level != null) {
      previous.forEach(ControllerClientPublisher::requestModelUpdate);
    }
  }

  private static void ensureWorld() {
    requireClientThread();
    if (world != Minecraft.getInstance().level) {
      invalidate();
    }
  }

  public static void tick() {
    ensureWorld();
    var level = Minecraft.getInstance().level;
    if (level == null) {
      return;
    }
    long deadline = System.nanoTime() + 500_000L;
    var discovery = MODEL_DISCOVERY;
    for (int work = 0; work < 32 && System.nanoTime() < deadline; work++) {
      var entity = discovery.queue.poll();
      if (entity == null) {
        break;
      }
      try {
        if (!entity.isRemoved()
            && entity.getLevel() == level
            && level.hasChunkAt(entity.getBlockPos())
            && level.getBlockEntity(entity.getBlockPos()) == entity) {
          selectModel(entity);
        }
      } finally {
        discovery.pending.remove(entity);
      }
    }
    var entries = SELECTIONS.keySet().iterator();
    var camera = Minecraft.getInstance().getBlockEntityRenderDispatcher().camera;
    while (entries.hasNext()) {
      var entity = entries.next();
      if (entity.isRemoved()
          || entity.getLevel() != level
          || !level.hasChunkAt(entity.getBlockPos())
          || level.getBlockEntity(entity.getBlockPos()) != entity) {
        boolean published = PUBLISHED_MODELS.remove(entity);
        boolean active = ACTIVE_MODELS.remove(entity.getBlockPos().asLong(), entity);
        if (published || active) {
          requestModelUpdate(entity);
        }
        entries.remove();
      } else if (camera == null || !inModelRange(entity, camera.getPosition())) {
        // The dispatcher does not call render beyond its distance limit. Restore
        // the baked mesh on this transition, not on frames skipped by culling.
        modelRendered(entity, false);
      }
    }
  }

  public static ControllerClientEvents.Context context(BlockEntity entity, String customData) {
    requireClientThread();
    var level = entity.getLevel();
    if (level == null || !level.isClientSide) {
      throw new IllegalStateException("Controller visual context requires a client world");
    }
    boolean factory = entity instanceof FactoryControllerBlockEntity;
    ResourceLocation machine = factory ? ((FactoryControllerBlockEntity) entity)
        .machineId() : ((MachineControllerBlockEntity) entity).machineId();
    var state = entity.getBlockState();
    boolean formed = state.hasProperty(ControllerAppearance.FORMED) && state.getValue(ControllerAppearance.FORMED);
    String appearance = state.hasProperty(ControllerAppearance.APPEARANCE) ? state
        .getValue(ControllerAppearance.APPEARANCE).getSerializedName() : "unformed";
    return new ControllerClientEvents.Context(
        machine,
        level.dimension().location(),
        entity.getBlockPos(),
        factory,
        formed,
        appearance,
        Math.max(0, level.getGameTime()),
        customData);
  }

  private static <T> void notifyHandlers(List<KubeJSControllerClientRegistration.Handler<T>> handlers, ResourceLocation machine, Consumer<T> invoke) {
    for (var handler : handlers) {
      if (!handler.machine().equals(machine)) {
        continue;
      }
      try {
        invoke.accept(handler.listener());
      } catch (RuntimeException failure) {
        if (WARNED.add(handler.id())) {
          ModularMachine.LOGGER.warn("Controller client callback {} failed", handler.id(), failure);
        }
      }
    }
  }

  public static Optional<KubeJSControllerClientRegistration.Model> selectModel(BlockEntity entity) {
    ensureWorld();
    if (entity.isRemoved() || entity.getLevel() == null || !entity.getLevel().isClientSide) {
      return Optional.empty();
    }
    var context = context(entity, "{}");
    var previous = SELECTIONS.get(entity);
    if (previous != null && previous.tick() == context.tick()) {
      return previous.model();
    }
    var event = new ControllerClientEvents.ModelEvent(
        context,
        snapshot.assignments().getOrDefault(context.machine(), ""));
    notifyHandlers(snapshot.model(), context.machine(), listener -> listener.select(event));
    var selected = Optional.ofNullable(snapshot.models().get(event.getModelName())).filter(model -> {
      var resources = Minecraft.getInstance().getResourceManager();
      return resources.getResource(model.geometry()).isPresent()
          && resources.getResource(model.texture()).isPresent()
          && resources.getResource(model.animation()).isPresent();
    });
    SELECTIONS.put(entity, new Selection(context.tick(), selected));
    boolean changed = selected.isPresent() ? PUBLISHED_MODELS.add(entity) : PUBLISHED_MODELS.remove(entity);
    if (changed) {
      requestModelUpdate(entity);
    }
    if (selected.isEmpty()) {
      modelRendered(entity, false);
    }
    return selected;
  }

  public static ControllerClientEvents.AnimationEvent animation(BlockEntity entity, String modelName) {
    var context = context(entity, "{}");
    var event = new ControllerClientEvents.AnimationEvent(context, modelName);
    notifyHandlers(snapshot.animation(), context.machine(), listener -> listener.animate(event));
    return event;
  }

  public static void modelRendered(BlockEntity entity, boolean rendered) {
    requireClientThread();
    long position = entity.getBlockPos().asLong();
    var level = Minecraft.getInstance().level;
    if (rendered
        && (level == null
            || entity.getLevel() != level
            || entity.isRemoved()
            || !level.hasChunkAt(entity.getBlockPos())
            || level.getBlockEntity(entity.getBlockPos()) != entity)) {
      rendered = false;
    }
    boolean changed = rendered ? ACTIVE_MODELS.put(position, entity) != entity : ACTIVE_MODELS.remove(position, entity);
    if (changed) {
      requestModelUpdate(entity);
    }
  }

  private static void requestModelUpdate(BlockEntity entity) {
    var level = Minecraft.getInstance().level;
    if (level != null && entity.getLevel() == level && level.hasChunkAt(entity.getBlockPos())) {
      var current = level.getBlockEntity(entity.getBlockPos());
      if (current != null) {
        current.requestModelDataUpdate();
      }
      var state = level.getBlockState(entity.getBlockPos());
      level.sendBlockUpdated(entity.getBlockPos(), state, state, 8);
    }
  }

  public static boolean usesModel(BlockPos position) {
    return ACTIVE_MODELS.containsKey(position.asLong());
  }

  /**
   * Section compilation may call this on a worker. Only the identity is queued;
   * the client tick validates it before consulting the world or any script. Later
   * compilations must recheck selection after controller state changes.
   */
  public static boolean hasPublishedModel(BlockEntity entity) {
    var discovery = MODEL_DISCOVERY;
    if (discovery.pending.add(entity)) {
      discovery.queue.add(entity);
    }
    return PUBLISHED_MODELS.contains(entity);
  }

  public static boolean shouldRenderModel(BlockEntity entity, Vec3 cameraPosition) {
    requireClientThread();
    boolean visible = inModelRange(entity, cameraPosition);
    if (!visible) {
      modelRendered(entity, false);
    }
    return visible;
  }

  private static boolean inModelRange(BlockEntity entity, Vec3 cameraPosition) {
    return Vec3.atCenterOf(entity.getBlockPos()).closerThan(cameraPosition, MODEL_VIEW_DISTANCE);
  }

  private static void requireClientThread() {
    if (!Minecraft.getInstance().isSameThread()) {
      throw new IllegalStateException("Controller visual callbacks require the client thread");
    }
  }

  public static void drawGui(BlockEntity entity, String customData, GuiGraphics graphics, Font font, int x, int y, int width, int bottom) {
    ensureWorld();
    var context = context(entity, customData);
    var event = new ControllerClientEvents.GuiEvent(context);
    notifyHandlers(snapshot.gui(), context.machine(), listener -> listener.render(event));
    for (String text : event.getExtraInfo()) {
      for (var line : font.split(Component.literal(text), width)) {
        if (y + font.lineHeight > bottom) {
          return;
        }
        graphics.drawString(font, line, x, y, 0xFFFFFF, true);
        y += 10;
      }
    }
  }
}
