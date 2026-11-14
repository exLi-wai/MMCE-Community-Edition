package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.controller.ControllerScriptDataValues;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Internal menu-only read snapshot; no controller persistence or client write
 * request is transported.
 */
public final class ControllerGuiDataPayloads {
  private static final CustomPacketPayload.Type<Update> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "controller_gui_data"));
  private static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec.of((buffer, value) -> {
    buffer.writeVarInt(value.containerId());
    buffer.writeResourceLocation(value.dimension());
    buffer.writeBlockPos(value.position());
    buffer.writeResourceLocation(value.machine());
    buffer.writeUtf(value.customData(), ControllerScriptDataValues.MAX_BYTES);
  },
      buffer -> new Update(
          buffer.readVarInt(),
          buffer.readResourceLocation(),
          buffer.readBlockPos(),
          buffer.readResourceLocation(),
          buffer.readUtf(ControllerScriptDataValues.MAX_BYTES)));
  private static Consumer<Update> receiver = ignored -> {
  };

  private ControllerGuiDataPayloads() {
  }

  public record Update(
      int containerId,
      ResourceLocation dimension,
      BlockPos position,
      ResourceLocation machine,
      String customData) implements CustomPacketPayload {
    public Update {
      Objects.requireNonNull(dimension, "dimension");
      position = position.immutable();
      Objects.requireNonNull(machine, "machine");
      customData = ControllerScriptDataValues.canonicalData(customData);
      if (containerId < 0) {
        throw new IllegalArgumentException("Invalid controller GUI container ID");
      }
    }

    @Override
    public Type<Update> type() {
      return TYPE;
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("1")
        .playToClient(TYPE, CODEC, (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)));
  }

  public static void setClientReceiver(Consumer<Update> next) {
    receiver = Objects.requireNonNull(next, "receiver");
  }
}
