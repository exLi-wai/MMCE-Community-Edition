package com.lw.modularmachine.network;

import java.util.ArrayList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import static com.lw.modularmachine.network.MachinePayloads.ControllerStatusPayload;
import static com.lw.modularmachine.network.MachinePayloads.MAX_SHORTAGES;
import static com.lw.modularmachine.network.MachinePayloads.Shortage;
import static com.lw.modularmachine.network.MachinePayloadText.readText;
import static com.lw.modularmachine.network.MachinePayloadText.writeText;
import org.jetbrains.annotations.ApiStatus;

/** Controller status wire layout and bounds. */
@ApiStatus.Internal
final class ControllerPayloadCodec {
  private ControllerPayloadCodec() {
  }

  static void writeStatus(RegistryFriendlyByteBuf buffer, ControllerStatusPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    buffer.writeResourceLocation(payload.machineId());
    buffer.writeVarLong(payload.definitionEpoch());
    writeText(buffer, payload.structureState());
    writeText(buffer, payload.structureFailure());
    writeText(buffer, payload.front());
    buffer.writeVarInt(payload.roll());
    buffer.writeBoolean(payload.productionAllowed());
    buffer.writeVarLong(payload.successfulTransfers());
    buffer.writeVarLong(payload.bindingRebuilds());
    writeText(buffer, payload.recipeStatus());
    writeText(buffer, payload.recipeId());
    writeText(buffer, payload.runState());
    buffer.writeVarInt(payload.progress());
    buffer.writeVarInt(payload.duration());
    buffer.writeVarInt(payload.averageTickMicros());
    buffer.writeVarInt(payload.averageSearchMicros());
    writeText(buffer, payload.recipeFailureMessage());
    buffer.writeVarInt(payload.shortages().size());
    for (var shortage : payload.shortages()) {
      writeText(buffer, shortage.key());
      buffer.writeVarLong(shortage.required());
      buffer.writeVarLong(shortage.available());
      buffer.writeVarLong(shortage.missing());
    }
  }

  static ControllerStatusPayload readStatus(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    var machineId = buffer.readResourceLocation();
    long epoch = buffer.readVarLong();
    String structureState = readText(buffer);
    String structureFailure = readText(buffer);
    String front = readText(buffer);
    int roll = buffer.readVarInt();
    boolean productionAllowed = buffer.readBoolean();
    long successfulTransfers = buffer.readVarLong();
    long bindingRebuilds = buffer.readVarLong();
    String recipeStatus = readText(buffer);
    String recipeId = readText(buffer);
    String runState = readText(buffer);
    int progress = buffer.readVarInt();
    int duration = buffer.readVarInt();
    int averageTickMicros = buffer.readVarInt();
    int averageSearchMicros = buffer.readVarInt();
    String recipeFailureMessage = readText(buffer);
    int size = buffer.readVarInt();
    if (size < 0 || size > MAX_SHORTAGES) {
      throw new IllegalArgumentException("Too many status shortages");
    }
    var shortages = new ArrayList<Shortage>(size);
    for (int i = 0; i < size; i++) {
      shortages.add(new Shortage(readText(buffer), buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong()));
    }
    return new ControllerStatusPayload(
        version,
        position,
        machineId,
        epoch,
        structureState,
        structureFailure,
        front,
        roll,
        productionAllowed,
        successfulTransfers,
        bindingRebuilds,
        recipeStatus,
        recipeId,
        runState,
        progress,
        duration,
        averageTickMicros,
        averageSearchMicros,
        shortages,
        recipeFailureMessage);
  }

}
