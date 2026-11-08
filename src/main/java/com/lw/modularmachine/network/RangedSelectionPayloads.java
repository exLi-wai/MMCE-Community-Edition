package com.lw.modularmachine.network;

import com.lw.modularmachine.machine.selection.RangedSelectionData;
import com.lw.modularmachine.machine.selection.RangedSelectionData.*;
import com.lw.modularmachine.machine.selection.RangedSelectionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal bounded editor messages; world selection comes from server
 * interactions.
 */
@ApiStatus.Internal
public final class RangedSelectionPayloads {
  private static final CustomPacketPayload.Type<ActionPacket> ACTION = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "ranged_selection_action"));
  private static final CustomPacketPayload.Type<StatePacket> STATE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "ranged_selection_state"));
  private static final CustomPacketPayload.Type<Preview> PREVIEW = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "ranged_selection_preview"));
  private static Consumer<Snapshot> receiver = ignored -> {
  };
  private static Consumer<Preview> previewReceiver = ignored -> {
  };

  private RangedSelectionPayloads() {
  }

  private record ActionPacket(Request request) implements CustomPacketPayload {
    @Override
    public Type<ActionPacket> type() {
      return ACTION;
    }
  }

  private record StatePacket(Snapshot snapshot) implements CustomPacketPayload {
    @Override
    public Type<StatePacket> type() {
      return STATE;
    }
  }

  public record Preview(UUID session, long revision, long request, int color, ProjectionPayloads.Snapshot preview)
      implements
        CustomPacketPayload {
    public Preview {
      Objects.requireNonNull(session);
      Objects.requireNonNull(preview);
      if (revision < 0 || request < 0 || (color & ~0xFFFFFF) != 0 || request != preview.request()) {
        throw new IllegalArgumentException("Invalid ranged selection preview");
      }
    }
    @Override
    public Type<Preview> type() {
      return PREVIEW;
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");
    registrar.playToServer(
        ACTION,
        StreamCodec.of(
            (buffer, packet) -> writeRequest(buffer, packet.request()),
            buffer -> new ActionPacket(readRequest(buffer))),
        (packet, context) -> context.enqueueWork(() -> {
          if (context.player() instanceof ServerPlayer player) {
            RangedSelectionService.handle(player, packet.request());
          }
        }));
    registrar.playToClient(
        STATE,
        StreamCodec.of(
            (buffer, packet) -> writeSnapshot(buffer, packet.snapshot()),
            buffer -> new StatePacket(readSnapshot(buffer))),
        (packet, context) -> context.enqueueWork(() -> receiver.accept(packet.snapshot())));
    registrar.playToClient(PREVIEW, StreamCodec.of((buffer, packet) -> {
      buffer.writeUUID(packet.session());
      buffer.writeVarLong(packet.revision());
      buffer.writeVarLong(packet.request());
      buffer.writeInt(packet.color());
      ProjectionPayloads.write(buffer, packet.preview());
    },
        buffer -> new Preview(
            buffer.readUUID(),
            buffer.readVarLong(),
            buffer.readVarLong(),
            buffer.readInt(),
            ProjectionPayloads.read(buffer))),
        (packet, context) -> context.enqueueWork(() -> previewReceiver.accept(packet)));
  }

  public static void setReceiver(Consumer<Snapshot> value) {
    receiver = Objects.requireNonNull(value);
  }
  public static void setPreviewReceiver(Consumer<Preview> value) {
    previewReceiver = Objects.requireNonNull(value);
  }
  public static void request(Request request) {
    PacketDistributor.sendToServer(new ActionPacket(request));
  }
  public static void send(ServerPlayer player, Snapshot snapshot) {
    PacketDistributor.sendToPlayer(player, new StatePacket(snapshot));
  }

  public static int sendPreviewChunk(ServerPlayer player, UUID session, long revision, long request, int color, ResourceLocation dimension, ResourceLocation machine, List<ProjectionPayloads.Entry> entries, int start) {
    int end = ProjectionPayloads.chunkEnd(entries, start);
    var preview = new ProjectionPayloads.Snapshot(
        request,
        revision,
        dimension,
        machine,
        BlockPos.ZERO,
        start,
        entries.size(),
        entries.subList(start, end));
    PacketDistributor.sendToPlayer(player, new Preview(session, revision, request, color, preview));
    return end;
  }

  private static void writeRequest(RegistryFriendlyByteBuf buffer, Request request) {
    buffer.writeUUID(request.session());
    buffer.writeVarLong(request.revision());
    buffer.writeEnum(request.action());
    buffer.writeEnum(request.target());
    buffer.writeInt(request.index());
    buffer.writeUtf(request.value(), 256);
    writeSettings(buffer, request.settings());
    buffer.writeBoolean(request.deduplicate());
    buffer.writeBoolean(request.includeAir());
  }

  private static Request readRequest(RegistryFriendlyByteBuf buffer) {
    return new Request(
        buffer.readUUID(),
        buffer.readVarLong(),
        buffer.readEnum(Action.class),
        buffer.readEnum(PickTarget.class),
        buffer.readInt(),
        buffer.readUtf(256),
        readSettings(buffer),
        buffer.readBoolean(),
        buffer.readBoolean());
  }

  private static void writeSettings(RegistryFriendlyByteBuf buffer, Settings settings) {
    buffer.writeUtf(settings.id(), 64);
    buffer.writeVarInt(settings.minimum());
    buffer.writeVarInt(settings.maximum());
    buffer.writeEnum(settings.direction());
    buffer.writeVarInt(settings.gap());
    buffer.writeBoolean(settings.customStep());
    writeVector(buffer, settings.step());
    buffer.writeBoolean(settings.customOrigin());
    writeVector(buffer, settings.origin());
    buffer.writeVarInt(settings.sampleLength());
    buffer.writeUtf(settings.selector(), 64);
    buffer.writeUtf(settings.interfaceType(), 64);
  }

  private static Settings readSettings(RegistryFriendlyByteBuf buffer) {
    return new Settings(
        buffer.readUtf(64),
        buffer.readVarInt(),
        buffer.readVarInt(),
        buffer.readEnum(Direction.class),
        buffer.readVarInt(),
        buffer.readBoolean(),
        readVector(buffer),
        buffer.readBoolean(),
        readVector(buffer),
        buffer.readVarInt(),
        buffer.readUtf(64),
        buffer.readUtf(64));
  }

  private static void writeSnapshot(RegistryFriendlyByteBuf buffer, Snapshot snapshot) {
    buffer.writeUUID(snapshot.session());
    buffer.writeVarLong(snapshot.revision());
    buffer.writeResourceLocation(snapshot.dimension());
    buffer.writeBoolean(snapshot.open());
    buffer.writeBoolean(snapshot.close());
    buffer.writeBoolean(snapshot.append());
    buffer.writeUtf(snapshot.fileToken(), 256);
    buffer.writeUtf(snapshot.fileLabel(), 1024);
    buffer.writeUtf(snapshot.fileName(), 256);
    buffer.writeBoolean(snapshot.deduplicate());
    buffer.writeBoolean(snapshot.includeAir());
    writeBox(buffer, snapshot.main());
    buffer.writeVarInt(snapshot.segments().size());
    for (var segment : snapshot.segments()) {
      writeSettings(buffer, segment.settings());
      writeBox(buffer, segment.body());
      writeBox(buffer, segment.start());
      writeBox(buffer, segment.end());
    }
    buffer.writeVarInt(snapshot.selected());
    buffer.writeEnum(snapshot.picking());
    buffer.writeBoolean(snapshot.first() != null);
    if (snapshot.first() != null) {
      writeVector(buffer, snapshot.first());
    }
    ComponentSerialization.STREAM_CODEC.encode(buffer, snapshot.status());
    buffer.writeBoolean(snapshot.busy());
    buffer.writeVarInt(snapshot.files().size());
    for (var file : snapshot.files()) {
      buffer.writeUtf(file.token(), 256);
      buffer.writeResourceLocation(file.machine());
      buffer.writeUtf(file.path(), 1024);
      buffer.writeUtf(file.label(), 512);
    }
    buffer.writeVarInt(snapshot.filePage());
    buffer.writeVarInt(snapshot.filePages());
    buffer.writeBoolean(snapshot.hasResult());
  }

  private static Snapshot readSnapshot(RegistryFriendlyByteBuf buffer) {
    var session = buffer.readUUID();
    long revision = buffer.readVarLong();
    var dimension = buffer.readResourceLocation();
    boolean open = buffer.readBoolean(), close = buffer.readBoolean(), append = buffer.readBoolean();
    String token = buffer.readUtf(256), label = buffer.readUtf(1024), name = buffer.readUtf(256);
    boolean deduplicate = buffer.readBoolean(), air = buffer.readBoolean();
    var main = readBox(buffer);
    int count = count(buffer, RangedSelectionData.MAX_SEGMENTS);
    var segments = new ArrayList<Segment>(count);
    for (int i = 0; i < count; i++) {
      segments.add(new Segment(readSettings(buffer), readBox(buffer), readBox(buffer), readBox(buffer)));
    }
    int selected = buffer.readVarInt();
    var picking = buffer.readEnum(PickTarget.class);
    var first = buffer.readBoolean() ? readVector(buffer) : null;
    var status = ComponentSerialization.STREAM_CODEC.decode(buffer);
    boolean busy = buffer.readBoolean();
    int fileCount = count(buffer, 32);
    var files = new ArrayList<FileOption>(fileCount);
    for (int i = 0; i < fileCount; i++) {
      files.add(
          new FileOption(
              buffer.readUtf(256),
              buffer.readResourceLocation(),
              buffer.readUtf(1024),
              buffer.readUtf(512)));
    }
    return new Snapshot(
        session,
        revision,
        dimension,
        open,
        close,
        append,
        token,
        label,
        name,
        deduplicate,
        air,
        main,
        segments,
        selected,
        picking,
        first,
        status,
        busy,
        files,
        buffer.readVarInt(),
        buffer.readVarInt(),
        buffer.readBoolean());
  }

  private static int count(RegistryFriendlyByteBuf buffer, int maximum) {
    int count = buffer.readVarInt();
    if (count < 0 || count > maximum) {
      throw new IllegalArgumentException("Oversized ranged selection message");
    }
    return count;
  }

  private static void writeBox(RegistryFriendlyByteBuf buffer, Box box) {
    buffer.writeBoolean(box != null);
    if (box != null) {
      writeVector(buffer, box.first());
      writeVector(buffer, box.second());
    }
  }
  private static Box readBox(RegistryFriendlyByteBuf buffer) {
    return buffer.readBoolean() ? new Box(readVector(buffer), readVector(buffer)) : null;
  }
  private static void writeVector(RegistryFriendlyByteBuf buffer, BlockPos value) {
    buffer.writeInt(value.getX());
    buffer.writeInt(value.getY());
    buffer.writeInt(value.getZ());
  }
  private static BlockPos readVector(RegistryFriendlyByteBuf buffer) {
    return new BlockPos(buffer.readInt(), buffer.readInt(), buffer.readInt());
  }
}
