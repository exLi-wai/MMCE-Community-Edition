package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.selection.RangedSelectionExportService;
import com.lw.modularmachine.machine.structure.MachineDefinitionFiles;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal bounded transfer of a preview export to its server-owned
 * destination.
 */
@ApiStatus.Internal
public final class RangedSelectionExportPayloads {
  public static final int CHUNK_BYTES = 24 * 1024;
  public static final int MAX_BYTES = (int) MachineDefinitionFiles.MAX_FILE_BYTES;
  private static final int MAX_NAME = 104;
  private static final int MAX_PATH = 4096;
  private static final CustomPacketPayload.Type<Begin> BEGIN = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "ranged_selection_export_begin"));
  private static final CustomPacketPayload.Type<Chunk> CHUNK = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "ranged_selection_export_chunk"));
  private static final CustomPacketPayload.Type<Cancel> CANCEL = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "ranged_selection_export_cancel"));
  private static final CustomPacketPayload.Type<Reply> REPLY = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "ranged_selection_export_reply"));
  private static Consumer<Reply> receiver = ignored -> {
  };

  private RangedSelectionExportPayloads() {
  }

  public record Begin(
      UUID request,
      ResourceLocation dimension,
      String name,
      boolean append,
      MachineOrientation orientation,
      int totalBytes) implements CustomPacketPayload {
    public Begin {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(orientation, "orientation");
      if (name.length() > MAX_NAME || totalBytes <= 0 || totalBytes > MAX_BYTES) {
        throw new IllegalArgumentException("Invalid ranged selection export header");
      }
    }

    @Override
    public Type<Begin> type() {
      return BEGIN;
    }
  }

  public record Chunk(UUID request, int offset, byte[] bytes) implements CustomPacketPayload {
    public Chunk {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(bytes, "bytes");
      if (offset < 0
          || offset >= MAX_BYTES
          || bytes.length == 0
          || bytes.length > CHUNK_BYTES
          || bytes.length > MAX_BYTES - offset) {
        throw new IllegalArgumentException("Invalid ranged selection export chunk");
      }
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public Type<Chunk> type() {
      return CHUNK;
    }
  }

  public record Cancel(UUID request) implements CustomPacketPayload {
    public Cancel {
      Objects.requireNonNull(request, "request");
    }

    @Override
    public Type<Cancel> type() {
      return CANCEL;
    }
  }

  public enum State {
    UPLOAD,
    SAVED,
    FAILED
  }

  public record Reply(UUID request, State state, int nextOffset, String name, String path, Component message)
      implements
        CustomPacketPayload {
    public Reply {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(message, "message");
      if (nextOffset < 0
          || nextOffset > MAX_BYTES
          || name.length() > MAX_NAME
          || path.length() > MAX_PATH
          || message.getString().length() > MAX_PATH) {
        throw new IllegalArgumentException("Invalid ranged selection export reply");
      }
    }

    @Override
    public Type<Reply> type() {
      return REPLY;
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");
    registrar.playToServer(
        BEGIN,
        StreamCodec.of(RangedSelectionExportPayloads::writeBegin, RangedSelectionExportPayloads::readBegin),
        (payload, context) -> context.enqueueWork(() -> {
          if (context.player() instanceof ServerPlayer player) {
            RangedSelectionExportService.begin(player, payload);
          }
        }));
    registrar.playToServer(
        CHUNK,
        StreamCodec.of(RangedSelectionExportPayloads::writeChunk, RangedSelectionExportPayloads::readChunk),
        (payload, context) -> context.enqueueWork(() -> {
          if (context.player() instanceof ServerPlayer player) {
            RangedSelectionExportService.chunk(player, payload);
          }
        }));
    registrar.playToServer(
        CANCEL,
        StreamCodec
            .of((buffer, payload) -> buffer.writeUUID(payload.request()), buffer -> new Cancel(buffer.readUUID())),
        (payload, context) -> context.enqueueWork(() -> {
          if (context.player() instanceof ServerPlayer player) {
            RangedSelectionExportService.cancel(player, payload.request());
          }
        }));
    registrar.playToClient(
        REPLY,
        StreamCodec.of(RangedSelectionExportPayloads::writeReply, RangedSelectionExportPayloads::readReply),
        (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)));
  }

  public static void setReceiver(Consumer<Reply> value) {
    receiver = Objects.requireNonNull(value, "receiver");
  }

  public static void begin(Begin value) {
    PacketDistributor.sendToServer(value);
  }

  public static void chunk(Chunk value) {
    PacketDistributor.sendToServer(value);
  }

  public static void cancel(UUID request) {
    PacketDistributor.sendToServer(new Cancel(request));
  }

  public static void reply(ServerPlayer player, Reply value) {
    PacketDistributor.sendToPlayer(player, value);
  }

  private static void writeBegin(RegistryFriendlyByteBuf buffer, Begin value) {
    buffer.writeUUID(value.request());
    buffer.writeResourceLocation(value.dimension());
    buffer.writeUtf(value.name(), MAX_NAME);
    buffer.writeBoolean(value.append());
    buffer.writeEnum(value.orientation().front());
    buffer.writeByte(value.orientation().roll());
    buffer.writeVarInt(value.totalBytes());
  }

  private static Begin readBegin(RegistryFriendlyByteBuf buffer) {
    return new Begin(
        buffer.readUUID(),
        buffer.readResourceLocation(),
        buffer.readUtf(MAX_NAME),
        buffer.readBoolean(),
        MachineOrientation.of(buffer.readEnum(Direction.class), buffer.readUnsignedByte()),
        buffer.readVarInt());
  }

  private static void writeChunk(RegistryFriendlyByteBuf buffer, Chunk value) {
    buffer.writeUUID(value.request());
    buffer.writeVarInt(value.offset());
    buffer.writeByteArray(value.bytes());
  }

  private static Chunk readChunk(RegistryFriendlyByteBuf buffer) {
    return new Chunk(buffer.readUUID(), buffer.readVarInt(), buffer.readByteArray(CHUNK_BYTES));
  }

  private static void writeReply(RegistryFriendlyByteBuf buffer, Reply value) {
    buffer.writeUUID(value.request());
    buffer.writeEnum(value.state());
    buffer.writeVarInt(value.nextOffset());
    buffer.writeUtf(value.name(), MAX_NAME);
    buffer.writeUtf(value.path(), MAX_PATH);
    ComponentSerialization.STREAM_CODEC.encode(buffer, value.message());
  }

  private static Reply readReply(RegistryFriendlyByteBuf buffer) {
    return new Reply(
        buffer.readUUID(),
        buffer.readEnum(State.class),
        buffer.readVarInt(),
        buffer.readUtf(MAX_NAME),
        buffer.readUtf(MAX_PATH),
        ComponentSerialization.STREAM_CODEC.decode(buffer));
  }
}
