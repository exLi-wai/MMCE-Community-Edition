package com.lw.modularmachine.network;

import com.lw.modularmachine.api.smart.SmartInterfaceBinding;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import com.lw.modularmachine.menu.SmartInterfaceMenu;
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

public final class SmartInterfacePayloads {
  private static final CustomPacketPayload.Type<Status> STATUS = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "smart_interface_status"));
  private static final CustomPacketPayload.Type<Action> ACTION = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "smart_interface_action"));
  private static Consumer<Status> receiver = ignored -> {
  };

  private SmartInterfacePayloads() {
  }

  public record Status(
      int containerId,
      BlockPos position,
      UUID instance,
      long revision,
      boolean valid,
      List<SmartInterfaceBlockEntity.Entry> entries) implements CustomPacketPayload {
    public Status {
      Objects.requireNonNull(instance, "instance");
      if (containerId < 0
          || revision < 0
          || entries.size() > SmartInterfaceBlockEntity.MAX_BINDINGS
          || !valid && !entries.isEmpty()) {
        throw new IllegalArgumentException("Invalid smart interface status");
      }
      position = position.immutable();
      entries = List.copyOf(entries);
    }

    @Override
    public CustomPacketPayload.Type<Status> type() {
      return STATUS;
    }
  }

  public record Action(
      int containerId,
      BlockPos position,
      UUID instance,
      BlockPos controller,
      String interfaceType,
      long revision,
      double value) implements CustomPacketPayload {
    public Action {
      Objects.requireNonNull(instance, "instance");
      ResourceSelector.require(interfaceType);
      if (containerId < 0 || interfaceType.isEmpty() || revision < 0 || !Double.isFinite(value)) {
        throw new IllegalArgumentException("Invalid smart interface action");
      }
      position = position.immutable();
      controller = controller.immutable();
    }

    @Override
    public CustomPacketPayload.Type<Action> type() {
      return ACTION;
    }
  }

  public static void setClientReceiver(Consumer<Status> value) {
    receiver = Objects.requireNonNull(value, "receiver");
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("2")
        .playToClient(
            STATUS,
            StreamCodec.of(SmartInterfacePayloads::write, SmartInterfacePayloads::read),
            (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)))
        .playToServer(
            ACTION,
            StreamCodec.of(SmartInterfacePayloads::writeAction, SmartInterfacePayloads::readAction),
            (payload, context) -> context.enqueueWork(() -> {
              if (context.player() instanceof ServerPlayer player
                  && player.containerMenu instanceof SmartInterfaceMenu menu
                  && menu.containerId == payload.containerId()
                  && menu.stillValid(player)
                  && menu.smart().getBlockPos().equals(payload.position())
                  && menu.smart().instance().equals(payload.instance())) {
                menu.smart()
                    .update(player, payload.controller(), payload.interfaceType(), payload.revision(), payload.value());
                menu.refreshStatus();
              }
            }));
  }

  private static void write(RegistryFriendlyByteBuf buffer, Status value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.instance());
    buffer.writeVarLong(value.revision());
    buffer.writeBoolean(value.valid());
    buffer.writeVarInt(value.entries().size());
    for (var entry : value.entries()) {
      var binding = entry.binding();
      var type = entry.specification();
      buffer.writeBlockPos(binding.controller());
      buffer.writeResourceLocation(binding.machine());
      buffer.writeUtf(binding.type(), ResourceSelector.MAX_LENGTH);
      buffer.writeDouble(binding.value());
      buffer.writeVarLong(binding.revision());
      buffer.writeUtf(entry.machineName(), 512);
      buffer.writeDouble(type.defaultValue());
      buffer.writeDouble(type.minimum());
      buffer.writeDouble(type.maximum());
      buffer.writeUtf(type.header(), 256);
      buffer.writeUtf(type.valueLabel(), 256);
      buffer.writeUtf(type.footer(), 256);
      buffer.writeInt(type.priority());
      buffer.writeBoolean(entry.editable());
    }
  }

  private static Status read(RegistryFriendlyByteBuf buffer) {
    int container = buffer.readVarInt();
    var position = buffer.readBlockPos();
    var instance = buffer.readUUID();
    long revision = buffer.readVarLong();
    boolean valid = buffer.readBoolean();
    int size = buffer.readVarInt();
    if (size < 0 || size > SmartInterfaceBlockEntity.MAX_BINDINGS) {
      throw new IllegalArgumentException("Smart interface status exceeds binding limit");
    }
    var entries = new ArrayList<SmartInterfaceBlockEntity.Entry>();
    for (int index = 0; index < size; index++) {
      var binding = new SmartInterfaceBinding(
          buffer.readBlockPos(),
          buffer.readResourceLocation(),
          buffer.readUtf(ResourceSelector.MAX_LENGTH),
          buffer.readDouble(),
          buffer.readVarLong());
      String name = buffer.readUtf(512);
      var type = new SmartInterfaceType(
          binding.type(),
          buffer.readDouble(),
          buffer.readDouble(),
          buffer.readDouble(),
          buffer.readUtf(256),
          buffer.readUtf(256),
          buffer.readUtf(256),
          buffer.readInt());
      entries.add(new SmartInterfaceBlockEntity.Entry(binding, name, type, buffer.readBoolean()));
    }

    return new Status(container, position, instance, revision, valid, entries);
  }

  private static void writeAction(RegistryFriendlyByteBuf buffer, Action value) {
    buffer.writeVarInt(value.containerId());
    buffer.writeBlockPos(value.position());
    buffer.writeUUID(value.instance());
    buffer.writeBlockPos(value.controller());
    buffer.writeUtf(value.interfaceType(), ResourceSelector.MAX_LENGTH);
    buffer.writeVarLong(value.revision());
    buffer.writeDouble(value.value());
  }

  private static Action readAction(RegistryFriendlyByteBuf buffer) {
    return new Action(
        buffer.readVarInt(),
        buffer.readBlockPos(),
        buffer.readUUID(),
        buffer.readBlockPos(),
        buffer.readUtf(ResourceSelector.MAX_LENGTH),
        buffer.readVarLong(),
        buffer.readDouble());
  }
}
