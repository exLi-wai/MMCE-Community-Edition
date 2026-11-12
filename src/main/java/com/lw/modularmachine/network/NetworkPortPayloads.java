package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.NetworkPortSnapshot;
import com.lw.modularmachine.menu.NetworkPortMenu;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Independent network-port protocol; clients submit configuration intentions
 * only.
 */
public final class NetworkPortPayloads {
  private static final CustomPacketPayload.Type<Status> STATUS = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "network_port_status"));
  private static final CustomPacketPayload.Type<Action> ACTION = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "network_port_action"));
  private static final CustomPacketPayload.Type<GhostAction> GHOST_ACTION = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "network_port_ghost_action"));
  private static Consumer<Status> receiver = ignored -> {
  };

  private NetworkPortPayloads() {
  }

  public enum Operation {
    SELECT_CARRIED,
    SET_AMOUNT,
    CLEAR
  }

  public record Status(
      int containerId,
      BlockPos position,
      UUID session,
      long sequence,
      boolean editable,
      NetworkPortSnapshot snapshot) implements CustomPacketPayload {
    public Status {
      position = position.immutable();
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(snapshot, "snapshot");
      if (containerId < 0 || sequence < 0) {
        throw new IllegalArgumentException("Invalid network port status");
      }
    }

    @Override
    public Type<Status> type() {
      return STATUS;
    }
  }

  public record Action(
      int containerId,
      BlockPos position,
      UUID session,
      long revision,
      Operation operation,
      int slot,
      int amount) implements CustomPacketPayload {
    public Action {
      position = position.immutable();
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(operation, "operation");
      if (containerId < 0
          || revision < 0
          || slot < 0
          || slot >= 9
          || amount < 0
          || (operation == Operation.CLEAR) != (amount == 0)) {
        throw new IllegalArgumentException("Invalid network port action");
      }
    }

    @Override
    public Type<Action> type() {
      return ACTION;
    }
  }

  /**
   * Quantity-free filter declaration; this packet never grants or moves inventory
   * contents.
   */
  public record GhostAction(
      int containerId,
      BlockPos position,
      UUID session,
      long revision,
      int slot,
      NetworkPortResource resource) implements CustomPacketPayload {
    public GhostAction {
      position = position.immutable();
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(resource, "resource");
      if (containerId < 0 || revision < 0 || slot < 0 || slot >= 9) {
        throw new IllegalArgumentException("Invalid network ghost configuration");
      }
    }

    @Override
    public Type<GhostAction> type() {
      return GHOST_ACTION;
    }
  }

  public static void setClientReceiver(Consumer<Status> value) {
    receiver = Objects.requireNonNull(value, "receiver");
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("1")
        .playToClient(
            STATUS,
            StreamCodec.of(NetworkPortPayloads::writeStatus, NetworkPortPayloads::readStatus),
            (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)))
        .playToServer(
            ACTION,
            StreamCodec.of(NetworkPortPayloads::writeAction, NetworkPortPayloads::readAction),
            (payload, context) -> context.enqueueWork(() -> {
              if (context.player() instanceof ServerPlayer player
                  && player.containerMenu instanceof NetworkPortMenu menu) {
                menu.apply(player, payload);
              }
            }))
        .playToServer(
            GHOST_ACTION,
            StreamCodec.of(NetworkPortPayloads::writeGhostAction, NetworkPortPayloads::readGhostAction),
            (payload, context) -> context.enqueueWork(() -> {
              if (context.player() instanceof ServerPlayer player
                  && player.containerMenu instanceof NetworkPortMenu menu) {
                menu.apply(player, payload);
              }
            }));
  }

  private static void writeStatus(RegistryFriendlyByteBuf buffer, Status value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.session());
    buffer.writeVarLong(value.sequence());
    buffer.writeBoolean(value.editable());
    var snapshot = value.snapshot();
    buffer.writeEnum(snapshot.resource());
    buffer.writeBoolean(snapshot.input());
    buffer.writeVarLong(snapshot.configRevision());
    buffer.writeBoolean(snapshot.active());
    buffer.writeBoolean(snapshot.supported());
    buffer.writeVarInt(snapshot.rows().size());
    for (var row : snapshot.rows()) {
      writeResource(buffer, row.configured());
      buffer.writeVarInt(row.target());
      writeResource(buffer, row.buffered());
      buffer.writeVarLong(row.stored());
    }
  }

  private static Status readStatus(RegistryFriendlyByteBuf buffer) {
    int containerId = buffer.readVarInt();
    var position = buffer.readBlockPos();
    var session = buffer.readUUID();
    long sequence = buffer.readVarLong();
    boolean editable = buffer.readBoolean();
    var resource = buffer.readEnum(PortKind.Resource.class);
    boolean input = buffer.readBoolean();
    long revision = buffer.readVarLong();
    boolean active = buffer.readBoolean();
    boolean supported = buffer.readBoolean();
    int size = buffer.readVarInt();
    if (size != (input ? 9 : 36)) {
      throw new IllegalArgumentException("Invalid network port row count");
    }
    var rows = new ArrayList<NetworkPortSnapshot.Row>(size);
    for (int index = 0; index < size; index++) {
      rows.add(
          new NetworkPortSnapshot.Row(
              readResource(buffer),
              buffer.readVarInt(),
              readResource(buffer),
              buffer.readVarLong()));
    }

    return new Status(
        containerId,
        position,
        session,
        sequence,
        editable,
        new NetworkPortSnapshot(resource, input, revision, active, supported, rows));
  }

  private static void writeResource(RegistryFriendlyByteBuf buffer, NetworkPortResource value) {
    buffer.writeBoolean(value != null);
    if (value != null) {
      NetworkPortResource.STREAM_CODEC.encode(buffer, value);
    }
  }

  private static NetworkPortResource readResource(RegistryFriendlyByteBuf buffer) {
    return buffer.readBoolean() ? NetworkPortResource.STREAM_CODEC.decode(buffer) : null;
  }

  private static void writeAction(RegistryFriendlyByteBuf buffer, Action value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.session());
    buffer.writeVarLong(value.revision());
    buffer.writeEnum(value.operation());
    buffer.writeVarInt(value.slot());
    buffer.writeVarInt(value.amount());
  }

  private static Action readAction(RegistryFriendlyByteBuf buffer) {
    return new Action(
        buffer.readVarInt(),
        buffer.readBlockPos(),
        buffer.readUUID(),
        buffer.readVarLong(),
        buffer.readEnum(Operation.class),
        buffer.readVarInt(),
        buffer.readVarInt());
  }

  private static void writeGhostAction(RegistryFriendlyByteBuf buffer, GhostAction value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.session());
    buffer.writeVarLong(value.revision());
    buffer.writeVarInt(value.slot());
    NetworkPortResource.STREAM_CODEC.encode(buffer, value.resource());
  }

  private static GhostAction readGhostAction(RegistryFriendlyByteBuf buffer) {
    return new GhostAction(
        buffer.readVarInt(),
        buffer.readBlockPos(),
        buffer.readUUID(),
        buffer.readVarLong(),
        buffer.readVarInt(),
        NetworkPortResource.STREAM_CODEC.decode(buffer));
  }
}
