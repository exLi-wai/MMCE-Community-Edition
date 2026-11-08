package com.lw.modularmachine.network;

import com.lw.modularmachine.machine.assembly.AssemblyService;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.ApiStatus;

/** Internal, server-authored previews for the held registered blueprint. */
@ApiStatus.Internal
public final class BlueprintPreviewPayloads {
  private static final Type<Request> REQUEST = new Type<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "blueprint_preview_request"));
  private static final Type<Snapshot> SNAPSHOT = new Type<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "blueprint_preview"));
  private static Consumer<Snapshot> receiver = ignored -> {
  };

  private BlueprintPreviewPayloads() {
  }

  public record Request(long request, InteractionHand hand, ResourceLocation machine, int size)
      implements
        CustomPacketPayload {
    public Request {
      Objects.requireNonNull(hand, "hand");
      Objects.requireNonNull(machine, "machine");
      if (request < 0 || size < -1) {
        throw new IllegalArgumentException("Invalid blueprint preview request");
      }
    }

    @Override
    public Type<Request> type() {
      return REQUEST;
    }
  }

  public record Snapshot(
      boolean dynamic,
      int size,
      int minimum,
      int maximum,
      int color,
      MachineOrientation orientation,
      ProjectionPayloads.Snapshot preview) implements CustomPacketPayload {
    public Snapshot {
      Objects.requireNonNull(preview, "preview");
      Objects.requireNonNull(orientation, "orientation");
      if (size < -1 || minimum < 0 || maximum < minimum || size > maximum || (color & ~0xFFFFFF) != 0) {
        throw new IllegalArgumentException("Invalid blueprint preview bounds");
      }
    }

    @Override
    public Type<Snapshot> type() {
      return SNAPSHOT;
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("1").playToServer(REQUEST, StreamCodec.of((buffer, request) -> {
      buffer.writeVarLong(request.request());
      buffer.writeEnum(request.hand());
      buffer.writeResourceLocation(request.machine());
      buffer.writeInt(request.size());
    },
        buffer -> new Request(
            buffer.readVarLong(),
            buffer.readEnum(InteractionHand.class),
            buffer.readResourceLocation(),
            buffer.readInt())),
        (request, context) -> context.enqueueWork(() -> {
          if (context.player() instanceof ServerPlayer player) {
            AssemblyService.requestBlueprintPreview(player, request);
          }
        }));
    event.registrar("1").playToClient(
        SNAPSHOT,
        StreamCodec.of(BlueprintPreviewPayloads::write, BlueprintPreviewPayloads::read),
        (snapshot, context) -> context.enqueueWork(() -> receiver.accept(snapshot)));
  }

  public static void setReceiver(Consumer<Snapshot> value) {
    receiver = Objects.requireNonNull(value);
  }

  public static void request(long request, InteractionHand hand, ResourceLocation machine, int size) {
    PacketDistributor.sendToServer(new Request(request, hand, machine, size));
  }

  public static int sendChunk(ServerPlayer player, Request request, long epoch, boolean dynamic, int size, int minimum, int maximum, int color, MachineOrientation orientation, List<ProjectionPayloads.Entry> entries, int start) {
    int end = ProjectionPayloads.chunkEnd(entries, start);
    var preview = new ProjectionPayloads.Snapshot(
        request.request(),
        epoch,
        player.level().dimension().location(),
        request.machine(),
        BlockPos.ZERO,
        start,
        entries.size(),
        entries.subList(start, end));
    PacketDistributor.sendToPlayer(player, new Snapshot(dynamic, size, minimum, maximum, color, orientation, preview));
    return end;
  }

  public static void invalidate(ServerPlayer player, Request request) {
    invalidate(player, request, false, -1, 0, 0, 0xFFFFFF, MachineOrientation.IDENTITY);
  }

  public static void invalidate(ServerPlayer player, Request request, boolean dynamic, int size, int minimum, int maximum, int color, MachineOrientation orientation) {
    var preview = new ProjectionPayloads.Snapshot(
        request.request(),
        0,
        player.level().dimension().location(),
        request.machine(),
        BlockPos.ZERO,
        -1,
        0,
        List.of());
    PacketDistributor.sendToPlayer(player, new Snapshot(dynamic, size, minimum, maximum, color, orientation, preview));
  }

  private static void write(RegistryFriendlyByteBuf buffer, Snapshot snapshot) {
    buffer.writeBoolean(snapshot.dynamic());
    buffer.writeInt(snapshot.size());
    buffer.writeVarInt(snapshot.minimum());
    buffer.writeVarInt(snapshot.maximum());
    buffer.writeInt(snapshot.color());
    buffer.writeEnum(snapshot.orientation().front());
    buffer.writeByte(snapshot.orientation().roll());
    ProjectionPayloads.write(buffer, snapshot.preview());
  }

  private static Snapshot read(RegistryFriendlyByteBuf buffer) {
    return new Snapshot(
        buffer.readBoolean(),
        buffer.readInt(),
        buffer.readVarInt(),
        buffer.readVarInt(),
        buffer.readInt(),
        MachineOrientation.of(buffer.readEnum(Direction.class), buffer.readUnsignedByte()),
        ProjectionPayloads.read(buffer));
  }
}
