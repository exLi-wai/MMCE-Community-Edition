package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.menu.ParallelControllerMenu;
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

public final class ParallelControllerPayloads {
  private static final CustomPacketPayload.Type<Status> STATUS = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "parallel_status"));
  private static final CustomPacketPayload.Type<Action> ACTION = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "parallel_action"));
  private static Consumer<Status> receiver = ignored -> {
  };

  private ParallelControllerPayloads() {
  }

  public record Status(
      int containerId,
      BlockPos position,
      UUID instance,
      long revision,
      int maximum,
      int value,
      boolean editable) implements CustomPacketPayload {
    public Status {
      position = position.immutable();
      Objects.requireNonNull(instance, "instance");
      if (containerId < 0 || revision < 0 || maximum < 1 || value < 0 || value > maximum) {
        throw new IllegalArgumentException("Invalid parallel status");
      }
    }

    @Override
    public Type<Status> type() {
      return STATUS;
    }
  }

  public record Action(int containerId, BlockPos position, UUID instance, long revision, int maximum, int value)
      implements
        CustomPacketPayload {
    public Action {
      position = position.immutable();
      Objects.requireNonNull(instance, "instance");
      if (containerId < 0 || revision < 0 || maximum < 1 || value < 0 || value > maximum) {
        throw new IllegalArgumentException("Invalid parallel action");
      }
    }

    @Override
    public Type<Action> type() {
      return ACTION;
    }
  }

  public static void setClientReceiver(Consumer<Status> value) {
    receiver = Objects.requireNonNull(value, "receiver");
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("1")
        .playToClient(
            STATUS,
            StreamCodec.of(ParallelControllerPayloads::writeStatus, ParallelControllerPayloads::readStatus),
            (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)))
        .playToServer(
            ACTION,
            StreamCodec.of(ParallelControllerPayloads::writeAction, ParallelControllerPayloads::readAction),
            (payload, context) -> context.enqueueWork(() -> {
              if (context.player() instanceof ServerPlayer player
                  && player.containerMenu instanceof ParallelControllerMenu menu) {
                menu.apply(player, payload);
              }
            }));
  }

  private static void writeStatus(RegistryFriendlyByteBuf buffer, Status value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.instance());
    buffer.writeVarLong(value.revision());
    buffer.writeVarInt(value.maximum());
    buffer.writeVarInt(value.value());
    buffer.writeBoolean(value.editable());
  }

  private static Status readStatus(RegistryFriendlyByteBuf buffer) {
    return new Status(
        buffer.readVarInt(),
        buffer.readBlockPos(),
        buffer.readUUID(),
        buffer.readVarLong(),
        buffer.readVarInt(),
        buffer.readVarInt(),
        buffer.readBoolean());
  }

  private static void writeAction(RegistryFriendlyByteBuf buffer, Action value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.instance());
    buffer.writeVarLong(value.revision());
    buffer.writeVarInt(value.maximum());
    buffer.writeVarInt(value.value());
  }

  private static Action readAction(RegistryFriendlyByteBuf buffer) {
    return new Action(
        buffer.readVarInt(),
        buffer.readBlockPos(),
        buffer.readUUID(),
        buffer.readVarLong(),
        buffer.readVarInt(),
        buffer.readVarInt());
  }
}
