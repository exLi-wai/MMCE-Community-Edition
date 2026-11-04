package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Compact client update for machine casing tint; never sends component
 * inventories.
 */
public final class MachineColorPayloads {
  private static final CustomPacketPayload.Type<Update> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "machine_color"));
  private static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec
      .of(MachineColorPayloads::write, MachineColorPayloads::read);
  private static Consumer<Update> receiver = ignored -> {
  };

  private MachineColorPayloads() {
  }

  public record Update(ResourceLocation dimension, BlockPos position, int color) implements CustomPacketPayload {
    public Update {
      Objects.requireNonNull(dimension, "dimension");
      position = position.immutable();
      if ((color & ~0xFFFFFF) != 0) {
        throw new IllegalArgumentException("Invalid machine RGB color");
      }
    }

    @Override
    public Type<Update> type() {
      return TYPE;
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("1").playToClient(TYPE, CODEC, (payload, context) -> {
      context.enqueueWork(() -> receiver.accept(payload));
    });
  }

  public static void setClientReceiver(Consumer<Update> value) {
    receiver = Objects.requireNonNull(value, "receiver");
  }

  public static void publish(ServerLevel level, BlockPos position, int color) {
    var update = new Update(level.dimension().location(), position, color & 0xFFFFFF);
    PacketDistributor.sendToPlayersTrackingChunk(level, level.getChunkAt(position).getPos(), update);
  }

  private static void write(RegistryFriendlyByteBuf buffer, Update update) {
    buffer.writeResourceLocation(update.dimension());
    buffer.writeBlockPos(update.position());
    buffer.writeInt(update.color());
  }

  private static Update read(RegistryFriendlyByteBuf buffer) {
    return new Update(buffer.readResourceLocation(), buffer.readBlockPos(), buffer.readInt());
  }
}
