package com.lw.modularmachine.network;

import java.util.ArrayList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import static com.lw.modularmachine.network.MachinePayloads.FactoryAction;
import static com.lw.modularmachine.network.MachinePayloads.FactoryStatusPayload;
import static com.lw.modularmachine.network.MachinePayloads.FactoryStatusThread;
import static com.lw.modularmachine.network.MachinePayloads.FactoryThreadActionPayload;
import static com.lw.modularmachine.network.MachinePayloads.MAX_FACTORY_THREADS;
import static com.lw.modularmachine.network.MachinePayloadText.readText;
import static com.lw.modularmachine.network.MachinePayloadText.writeText;
import org.jetbrains.annotations.ApiStatus;

/** Factory status and thread action wire layout and bounds. */
@ApiStatus.Internal
final class FactoryPayloadCodec {
  private FactoryPayloadCodec() {
  }

  static void writeFactoryStatus(RegistryFriendlyByteBuf buffer, FactoryStatusPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    buffer.writeResourceLocation(payload.machineId());
    buffer.writeVarInt(payload.schedulerCursor());
    buffer.writeBoolean(payload.persistentStateValid());
    writeText(buffer, payload.structureState());
    writeText(buffer, payload.structureFailure());
    buffer.writeVarInt(payload.averageTickMicros());
    buffer.writeVarInt(payload.averageSearchMicros());
    buffer.writeVarInt(payload.threads().size());
    for (var thread : payload.threads()) {
      writeText(buffer, thread.threadId());
      buffer.writeResourceLocation(thread.machineId());
      buffer.writeBoolean(thread.lockedRecipe() != null);
      if (thread.lockedRecipe() != null) {
        buffer.writeResourceLocation(thread.lockedRecipe());
      }
      buffer.writeBoolean(thread.enabled());
      buffer.writeVarInt(thread.priority());
      buffer.writeVarInt(thread.parallelLimit());
      buffer.writeVarLong(thread.upgradeRevision());
      buffer.writeVarInt(thread.speedNumerator());
      buffer.writeVarInt(thread.speedDenominator());
      buffer.writeVarInt(thread.energyNumerator());
      buffer.writeVarInt(thread.energyDenominator());
      buffer.writeVarInt(thread.fluidNumerator());
      buffer.writeVarInt(thread.fluidDenominator());
      writeText(buffer, thread.state());
      writeText(buffer, thread.reason());
      buffer.writeVarLong(thread.revision());
      buffer.writeUUID(thread.runId());
      writeText(buffer, thread.runState());
      buffer.writeVarInt(thread.progress());
      buffer.writeVarInt(thread.duration());
      writeText(buffer, thread.recipeFailureMessage());
    }
  }

  static FactoryStatusPayload readFactoryStatus(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    var machineId = buffer.readResourceLocation();
    int cursor = buffer.readVarInt();
    boolean valid = buffer.readBoolean();
    String structureState = readText(buffer);
    String structureFailure = readText(buffer);
    int averageTickMicros = buffer.readVarInt();
    int averageSearchMicros = buffer.readVarInt();
    int size = buffer.readVarInt();
    if (size < 0 || size > MAX_FACTORY_THREADS) {
      throw new IllegalArgumentException("Too many factory status threads");
    }
    var threads = new ArrayList<FactoryStatusThread>(size);
    for (int i = 0; i < size; i++) {
      var id = readText(buffer);
      var machine = buffer.readResourceLocation();
      var locked = buffer.readBoolean() ? buffer.readResourceLocation() : null;
      threads.add(
          new FactoryStatusThread(
              id,
              machine,
              locked,
              buffer.readBoolean(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarLong(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              readText(buffer),
              readText(buffer),
              buffer.readVarLong(),
              buffer.readUUID(),
              readText(buffer),
              buffer.readVarInt(),
              buffer.readVarInt(),
              readText(buffer)));
    }
    return new FactoryStatusPayload(
        version,
        position,
        machineId,
        cursor,
        valid,
        structureState,
        structureFailure,
        averageTickMicros,
        averageSearchMicros,
        threads);
  }

  static void writeFactoryAction(RegistryFriendlyByteBuf buffer, FactoryThreadActionPayload payload) {
    buffer.writeVarInt(payload.version());
    buffer.writeBlockPos(payload.position());
    writeText(buffer, payload.threadId());
    buffer.writeVarLong(payload.expectedRevision());
    buffer.writeVarInt(payload.action().ordinal());
    buffer.writeBoolean(payload.lockedRecipe() != null);
    if (payload.lockedRecipe() != null) {
      buffer.writeResourceLocation(payload.lockedRecipe());
    }
    buffer.writeBoolean(payload.enabled());
    buffer.writeVarInt(payload.parallelLimit());
    buffer.writeVarLong(payload.upgradeRevision());
  }

  static FactoryThreadActionPayload readFactoryAction(RegistryFriendlyByteBuf buffer) {
    int version = buffer.readVarInt();
    var position = buffer.readBlockPos();
    String threadId = readText(buffer);
    long revision = buffer.readVarLong();
    int ordinal = buffer.readVarInt();
    if (ordinal < 0 || ordinal >= FactoryAction.values().length) {
      throw new IllegalArgumentException("Invalid factory action");
    }
    var locked = buffer.readBoolean() ? buffer.readResourceLocation() : null;
    return new FactoryThreadActionPayload(
        version,
        position,
        threadId,
        revision,
        FactoryAction.values()[ordinal],
        locked,
        buffer.readBoolean(),
        buffer.readVarInt(),
        buffer.readVarLong());
  }

}
