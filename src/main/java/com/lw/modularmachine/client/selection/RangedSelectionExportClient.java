package com.lw.modularmachine.client.selection;

import com.google.gson.Gson;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import com.lw.modularmachine.network.RangedSelectionExportPayloads;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Client-thread upload ownership; only detached JSON encoding runs on a worker.
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = "mmcece", value = Dist.CLIENT)
public final class RangedSelectionExportClient {
  private static final Gson JSON = new Gson();
  private static final long TIMEOUT_NANOS = 120_000_000_000L;
  private static Upload current;

  public record Saved(String name, String path, Component message) {
  }

  static final class Failure extends RuntimeException {
    private final Component message;

    Failure(Component message) {
      super(message.getString());
      this.message = message;
    }

    Component message() {
      return message;
    }
  }

  private static final class Upload {
    final UUID id = UUID.randomUUID();
    final ClientPacketListener connection;
    final ResourceLocation dimension;
    final CompletableFuture<Saved> result = new CompletableFuture<>();
    long lastActivity = System.nanoTime();
    byte[] bytes;
    int expectedOffset;
    boolean begun;

    Upload(ClientPacketListener connection, ResourceLocation dimension) {
      this.connection = connection;
      this.dimension = dimension;
    }
  }

  private RangedSelectionExportClient() {
  }

  public static CompletableFuture<Saved> save(RangedSelectionExportSnapshot snapshot, Design design, String name) {
    var client = Minecraft.getInstance();
    if (current != null) {
      return CompletableFuture.failedFuture(new Failure(RangedSelectionClient.text("export_busy")));
    }
    if (client.getConnection() == null || client.level == null) {
      return CompletableFuture.failedFuture(new Failure(RangedSelectionClient.text("export_disconnected")));
    }
    var upload = new Upload(client.getConnection(), client.level.dimension().location());
    current = upload;
    CompletableFuture.supplyAsync(() -> encode(snapshot, design, name), Util.ioPool())
        .whenComplete((bytes, failure) -> client.execute(() -> {
          if (current != upload) {
            return;
          }
          if (failure != null) {
            fail(upload, failure, false);
            return;
          }
          if (!connected(upload)) {
            fail(upload, new Failure(RangedSelectionClient.text("export_disconnected")), false);
            return;
          }
          upload.bytes = bytes;
          upload.lastActivity = System.nanoTime();
          try {
            RangedSelectionExportPayloads.begin(
                new RangedSelectionExportPayloads.Begin(
                    upload.id,
                    upload.dimension,
                    name,
                    design.append(),
                    design.orientation(),
                    bytes.length));
            upload.begun = true;
          } catch (RuntimeException invalid) {
            fail(upload, invalid, false);
          }
        }));

    return upload.result;
  }

  private static byte[] encode(RangedSelectionExportSnapshot snapshot, Design design, String name) {
    byte[] bytes = JSON.toJson(snapshot.submission(design, name)).getBytes(StandardCharsets.UTF_8);
    if (bytes.length > RangedSelectionExportPayloads.MAX_BYTES) {
      throw new IllegalArgumentException("The exported preview exceeds the machine definition size limit");
    }

    return bytes;
  }

  public static void receive(RangedSelectionExportPayloads.Reply reply) {
    var upload = current;
    if (upload == null || !upload.id.equals(reply.request()) || !connected(upload)) {
      return;
    }
    upload.lastActivity = System.nanoTime();
    switch (reply.state()) {
      case UPLOAD -> sendChunk(upload, reply.nextOffset());
      case SAVED -> {
        current = null;
        upload.bytes = null;
        upload.result.complete(new Saved(reply.name(), reply.path(), reply.message()));
      }
      case FAILED -> fail(upload, new Failure(reply.message()), false);
    }
  }

  private static void sendChunk(Upload upload, int offset) {
    if (upload.bytes == null || offset != upload.expectedOffset || offset >= upload.bytes.length) {
      fail(upload, new Failure(RangedSelectionClient.text("export_protocol")), true);
      return;
    }
    int end = Math.min(upload.bytes.length, offset + RangedSelectionExportPayloads.CHUNK_BYTES);
    byte[] chunk = Arrays.copyOfRange(upload.bytes, offset, end);
    upload.expectedOffset = end;
    try {
      RangedSelectionExportPayloads.chunk(new RangedSelectionExportPayloads.Chunk(upload.id, offset, chunk));
    } catch (RuntimeException invalid) {
      fail(upload, invalid, true);
    }
  }

  private static boolean connected(Upload upload) {
    var client = Minecraft.getInstance();

    return client.getConnection() == upload.connection
        && client.level != null
        && upload.dimension.equals(client.level.dimension().location());
  }

  private static void fail(Upload upload, Throwable failure, boolean cancel) {
    if (current != upload) {
      return;
    }
    current = null;
    upload.bytes = null;
    if (cancel && upload.begun && Minecraft.getInstance().getConnection() == upload.connection) {
      try {
        RangedSelectionExportPayloads.cancel(upload.id);
      } catch (RuntimeException disconnected) {
        // Connection shutdown must still release the local upload and its future.
      }
    }
    upload.result.completeExceptionally(
        failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure);
  }

  @SubscribeEvent
  public static void tick(ClientTickEvent.Post event) {
    var upload = current;
    if (upload == null) {
      return;
    }
    if (!connected(upload)) {
      fail(upload, new Failure(RangedSelectionClient.text("export_disconnected")), true);
    } else if (System.nanoTime() - upload.lastActivity >= TIMEOUT_NANOS) {
      fail(upload, new Failure(RangedSelectionClient.text("export_timeout")), true);
    }
  }

  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
    if (current != null) {
      fail(current, new Failure(RangedSelectionClient.text("export_disconnected")), true);
    }
  }
}
