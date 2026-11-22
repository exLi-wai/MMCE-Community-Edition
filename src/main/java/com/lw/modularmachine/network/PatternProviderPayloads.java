package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.pattern.PatternProviderState;
import com.lw.modularmachine.menu.PatternProviderMenu;
import java.util.ArrayList;
import java.util.List;
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
 * Internal bounded provider view; clients never submit cache quantities or CPU
 * identities.
 */
public final class PatternProviderPayloads {
  public static final int ALL_SLOTS = -1;
  public static final int COLUMNS = 3;
  public static final int VISIBLE_ROWS = 7;
  public static final int PAGE_SIZE = COLUMNS * VISIBLE_ROWS;
  private static final CustomPacketPayload.Type<Status> STATUS = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "pattern_provider_status"));
  private static final CustomPacketPayload.Type<Action> ACTION = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "pattern_provider_action"));
  private static Consumer<Status> receiver = ignored -> {
  };

  public enum Operation {
    SELECT_SLOT,
    SCROLL,
    RETURN_ALL,
    FLUID_CONTAINER
  }

  public record Entry(NetworkPortResource resource, long amount, int slot, long generation, boolean retired) {
    public Entry {
      Objects.requireNonNull(resource);
      if (amount <= 0 || slot < 0 || slot >= PatternProviderState.SLOT_COUNT || generation <= 0) {
        throw new IllegalArgumentException("Invalid provider cache entry");
      }
    }
  }

  public record Snapshot(
      long revision,
      List<Long> generations,
      int selectedSlot,
      int firstRow,
      int totalRows,
      List<Entry> entries,
      NetworkPortResource fluid,
      long fluidAmount,
      long fluidCapacity) {
    public Snapshot {
      generations = List.copyOf(generations);
      entries = List.copyOf(entries);
      if (revision < 0
          || generations.size() != PatternProviderState.SLOT_COUNT
          || generations.stream().anyMatch(value -> value < 0)
          || selectedSlot < ALL_SLOTS
          || selectedSlot >= PatternProviderState.SLOT_COUNT
          || firstRow < 0
          || totalRows < 0
          || firstRow > Math.max(0, totalRows - VISIBLE_ROWS)
          || entries.size() > PAGE_SIZE
          || fluidAmount < 0
          || fluidCapacity < fluidAmount
          || (fluid == null) != (fluidAmount == 0)
          || fluid != null && !(fluid instanceof NetworkPortResource.Fluid)) {
        throw new IllegalArgumentException("Invalid provider snapshot");
      }
    }
  }

  public record Status(
      int containerId,
      BlockPos position,
      UUID session,
      long sequence,
      boolean editable,
      Snapshot snapshot) implements CustomPacketPayload {
    public Status {
      position = position.immutable();
      Objects.requireNonNull(session);
      Objects.requireNonNull(snapshot);
      if (containerId < 0 || sequence < 0) {
        throw new IllegalArgumentException("Invalid provider status");
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
      int slot,
      long generation,
      Operation operation,
      int firstRow) implements CustomPacketPayload {
    public Action {
      position = position.immutable();
      Objects.requireNonNull(session);
      Objects.requireNonNull(operation);
      if (containerId < 0
          || revision < 0
          || slot < ALL_SLOTS
          || slot >= PatternProviderState.SLOT_COUNT
          || generation < 0
          || slot == ALL_SLOTS && generation != 0
          || firstRow < 0) {
        throw new IllegalArgumentException("Invalid provider action");
      }
    }

    @Override
    public Type<Action> type() {
      return ACTION;
    }
  }

  public static void setClientReceiver(Consumer<Status> value) {
    receiver = Objects.requireNonNull(value);
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("2")
        .playToClient(
            STATUS,
            StreamCodec.of(PatternProviderPayloads::writeStatus, PatternProviderPayloads::readStatus),
            (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)))
        .playToServer(
            ACTION,
            StreamCodec.of(PatternProviderPayloads::writeAction, PatternProviderPayloads::readAction),
            (payload, context) -> context.enqueueWork(() -> {
              if (context.player() instanceof ServerPlayer player
                  && player.containerMenu instanceof PatternProviderMenu menu) {
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
    buffer.writeVarLong(snapshot.revision());
    snapshot.generations().forEach(buffer::writeVarLong);
    buffer.writeVarInt(snapshot.selectedSlot());
    buffer.writeVarInt(snapshot.firstRow());
    buffer.writeVarInt(snapshot.totalRows());
    buffer.writeVarInt(snapshot.entries().size());
    for (var entry : snapshot.entries()) {
      NetworkPortResource.STREAM_CODEC.encode(buffer, entry.resource());
      buffer.writeVarLong(entry.amount());
      buffer.writeVarInt(entry.slot());
      buffer.writeVarLong(entry.generation());
      buffer.writeBoolean(entry.retired());
    }
    buffer.writeBoolean(snapshot.fluid() != null);
    if (snapshot.fluid() != null) {
      NetworkPortResource.STREAM_CODEC.encode(buffer, snapshot.fluid());
    }
    buffer.writeVarLong(snapshot.fluidAmount());
    buffer.writeVarLong(snapshot.fluidCapacity());
  }

  private static Status readStatus(RegistryFriendlyByteBuf buffer) {
    int id = buffer.readVarInt();
    var position = buffer.readBlockPos();
    var session = buffer.readUUID();
    long sequence = buffer.readVarLong();
    boolean editable = buffer.readBoolean();
    long revision = buffer.readVarLong();
    var generations = new ArrayList<Long>(PatternProviderState.SLOT_COUNT);
    for (int slot = 0; slot < PatternProviderState.SLOT_COUNT; slot++) {
      generations.add(buffer.readVarLong());
    }
    int selected = buffer.readVarInt();
    int firstRow = buffer.readVarInt();
    int totalRows = buffer.readVarInt();
    int count = buffer.readVarInt();
    if (count < 0 || count > PAGE_SIZE) {
      throw new IllegalArgumentException("Invalid provider page size");
    }
    var entries = new ArrayList<Entry>(count);
    for (int index = 0; index < count; index++) {
      entries.add(
          new Entry(
              NetworkPortResource.STREAM_CODEC.decode(buffer),
              buffer.readVarLong(),
              buffer.readVarInt(),
              buffer.readVarLong(),
              buffer.readBoolean()));
    }
    var fluid = buffer.readBoolean() ? NetworkPortResource.STREAM_CODEC.decode(buffer) : null;
    var snapshot = new Snapshot(
        revision,
        generations,
        selected,
        firstRow,
        totalRows,
        entries,
        fluid,
        buffer.readVarLong(),
        buffer.readVarLong());

    return new Status(id, position, session, sequence, editable, snapshot);
  }

  private static void writeAction(RegistryFriendlyByteBuf buffer, Action value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.session());
    buffer.writeVarLong(value.revision());
    buffer.writeVarInt(value.slot());
    buffer.writeVarLong(value.generation());
    buffer.writeEnum(value.operation());
    buffer.writeVarInt(value.firstRow());
  }

  private static Action readAction(RegistryFriendlyByteBuf buffer) {
    return new Action(
        buffer.readVarInt(),
        buffer.readBlockPos(),
        buffer.readUUID(),
        buffer.readVarLong(),
        buffer.readVarInt(),
        buffer.readVarLong(),
        buffer.readEnum(Operation.class),
        buffer.readVarInt());
  }

  private PatternProviderPayloads() {
  }
}
