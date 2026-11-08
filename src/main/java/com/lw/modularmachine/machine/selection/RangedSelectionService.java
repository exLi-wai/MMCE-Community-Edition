package com.lw.modularmachine.machine.selection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Action;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Box;
import com.lw.modularmachine.machine.selection.RangedSelectionData.PickTarget;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Request;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Segment;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Settings;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Snapshot;
import com.lw.modularmachine.machine.structure.AsyncStructureWork;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.network.RangedSelectionPayloads;
import com.lw.modularmachine.registry.MachineDataComponents;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal server-owned editor, interactions and fair task scheduling for the
 * existing ranged tool.
 */
@ApiStatus.Internal
public final class RangedSelectionService {
  private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();
  private static final long IDLE_NANOS = 600_000_000_000L;
  private static int playerCursor;

  private static final class Session {
    final UUID id = UUID.randomUUID();
    final ResourceLocation dimension;
    final ArrayList<Segment> segments = new ArrayList<>();
    long revision;
    long lastUsed = System.nanoTime();
    Box main;
    int selected;
    PickTarget picking = PickTarget.NONE;
    BlockPos first;
    boolean append;
    boolean deduplicate = true;
    boolean includeAir;
    String fileToken = "";
    String fileLabel = "";
    String fileName = "";
    String filter = "";
    int filePage;
    MachineDefinitionFileStore.Catalog catalog;
    CompletableFuture<MachineDefinitionFileStore.Catalog> catalogFuture;
    Component status = message("ready");
    RangedSelectionTask task;
    DynamicDefinitionEditor.Result result;
    MachineDefinitionFileStore.SaveResult saved;
    RangedSelectionPreview preview;
    List<ProjectionPayloads.Entry> previewEntries;
    int previewCursor;
    long previewRequest;

    Session(ResourceLocation dimension) {
      this.dimension = dimension;
      segments.add(new Segment(Settings.defaults(), null, null, null));
    }

    boolean busy() {
      return task != null || catalogFuture != null || preview != null || previewEntries != null;
    }

    Snapshot snapshot(boolean open, boolean close) {
      return new Snapshot(
          id,
          revision,
          dimension,
          open,
          close,
          append,
          fileToken,
          fileLabel,
          fileName,
          deduplicate,
          includeAir,
          main,
          segments,
          selected,
          picking,
          first,
          status,
          busy(),
          catalog == null ? List.of() : catalog.page(filter, filePage),
          filePage,
          catalog == null ? 1 : catalog.pages(filter),
          result != null && saved != null);
    }

    void cancelWork() {
      if (task != null) {
        task.abandon();
        task = null;
      }
      if (catalogFuture != null) {
        catalogFuture.cancel(false);
        catalogFuture = null;
      }
      cancelPreview();
    }

    void cancelPreview() {
      if (preview != null) {
        preview.cancel();
        preview = null;
      }
      previewEntries = null;
      previewCursor = 0;
    }

    void changed() {
      result = null;
      saved = null;
      cancelPreview();
      status = message("updated");
    }
  }

  private RangedSelectionService() {
  }

  public static BlockPos min(BlockPos first, BlockPos second) {
    return new BlockPos(
        Math.min(first.getX(), second.getX()),
        Math.min(first.getY(), second.getY()),
        Math.min(first.getZ(), second.getZ()));
  }

  public static BlockPos max(BlockPos first, BlockPos second) {
    return new BlockPos(
        Math.max(first.getX(), second.getX()),
        Math.max(first.getY(), second.getY()),
        Math.max(first.getZ(), second.getZ()));
  }

  /**
   * Called only during the server-thread capture; no inventory or block entity
   * data is exported.
   */
  public static JsonObject exportPart(BlockState state, MachineOrientation orientation, BlockPos localOffset) {
    var part = new JsonObject();
    var xyz = new JsonArray();
    xyz.add(localOffset.getX());
    xyz.add(localOffset.getY());
    xyz.add(localOffset.getZ());
    part.add("offset", xyz);
    if (state.getBlock() instanceof MachinePortBlock port) {
      part.addProperty("block", port.kind().name().toLowerCase(Locale.ROOT));
    } else {
      part.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
      if (!(state.getBlock() instanceof LiquidBlock)) {
        var properties = stateProperties(state, orientation, true);
        String space = "local";
        if (properties == null) {
          properties = stateProperties(state, orientation, false);
          space = "world";
        }
        if (!properties.isEmpty()) {
          part.add("properties", properties);
          part.addProperty("properties_space", space);
        }
      }
    }
    return part;
  }

  public static void open(ServerPlayer player) {
    if (!authorized(player)) {
      return;
    }
    var session = session(player);
    session.lastUsed = System.nanoTime();
    if (session.picking != PickTarget.NONE) {
      session.picking = PickTarget.NONE;
      session.first = null;
      session.revision++;
      session.status = message("cancelled");
    }
    send(player, session, true, false);
  }

  public static InteractionResult interact(ServerPlayer player, BlockPos position) {
    if (!authorized(player)) {
      return InteractionResult.FAIL;
    }
    var level = player.serverLevel();
    if (!level.hasChunkAt(position)
        || !level.getWorldBorder().isWithinBounds(position)
        || !player.canInteractWithBlock(position, 0)
        || !level.mayInteract(player, position)) {
      return InteractionResult.FAIL;
    }
    var session = session(player);
    session.lastUsed = System.nanoTime();
    if (player.isSecondaryUseActive()) {
      open(player);
      return InteractionResult.SUCCESS;
    }
    if (session.busy()) {
      send(player, session, false, false);
      return InteractionResult.SUCCESS;
    }
    if (session.picking != PickTarget.NONE) {
      if (session.first == null) {
        session.first = position.immutable();
        session.revision++;
        session.status = message("first", position.toShortString());
      } else {
        try {
          var box = new Box(session.first, position);
          if (box.volume() > RangedSelectionData.MAX_CAPTURE) {
            throw new IllegalArgumentException("Selection exceeds 262144 positions");
          }
          boolean changed = !box.equals(box(session, session.picking));
          setBox(session, session.picking, box);
          session.picking = PickTarget.NONE;
          session.first = null;
          session.revision++;
          if (changed) {
            session.changed();
          }
          session.status = message("ready");
          syncMain(player, session);
        } catch (IllegalArgumentException | ArithmeticException failure) {
          session.status = message("invalid", detail(failure));
        }
      }
      player.displayClientMessage(session.status, true);
      send(player, session, false, false);
      return InteractionResult.SUCCESS;
    }
    if (level.getBlockEntity(position) instanceof StructureService.StructureSubject controller) {
      submit(player, session, controller);
    } else {
      send(player, session, true, false);
    }
    return InteractionResult.SUCCESS;
  }

  public static void handle(ServerPlayer player, Request request) {
    if (!authorized(player)) {
      return;
    }
    if (request.action() == Action.OPEN) {
      open(player);
      return;
    }
    var session = session(player);
    session.lastUsed = System.nanoTime();
    if (!request.session().equals(session.id) || request.revision() != session.revision) {
      session.status = message("stale");
      send(player, session, true, false);
      return;
    }
    if (session.busy()) {
      send(player, session, false, false);
      return;
    }
    try {
      boolean close = false;
      switch (request.action()) {
        case BEGIN_PICK -> {
          requireTarget(session, request);
          session.picking = request.target();
          session.first = null;
          session.status = message("picking");
          session.revision++;
          close = true;
        }
        case CANCEL_PICK -> {
          session.picking = PickTarget.NONE;
          session.first = null;
          session.status = message("cancelled");
          session.revision++;
        }
        case CLEAR_BOX -> {
          requireTarget(session, request);
          boolean changed = box(session, request.target()) != null;
          setBox(session, request.target(), null);
          if (changed) {
            session.changed();
          }
          session.revision++;
          syncMain(player, session);
        }
        case SET_MODE -> {
          if (request.index() != 0 && request.index() != 1) {
            throw new IllegalArgumentException("Invalid export mode");
          }
          boolean append = request.index() == 1;
          if (session.append != append) {
            session.append = append;
            session.changed();
          }
          session.revision++;
        }
        case LIST_FILES -> {
          session.filter = request.value();
          session.filePage = Math.max(0, request.index());
          if (session.catalog == null || request.index() == 0) {
            session.catalogFuture = AsyncStructureWork.submit(() -> {
              try {
                return MachineDefinitionFileStore.scan();
              } catch (IOException failure) {
                throw new CompletionException(failure);
              }
            });
            if (session.catalogFuture == null) {
              throw new IllegalStateException("The editor worker queue is busy; retry");
            }
          } else {
            session.filePage = Math.min(session.filePage, session.catalog.pages(session.filter) - 1);
          }
        }
        case SELECT_FILE -> {
          if (session.catalog == null) {
            throw new IllegalArgumentException("Open the file list first");
          }
          var entry = session.catalog.entries().stream().filter(value -> value.token().equals(request.value()))
              .findFirst().orElseThrow(() -> new IllegalArgumentException("The selected file token has expired"));
          if (!session.fileToken.equals(entry.token()) || !session.append) {
            session.changed();
          }
          session.fileToken = entry.token();
          session.fileLabel = entry.path().getFileName().toString();
          session.append = true;
          session.revision++;
        }
        case ADD_SEGMENT -> {
          if (session.segments.size() >= RangedSelectionData.MAX_SEGMENTS) {
            throw new IllegalArgumentException("At most 64 dynamic segments");
          }
          session.segments.add(new Segment(Settings.defaults(), null, null, null));
          session.selected = session.segments.size() - 1;
          session.changed();
          session.revision++;
        }
        case SELECT_SEGMENT -> {
          requireSegment(session, request.index());
          session.selected = request.index();
          session.revision++;
        }
        case REMOVE_SEGMENT -> {
          requireSegment(session, request.index());
          session.segments.remove(request.index());
          if (session.segments.isEmpty()) {
            session.segments.add(new Segment(Settings.defaults(), null, null, null));
          }
          session.selected = Math.min(session.selected, session.segments.size() - 1);
          session.changed();
          session.revision++;
        }
        case UPDATE -> {
          requireSegment(session, request.index());
          if (request.index() != session.selected) {
            throw new IllegalArgumentException("The selected segment changed");
          }
          var before = session.segments.get(session.selected);
          if (!before.settings().equals(request.settings())
              || session.deduplicate != request.deduplicate()
              || session.includeAir != request.includeAir()
              || !session.fileName.equals(request.value())) {
            session.changed();
          }
          session.segments
              .set(session.selected, new Segment(request.settings(), before.body(), before.start(), before.end()));
          session.deduplicate = request.deduplicate();
          session.includeAir = request.includeAir();
          session.fileName = request.value();
          session.revision++;
        }
        case CLEAR -> {
          session.main = null;
          session.segments.clear();
          session.segments.add(new Segment(Settings.defaults(), null, null, null));
          session.selected = 0;
          session.picking = PickTarget.NONE;
          session.first = null;
          session.changed();
          session.revision++;
          syncMain(player, session);
          session.status = message("cleared");
        }
        case PREVIEW -> {
          if (session.result == null || session.saved == null) {
            throw new IllegalArgumentException("Export a machine before opening its result preview");
          }
          session.cancelPreview();
          session.previewRequest++;
          session.preview = new RangedSelectionPreview(
              session.result,
              session.selected,
              request.index(),
              StructureService.definitionEpoch());
        }
        case OPEN -> {
        }
      }
      send(player, session, !close, close);
    } catch (RuntimeException failure) {
      session.status = message("invalid", detail(failure));
      if (request.action() == Action.PREVIEW) {
        failPreview(player, session);
      }
      send(player, session, true, false);
    }
  }

  private static void submit(ServerPlayer player, Session session, StructureService.StructureSubject controller) {
    if (session.saved != null && session.result != null) {
      session.status = savedMessage(session);
      player.displayClientMessage(session.status, false);
      send(player, session, false, false);
      return;
    }
    String missing = null;
    if (session.append && (session.catalog == null || session.fileToken.isEmpty())) {
      missing = "missing_file";
    } else if (!session.append && session.main == null) {
      missing = "missing_main";
    } else if (session.append && session.segments.stream().noneMatch(value -> value.body() != null)) {
      missing = "missing_body";
    }
    if (missing != null) {
      session.status = message(missing);
      send(player, session, true, false);
      return;
    }
    try {
      session.task = new RangedSelectionTask(
          player,
          controller,
          session.snapshot(false, false),
          session.append ? session.catalog : null);
      session.status = message("busy");
      send(player, session, false, false);
    } catch (RuntimeException failure) {
      session.status = message("invalid", detail(failure));
      send(player, session, true, false);
    }
  }

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post event) {
    var ids = new ArrayList<>(SESSIONS.keySet());
    if (ids.isEmpty()) {
      return;
    }
    int start = Math.floorMod(playerCursor, ids.size());
    long deadline = System.nanoTime() + 2_000_000L;
    int packets = 0;
    int processed = 0;
    for (; processed < ids.size(); processed++) {
      var id = ids.get((start + processed) % ids.size());
      var session = SESSIONS.get(id);
      var player = event.getServer().getPlayerList().getPlayer(id);
      if (player == null
          || !session.dimension.equals(player.level().dimension().location())
          || System.nanoTime() - session.lastUsed > IDLE_NANOS) {
        session.cancelWork();
        SESSIONS.remove(id);
        continue;
      }
      if (!(player.getMainHandItem().getItem() instanceof RangedConstructToolItem) || !player.hasPermissions(2)) {
        if (session.task != null && session.task.advance(deadline)) {
          finishTask(player, session);
        }
        if (session.catalogFuture != null) {
          session.catalogFuture.cancel(false);
          session.catalogFuture = null;
        }
        session.cancelPreview();
        continue;
      }
      if (session.catalogFuture != null && session.catalogFuture.isDone()) {
        finishCatalog(player, session);
      }
      if (session.task != null && System.nanoTime() < deadline) {
        try {
          if (session.task.advance(deadline)) {
            finishTask(player, session);
          }
        } catch (RuntimeException failure) {
          session.task.cancel();
          session.task = null;
          session.status = message("failed", detail(failure));
          send(player, session, false, false);
          player.displayClientMessage(session.status, false);
        }
      }
      if (session.preview != null && System.nanoTime() < deadline) {
        try {
          var entries = session.preview.advance(deadline);
          if (entries != null) {
            session.previewEntries = entries;
            session.preview = null;
            session.previewCursor = 0;
          }
        } catch (RuntimeException failure) {
          session.status = message("failed", detail(failure));
          failPreview(player, session);
          send(player, session, false, false);
        }
      }
      if (session.previewEntries != null && packets < 8) {
        session.previewCursor = RangedSelectionPayloads.sendPreviewChunk(
            player,
            session.id,
            session.revision,
            session.previewRequest,
            session.result.definition().color(),
            session.dimension,
            session.result.definition().id(),
            session.previewEntries,
            session.previewCursor);
        packets++;
        if (session.previewCursor >= session.previewEntries.size()) {
          session.previewEntries = null;
          send(player, session, false, false);
        }
      }
      if (System.nanoTime() >= deadline) {
        processed++;
        break;
      }
    }
    playerCursor = (start + Math.max(1, processed)) % ids.size();
  }

  private static void finishCatalog(ServerPlayer player, Session session) {
    var future = session.catalogFuture;
    session.catalogFuture = null;
    try {
      var catalog = future.join();
      if (!session.fileToken.isEmpty() && session.catalog != null) {
        var old = session.catalog.entries().stream().filter(entry -> entry.token().equals(session.fileToken))
            .findFirst().orElse(null);
        var replacement = old == null ? null : catalog.entries().stream()
            .filter(entry -> entry.path().equals(old.path())).findFirst().orElse(null);
        session.fileToken = replacement == null ? "" : replacement.token();
        session.fileLabel = replacement == null ? "" : replacement.path().getFileName().toString();
      }
      session.catalog = catalog;
      session.filePage = Math.min(session.filePage, catalog.pages(session.filter) - 1);
      session.status = session.saved != null && session.result != null ? savedMessage(session) : message("listed");
    } catch (RuntimeException failure) {
      session.status = message("failed", detail(failure));
    }
    send(player, session, false, false);
  }

  private static void finishTask(ServerPlayer player, Session session) {
    var task = session.task;
    session.task = null;
    session.saved = task.saved();
    session.result = task.result();
    if (session.saved != null && session.result != null) {
      session.status = savedMessage(session);
      player.displayClientMessage(session.status, false);
      player.displayClientMessage(
          session.append ? message("reload", session.saved.machine().toString()) : message("restart"),
          false);
      if (session.append) {
        // Refresh the token/hash baseline after our own successful write so another
        // edited draft
        // can append without being rejected against the pre-save source hash.
        session.catalogFuture = AsyncStructureWork.submit(() -> {
          try {
            return MachineDefinitionFileStore.scan();
          } catch (IOException failure) {
            throw new CompletionException(failure);
          }
        });
      }
    } else {
      session.status = message("failed", task.status());
      player.displayClientMessage(session.status, false);
    }
    send(player, session, false, false);
  }

  private static void failPreview(ServerPlayer player, Session session) {
    session.cancelPreview();
    if (session.result != null) {
      var stopped = new ProjectionPayloads.Snapshot(
          session.previewRequest,
          session.revision,
          session.dimension,
          session.result.definition().id(),
          BlockPos.ZERO,
          -1,
          0,
          List.of());
      PacketDistributor.sendToPlayer(
          player,
          new RangedSelectionPayloads.Preview(
              session.id,
              session.revision,
              session.previewRequest,
              session.result.definition().color(),
              stopped));
    }
  }

  private static void requireTarget(Session session, Request request) {
    if (request.target() == PickTarget.NONE || session.append && request.target() == PickTarget.MAIN) {
      throw new IllegalArgumentException("This selection target is unavailable");
    }
    requireSegment(session, request.index());
    if (request.index() != session.selected) {
      throw new IllegalArgumentException("The selected segment changed");
    }
  }

  private static Component savedMessage(Session session) {
    var message = message(
        "saved_detail",
        session.saved.file().toString(),
        session.result.mainParts(),
        session.result.dynamicParts(),
        session.result.deduplicated());
    message.getSiblings().add(
        Component.literal(
            "\n源 SHA-256: " + session.saved.sourceHash() + "\n目标 SHA-256: " + session.saved
                .targetHash() + (session.saved.backup() == null ? "" : "\n备份: " + session.saved.backup())));
    return message;
  }

  private static Box box(Session session, PickTarget target) {
    var segment = session.segments.get(session.selected);
    return switch (target) {
      case MAIN -> session.main;
      case BODY -> segment.body();
      case START -> segment.start();
      case END -> segment.end();
      case NONE -> null;
    };
  }

  private static void requireSegment(Session session, int index) {
    if (index < 0 || index >= session.segments.size()) {
      throw new IllegalArgumentException("Invalid segment index");
    }
  }

  private static void setBox(Session session, PickTarget target, Box box) {
    if (target == PickTarget.MAIN) {
      session.main = box;
      return;
    }
    var before = session.segments.get(session.selected);
    session.segments.set(
        session.selected,
        new Segment(
            before.settings(),
            target == PickTarget.BODY ? box : before.body(),
            target == PickTarget.START ? box : before.start(),
            target == PickTarget.END ? box : before.end()));
  }

  private static boolean authorized(ServerPlayer player) {
    if (!(player.getMainHandItem().getItem() instanceof RangedConstructToolItem)) {
      return false;
    }
    if (!player.hasPermissions(2)) {
      player.displayClientMessage(message("permission"), true);
      return false;
    }
    return true;
  }

  private static Session session(ServerPlayer player) {
    var existing = SESSIONS.get(player.getUUID());
    var dimension = player.level().dimension().location();
    if (existing != null && existing.dimension.equals(dimension)) {
      return existing;
    }
    if (existing != null) {
      existing.cancelWork();
    }
    var created = new Session(dimension);
    SESSIONS.put(player.getUUID(), created);
    return created;
  }

  private static void syncMain(ServerPlayer player, Session session) {
    var stack = player.getMainHandItem();
    if (session.main == null) {
      stack.remove(MachineDataComponents.SELECTION_FIRST.get());
      stack.remove(MachineDataComponents.SELECTION_SECOND.get());
    } else {
      stack.set(MachineDataComponents.SELECTION_FIRST.get(), session.main.first());
      stack.set(MachineDataComponents.SELECTION_SECOND.get(), session.main.second());
    }
    player.containerMenu.broadcastChanges();
  }

  private static void send(ServerPlayer player, Session session, boolean open, boolean close) {
    RangedSelectionPayloads.send(player, session.snapshot(open, close));
  }

  private static Component message(String key, Object... args) {
    return Component.translatable("message.mmcece.selection." + key, args);
  }

  private static String detail(Throwable failure) {
    while (failure.getCause() != null && failure instanceof CompletionException) {
      failure = failure.getCause();
    }
    String text = failure.getMessage();
    if (text == null || text.isBlank()) {
      text = failure.getClass().getSimpleName();
    }
    return text.length() > 1024 ? text.substring(0, 1024) : text;
  }

  @SubscribeEvent
  public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
    var session = SESSIONS.remove(event.getEntity().getUUID());
    if (session != null) {
      session.cancelWork();
    }
  }

  @SubscribeEvent
  public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
    var session = SESSIONS.remove(event.getEntity().getUUID());
    if (session != null) {
      session.cancelWork();
    }
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    SESSIONS.values().forEach(Session::cancelWork);
    SESSIONS.clear();
    playerCursor = 0;
  }

  private static JsonObject stateProperties(BlockState state, MachineOrientation orientation, boolean local) {
    var result = new JsonObject();
    for (var entry : state.getValues().entrySet()) {
      Property<?> property = entry.getKey();
      Object value = entry.getValue();
      if (local && value instanceof Direction direction) {
        value = orientation.toLocalDirection(direction);
      } else if (local && value instanceof Direction.Axis axis) {
        value = orientation.toLocalAxis(axis);
      }
      String serialized = propertyValue(property, (Comparable<?>) value);
      if (local && property.getValue(serialized).isEmpty()) {
        return null;
      }
      result.addProperty(property.getName(), serialized);
    }
    return result;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String propertyValue(Property<?> property, Comparable<?> value) {
    return ((Property) property).getName(value);
  }
}
