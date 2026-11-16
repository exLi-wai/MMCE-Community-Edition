package com.lw.modularmachine.network;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.machine.PortKind;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.lw.modularmachine.network.MachinePayloads.ResourcePortActionPayload;
import static com.lw.modularmachine.network.MachinePayloads.ResourcePortStatusPayload;
import static com.lw.modularmachine.network.MachinePayloadText.readText;
import static com.lw.modularmachine.network.MachinePayloadText.writeText;
import org.jetbrains.annotations.ApiStatus;

/** Resource port status and action wire layout and bounds. */
@ApiStatus.Internal
final class ResourcePortPayloadCodec {
  private ResourcePortPayloadCodec() {
  }

  static void writeResourceStatus(RegistryFriendlyByteBuf buffer, ResourcePortStatusPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    buffer.writeVarInt(payload.kind().ordinal());
    writeText(buffer, payload.tier());
    buffer.writeVarLong(payload.amount());
    buffer.writeVarLong(payload.capacity());
    buffer.writeVarLong(payload.transferLimit());
    FluidStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.fluid());
    buffer.writeResourceLocation(payload.chemical().chemical());
    buffer.writeVarLong(payload.chemical().amount());
  }

  static ResourcePortStatusPayload readResourceStatus(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    int ordinal = buffer.readVarInt();
    if (ordinal < 0 || ordinal >= PortKind.values().length) {
      throw new IllegalArgumentException("Invalid resource port kind");
    }
    return new ResourcePortStatusPayload(
        version,
        position,
        PortKind.values()[ordinal],
        readText(buffer),
        buffer.readVarLong(),
        buffer.readVarLong(),
        buffer.readVarLong(),
        FluidStack.OPTIONAL_STREAM_CODEC.decode(buffer),
        new ChemicalStackData(buffer.readResourceLocation(), buffer.readVarLong()));
  }

  static void writeResourceAction(RegistryFriendlyByteBuf buffer, ResourcePortActionPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    buffer.writeVarInt(payload.hand().ordinal());
  }

  static ResourcePortActionPayload readResourceAction(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    int ordinal = buffer.readVarInt();
    if (ordinal < 0 || ordinal >= InteractionHand.values().length) {
      throw new IllegalArgumentException("Invalid resource action hand");
    }
    return new ResourcePortActionPayload(version, position, InteractionHand.values()[ordinal]);
  }

}
