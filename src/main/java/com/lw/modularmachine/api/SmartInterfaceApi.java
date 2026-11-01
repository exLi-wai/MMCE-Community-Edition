package com.lw.modularmachine.api;

import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Read-only numerical interface queries for server integrations. */
public final class SmartInterfaceApi {
  public record Value(
      BlockPos position,
      String partId,
      String type,
      double value,
      double minimum,
      double maximum,
      long revision) {
    public Value {
      position = Objects.requireNonNull(position, "position").immutable();
      Objects.requireNonNull(partId, "partId");
      Objects.requireNonNull(type, "type");
      if (partId.length() > 128
          || type.isBlank()
          || !Double.isFinite(value)
          || !Double.isFinite(minimum)
          || !Double.isFinite(maximum)
          || minimum > maximum
          || value < minimum
          || value > maximum
          || revision < 0) {
        throw new IllegalArgumentException("Invalid smart interface query value");
      }
    }
  }

  public record Snapshot(BlockPos controller, ResourceLocation machine, long definitionEpoch, List<Value> values) {
    public Snapshot {
      controller = Objects.requireNonNull(controller, "controller").immutable();
      Objects.requireNonNull(machine, "machine");
      values = List.copyOf(values);
      if (definitionEpoch < 0 || values.size() > MachineDefinition.MAX_PARTS) {
        throw new IllegalArgumentException("Invalid smart interface query snapshot");
      }
    }

    public List<Value> valuesFor(String type) {
      Objects.requireNonNull(type, "type");

      return values.stream().filter(value -> value.type().equals(type)).toList();
    }
  }

  private SmartInterfaceApi() {
  }

  /**
   * Returns empty unless the loaded controller has a current, fully confirmed
   * structure and every declared smart interface binding is readable.
   */
  public static Optional<Snapshot> inspect(ServerLevel level, BlockPos controllerPosition) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(controllerPosition, "controllerPosition");
    ServerThreadGuard.require(level, "Smart interface API query");
    if (!level.hasChunkAt(controllerPosition)
        || !(level.getBlockEntity(controllerPosition) instanceof StructureService.StructureSubject controller)
        || controller.structureRemoved()
        || !controller.structurePosition().equals(controllerPosition)
        || controller.structureLevel() != level
        || !controller.structureSchemaSupported()) {
      return Optional.empty();
    }
    var registration = controller.structureRegistration();
    if (registration == null || registration.controller != controller || !registration.canCommit(level)) {
      return Optional.empty();
    }
    MachineDefinition definition = registration.pattern.definition;
    if (definition != StructureService.definition(controller.structureMachineId())) {
      return Optional.empty();
    }
    var values = new ArrayList<Value>();
    for (int index : registration.interfaceParts()) {
      BlockPos position = registration.pattern.position(index);
      String type = registration.interfaceType(index);
      var specification = definition.interfaceTypes().get(type);
      if (type.isBlank()
          || specification == null
          || !level.hasChunkAt(position)
          || !(level.getBlockEntity(position) instanceof SmartInterfaceBlockEntity smart)) {
        return Optional.empty();
      }
      var binding = smart.read(controller, type);
      if (binding.isEmpty()) {
        return Optional.empty();
      }
      values.add(
          new Value(
              position,
              definition.parts().get(index).id(),
              type,
              binding.orElseThrow().value(),
              specification.minimum(),
              specification.maximum(),
              binding.orElseThrow().revision()));
    }

    return Optional.of(new Snapshot(controller.structurePosition(), definition.id(), registration.epoch(), values));
  }
}
