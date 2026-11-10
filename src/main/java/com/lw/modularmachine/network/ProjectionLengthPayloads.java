package com.lw.modularmachine.network;

import com.lw.modularmachine.machine.assembly.AssemblyService;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.ApiStatus;

/** Internal input for the layer count stored in the held projector. */
@ApiStatus.Internal
public final class ProjectionLengthPayloads {
  private static final CustomPacketPayload.Type<Adjust> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath("mmcece", "projection_length"));
  private static final StreamCodec<RegistryFriendlyByteBuf, Adjust> CODEC = StreamCodec.of((buffer, packet) -> {
    buffer.writeEnum(packet.hand());
    buffer.writeByte(packet.delta());
  }, buffer -> new Adjust(buffer.readEnum(InteractionHand.class), buffer.readByte()));

  public record Adjust(InteractionHand hand, int delta) implements CustomPacketPayload {
    public Adjust {
      Objects.requireNonNull(hand, "hand");
      if (delta != -1 && delta != 1) {
        throw new IllegalArgumentException("Invalid projector length step");
      }
    }

    @Override
    public Type<Adjust> type() {
      return TYPE;
    }
  }

  private ProjectionLengthPayloads() {
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("2").playToServer(TYPE, CODEC, (packet, context) -> context.enqueueWork(() -> {
      if (context.player() instanceof ServerPlayer player) {
        AssemblyService.adjustProjectionLength(player, packet.hand(), packet.delta());
      }
    }));
  }

  public static void adjust(InteractionHand hand, int delta) {
    PacketDistributor.sendToServer(new Adjust(hand, delta));
  }
}
