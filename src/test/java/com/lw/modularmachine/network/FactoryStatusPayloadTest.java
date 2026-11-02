package com.lw.modularmachine.network;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactoryStatusPayloadTest {
  private static final ResourceLocation MACHINE = ResourceLocation.fromNamespaceAndPath("mmcece", "test_machine");

  @Test
  void acceptsEmptyAndRunningRunSummaries() {
    var idle = thread("idle", new UUID(0, 0), "", 0, 0);
    var running = thread("running", UUID.randomUUID(), "RUNNING", 3, 10);
    var payload = new MachinePayloads.FactoryStatusPayload(
        MachinePayloads.PROTOCOL_VERSION,
        new BlockPos(4, 5, 6),
        1,
        true,
        "CONFIRMED",
        "NONE",
        List.of(idle, running));

    assertEquals(2, payload.threads().size());
    assertEquals(3, payload.threads().get(1).progress());
  }

  @Test
  void rejectsProgressOutsideDuration() {
    assertThrows(IllegalArgumentException.class, () -> thread("broken", UUID.randomUUID(), "RUNNING", 11, 10));
  }

  @Test
  void acceptsIntSizedParallelLimit() {
    var thread = new MachinePayloads.FactoryStatusThread(
        "wide",
        MACHINE,
        null,
        true,
        0,
        Integer.MAX_VALUE,
        Long.MAX_VALUE,
        1,
        1,
        1,
        1,
        1,
        1,
        "IDLE",
        "NONE",
        0,
        new UUID(0, 0),
        "",
        0,
        0);
    assertEquals(Integer.MAX_VALUE, thread.parallelLimit());
    assertEquals(Long.MAX_VALUE, thread.upgradeRevision());
  }

  private static MachinePayloads.FactoryStatusThread thread(String id, UUID runId, String runState, int progress, int duration) {
    return new MachinePayloads.FactoryStatusThread(
        id,
        MACHINE,
        null,
        true,
        0,
        1,
        0,
        1,
        1,
        1,
        1,
        1,
        1,
        "RUNNING",
        "NONE",
        0,
        runId,
        runState,
        progress,
        duration);
  }
}
