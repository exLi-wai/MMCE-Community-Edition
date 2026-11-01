package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.recipe.InputGroup;
import com.lw.modularmachine.menu.GroupInputMenu;
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

public final class GroupInputPayloads {
  private static final CustomPacketPayload.Type<Status> STATUS = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "input_group_status"));
  private static final CustomPacketPayload.Type<Action> ACTION = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "input_group_action"));
  private static Consumer<Status> receiver = ignored -> {
  };

  private GroupInputPayloads() {
  }

  public record Status(
      int containerId,
      BlockPos position,
      UUID session,
      long revision,
      int group,
      int preferredGroup,
      boolean editable) implements CustomPacketPayload {
    public Status {
      position = position.immutable();
      Objects.requireNonNull(session, "session");
      InputGroup.require(group);
      if (containerId < 0 || revision < 0 || preferredGroup <= 0) {
        throw new IllegalArgumentException("Invalid input group status");
      }
    }

    @Override
    public Type<Status> type() {
      return STATUS;
    }
  }

  public record Action(int containerId, BlockPos position, UUID session, long revision, int group)
      implements
        CustomPacketPayload {
    public Action {
      position = position.immutable();
      Objects.requireNonNull(session, "session");
      InputGroup.require(group);
      if (containerId < 0 || revision < 0) {
        throw new IllegalArgumentException("Invalid input group action");
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
            StreamCodec.of(GroupInputPayloads::writeStatus, GroupInputPayloads::readStatus),
            (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)))
        .playToServer(
            ACTION,
            StreamCodec.of(GroupInputPayloads::writeAction, GroupInputPayloads::readAction),
            (payload, context) -> context.enqueueWork(() -> {
              if (context.player() instanceof ServerPlayer player
                  && player.containerMenu instanceof GroupInputMenu menu) {
                menu.apply(player, payload);
              }
            }));
  }

  private static void writeStatus(RegistryFriendlyByteBuf buffer, Status value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.session());
    buffer.writeVarLong(value.revision());
    buffer.writeInt(value.group());
    buffer.writeVarInt(value.preferredGroup());
    buffer.writeBoolean(value.editable());
  }

  private static Status readStatus(RegistryFriendlyByteBuf buffer) {
    return new Status(
        buffer.readVarInt(),
        buffer.readBlockPos(),
        buffer.readUUID(),
        buffer.readVarLong(),
        buffer.readInt(),
        buffer.readVarInt(),
        buffer.readBoolean());
  }

  private static void writeAction(RegistryFriendlyByteBuf buffer, Action value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.session());
    buffer.writeVarLong(value.revision());
    buffer.writeInt(value.group());
  }

  private static Action readAction(RegistryFriendlyByteBuf buffer) {
    return new Action(
        buffer.readVarInt(),
        buffer.readBlockPos(),
        buffer.readUUID(),
        buffer.readVarLong(),
        buffer.readInt());
  }
}
