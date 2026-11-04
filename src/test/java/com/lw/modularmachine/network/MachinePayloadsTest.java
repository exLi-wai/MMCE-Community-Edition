package com.lw.modularmachine.network;

import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MachinePayloadsTest {
  private static MachinePayloads.ControllerStatusPayload payload() {
    return new MachinePayloads.ControllerStatusPayload(
        MachinePayloads.PROTOCOL_VERSION,
        new BlockPos(1, 2, 3),
        ResourceLocation.fromNamespaceAndPath("mmcece", "processor"),
        Long.MAX_VALUE,
        "CONFIRMED",
        "NONE",
        "north",
        2,
        true,
        Long.MAX_VALUE,
        4,
        "ADVANCED",
        "mmcece:recipe",
        "RUNNING",
        7,
        20,
        0,
        0,
        List.of(new MachinePayloads.Shortage("minecraft:dirt", 4, 1, 3)));
  }

  @Test
  void rejectsInvalidVersionProgressAndBoundaries() {
    var valid = payload();
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachinePayloads.ControllerStatusPayload(
            3,
            valid.position(),
            valid.machineId(),
            valid.definitionEpoch(),
            valid.structureState(),
            valid.structureFailure(),
            valid.front(),
            valid.roll(),
            valid.productionAllowed(),
            valid.successfulTransfers(),
            valid.bindingRebuilds(),
            valid.recipeStatus(),
            valid.recipeId(),
            valid.runState(),
            valid.progress(),
            valid.duration(),
            0,
            0,
            valid.shortages()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachinePayloads.ControllerStatusPayload(
            MachinePayloads.PROTOCOL_VERSION,
            valid.position(),
            valid.machineId(),
            valid.definitionEpoch(),
            valid.structureState(),
            valid.structureFailure(),
            valid.front(),
            valid.roll(),
            valid.productionAllowed(),
            valid.successfulTransfers(),
            valid.bindingRebuilds(),
            valid.recipeStatus(),
            valid.recipeId(),
            valid.runState(),
            21,
            20,
            0,
            0,
            valid.shortages()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachinePayloads.Shortage("x".repeat(MachinePayloads.MAX_TEXT_LENGTH + 1), 2, 0, 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachinePayloads.ControllerStatusPayload(
            MachinePayloads.PROTOCOL_VERSION,
            valid.position(),
            valid.machineId(),
            valid.definitionEpoch(),
            valid.structureState(),
            valid.structureFailure(),
            valid.front(),
            valid.roll(),
            valid.productionAllowed(),
            valid.successfulTransfers(),
            valid.bindingRebuilds(),
            valid.recipeStatus(),
            valid.recipeId(),
            valid.runState(),
            valid.progress(),
            valid.duration(),
            0,
            0,
            IntStream.range(0, MachinePayloads.MAX_SHORTAGES + 1)
                .mapToObj(index -> new MachinePayloads.Shortage("item" + index, 1, 0, 1)).toList()));
  }

  @Test
  void preservesLongAndBoundedStatusFields() {
    var value = payload();
    assertEquals(Long.MAX_VALUE, value.definitionEpoch());
    assertEquals(Long.MAX_VALUE, value.successfulTransfers());
    assertEquals(1, value.shortages().size());
    assertEquals(3, value.shortages().getFirst().missing());
  }

  @Test
  void resourceActionsAreVersionedAndPositionBound() {
    var action = new MachinePayloads.ResourcePortActionPayload(
        MachinePayloads.PROTOCOL_VERSION,
        new BlockPos(7, 8, 9),
        InteractionHand.MAIN_HAND);
    assertEquals(new BlockPos(7, 8, 9), action.position());
    assertEquals(InteractionHand.MAIN_HAND, action.hand());
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachinePayloads.ResourcePortActionPayload(3, action.position(), action.hand()));
  }

}
