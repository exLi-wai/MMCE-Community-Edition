package com.lw.modularmachine.machine.selection;

import com.lw.modularmachine.item.RangedConstructToolItem;
import com.lw.modularmachine.machine.structure.MachineDefinitionConfig;
import com.lw.modularmachine.machine.structure.MachineDefinitionFiles;
import com.lw.modularmachine.network.RangedSelectionExportPayloads;
import com.lw.modularmachine.network.RangedSelectionExportPayloads.Begin;
import com.lw.modularmachine.network.RangedSelectionExportPayloads.Chunk;
import com.lw.modularmachine.network.RangedSelectionExportPayloads.Reply;
import com.lw.modularmachine.network.RangedSelectionExportPayloads.State;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server-thread ownership of bounded uploads. Workers only read detached JSON,
 * file snapshots and the cancellation flag; registry capture runs on the
 * server.
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = "mmcece")
public final class RangedSelectionExportService {
  private static final int MAX_SESSIONS = 4;
  private static final long MAX_RESERVED_BYTES = 128L * 1024 * 1024;
  private static final long IDLE_NANOS = 120_000_000_000L;
  private static final Map<UUID, Session> SESSIONS = new HashMap<>();

  private enum Stage {
    PREPARING,
    UPLOADING,
    SAVING
  }

  private record Upload(byte[] bytes, RangedSelectionExportFile.Target target) {
  }

  private static final class Session {
    final ServerPlayer player;
    final Begin request;
    final Path directory;
    final String name;
    final AtomicBoolean active = new AtomicBoolean(true);
    Stage stage = Stage.PREPARING;
    CompletableFuture<?> work;
    Upload upload;
    int offset;
    long lastUsed = System.nanoTime();

    Session(ServerPlayer player, Begin request, Path directory, String name) {
      this.player = player;
      this.request = request;
      this.directory = directory;
      this.name = name;
    }

    void requireActive() {
      if (!active.get() || Thread.currentThread().isInterrupted()) {
        throw new CancellationException("Export cancelled");
      }
    }
  }

  private RangedSelectionExportService() {
  }

  public static void begin(ServerPlayer player, Begin request) {
    if (!authorized(player) || !player.serverLevel().dimension().location().equals(request.dimension())) {
      failed(player, request.request(), Component.translatable("gui.mmcece.selection.export_permission"));
      return;
    }
    long reserved = SESSIONS.values().stream().mapToLong(session -> session.request.totalBytes()).sum();
    if (SESSIONS.containsKey(player.getUUID())
        || SESSIONS.size() >= MAX_SESSIONS
        || reserved + request.totalBytes() > MAX_RESERVED_BYTES) {
      failed(player, request.request(), Component.translatable("gui.mmcece.selection.export_busy"));
      return;
    }
    try {
      // The header already contains a normalized stem. Preserve a literal .json
      // suffix in that stem while reusing the full filename validation.
      String name = request.append() ? RangedSelectionExportFile
          .targetName(request.name() + ".json") : RangedSelectionExportFile.baseName(request.name() + ".json");
      var session = new Session(player, request, MachineDefinitionConfig.directory(), name);
      session.work = CompletableFuture.supplyAsync(() -> prepareUpload(session), Util.ioPool());
      SESSIONS.put(player.getUUID(), session);
    } catch (RuntimeException failure) {
      failed(player, request.request(), failureMessage(failure));
    }
  }

  private static Upload prepareUpload(Session session) {
    session.requireActive();
    try {
      var target = session.request.append() ? RangedSelectionExportFile.capture(session.directory, session.name) : null;
      var bytes = new byte[session.request.totalBytes()];
      session.requireActive();
      return new Upload(bytes, target);
    } catch (IOException failure) {
      throw new CompletionException(failure);
    }
  }

  public static void chunk(ServerPlayer player, Chunk chunk) {
    var session = SESSIONS.get(player.getUUID());
    if (session == null || !session.request.request().equals(chunk.request())) {
      return;
    }
    if (!current(session)) {
      reject(session, Component.translatable("gui.mmcece.selection.export_permission"));
      return;
    }
    byte[] bytes = chunk.bytes();
    if (session.stage != Stage.UPLOADING
        || chunk.offset() != session.offset
        || bytes.length > session.request.totalBytes() - session.offset) {
      reject(session, Component.translatable("gui.mmcece.selection.export_protocol"));
      return;
    }
    System.arraycopy(bytes, 0, session.upload.bytes(), session.offset, bytes.length);
    session.offset += bytes.length;
    session.lastUsed = System.nanoTime();
    if (session.offset < session.request.totalBytes()) {
      acknowledge(session);
      return;
    }
    session.stage = Stage.SAVING;
    var server = player.server;
    var upload = session.upload;
    session.upload = null;
    session.work = CompletableFuture.supplyAsync(() -> {
      session.requireActive();
      return MachineDefinitionFiles.parse(upload.bytes(), "selection upload");
    }, Util.ioPool()).thenCompose(root -> {
      session.requireActive();
      if (upload.target() != null) {
        return RangedSelectionExportFile
            .merge(upload.target(), root, session.request.orientation(), server, Util.ioPool(), session.active::get);
      }
      return CompletableFuture.supplyAsync(() -> {
        session.requireActive();
        try {
          return RangedSelectionExportFile
              .save(session.directory.resolve("mmcece"), session.name, root, session.active::get);
        } catch (IOException failure) {
          throw new CompletionException(failure);
        }
      }, Util.ioPool());
    });
  }

  public static void cancel(ServerPlayer player, UUID request) {
    var session = SESSIONS.get(player.getUUID());
    if (session != null && session.request.request().equals(request)) {
      session.active.set(false);
      if (session.stage == Stage.UPLOADING) {
        SESSIONS.remove(player.getUUID(), session);
      }
    }
  }

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post event) {
    var iterator = SESSIONS.values().iterator();
    while (iterator.hasNext()) {
      var session = iterator.next();
      if (session.active.get() && !current(session)) {
        reject(session, Component.translatable("gui.mmcece.selection.export_permission"));
      }
      if (session.active.get() && System.nanoTime() - session.lastUsed > IDLE_NANOS) {
        reject(session, Component.translatable("gui.mmcece.selection.export_timeout"));
      }
      if (session.stage == Stage.UPLOADING) {
        if (!session.active.get()) {
          iterator.remove();
        }
        continue;
      }
      if (!session.work.isDone()) {
        continue;
      }
      if (!session.active.get()) {
        iterator.remove();
        continue;
      }
      try {
        if (session.stage == Stage.PREPARING) {
          session.upload = (Upload) session.work.join();
          session.work = null;
          session.stage = Stage.UPLOADING;
          session.lastUsed = System.nanoTime();
          acknowledge(session);
        } else {
          var saved = (RangedSelectionExportFile.Saved) session.work.join();
          String path = saved.file().toString();
          String key = session.request.append() ? "append_saved" : "export_saved";
          RangedSelectionExportPayloads.reply(
              session.player,
              new Reply(
                  session.request.request(),
                  State.SAVED,
                  0,
                  saved.name(),
                  path,
                  Component.translatable("gui.mmcece.selection." + key, path)));
          iterator.remove();
        }
      } catch (RuntimeException failure) {
        reject(session, failureMessage(failure));
        iterator.remove();
      }
    }
  }

  private static void acknowledge(Session session) {
    RangedSelectionExportPayloads.reply(
        session.player,
        new Reply(session.request.request(), State.UPLOAD, session.offset, "", "", Component.empty()));
  }

  private static boolean authorized(ServerPlayer player) {
    return !player.hasDisconnected()
        && (player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile()))
        && player.getMainHandItem().getItem() instanceof RangedConstructToolItem;
  }

  private static boolean current(Session session) {
    return authorized(session.player)
        && session.player.server.getPlayerList().getPlayer(session.player.getUUID()) == session.player
        && session.player.serverLevel().dimension().location().equals(session.request.dimension());
  }

  private static void reject(Session session, Component message) {
    if (session.active.getAndSet(false) && !session.player.hasDisconnected()) {
      failed(session.player, session.request.request(), message);
    }
  }

  private static void failed(ServerPlayer player, UUID request, Component message) {
    RangedSelectionExportPayloads.reply(player, new Reply(request, State.FAILED, 0, "", "", message));
  }

  private static Component failureMessage(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null) {
      failure = failure.getCause();
    }
    String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    return Component
        .translatable("gui.mmcece.selection.export_failed", detail.substring(0, Math.min(512, detail.length())));
  }

  @SubscribeEvent
  public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
    var session = SESSIONS.get(event.getEntity().getUUID());
    if (session != null) {
      session.active.set(false);
    }
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    SESSIONS.values().forEach(session -> session.active.set(false));
    SESSIONS.clear();
  }
}
