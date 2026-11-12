package com.lw.modularmachine.storage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LongEnergyBufferTest {
  @Test
  void rejectedWriteRunsGuardBeforeChangingStorage() {
    var input = new LongEnergyBuffer(2048, 128, true, () -> 0, () -> {
      throw new IllegalStateException("wrong thread");
    }, () -> {
    });
    assertEquals(128, input.receiveEnergy(128, true));
    assertThrows(IllegalStateException.class, () -> input.receiveEnergy(128, false));
    assertEquals(0, input.stored());
  }

  @Test
  void simulationIsLosslessAndDoesNotSpendTickBudget() {
    AtomicLong tick = new AtomicLong();
    AtomicInteger changed = new AtomicInteger();
    var input = new LongEnergyBuffer(2048, 128, true, tick::get, changed::incrementAndGet);
    assertEquals(128, input.receiveEnergy(256, true));
    assertEquals(128, input.receiveEnergy(256, true));
    assertEquals(0, input.stored());
    assertEquals(0, changed.get());
    assertEquals(128, input.receiveEnergy(256, false));
    assertEquals(0, input.receiveEnergy(128, false));
    tick.incrementAndGet();
    assertEquals(128, input.receiveEnergy(128, false));
    assertEquals(256, input.stored());
  }

  @Test
  void longCapacityDoesNotNarrowToNegativeFeValues() {
    var energy = new LongEnergyBuffer(Long.MAX_VALUE, Long.MAX_VALUE, false, () -> 0, () -> {
    });
    energy.load(Long.MAX_VALUE);
    assertEquals(Long.MAX_VALUE, energy.stored());
    assertEquals(Integer.MAX_VALUE, energy.getEnergyStored());
    assertEquals(Integer.MAX_VALUE, energy.getMaxEnergyStored());
    assertEquals(Integer.MAX_VALUE, energy.extractEnergy(Integer.MAX_VALUE, true));
    assertEquals(Long.MAX_VALUE, energy.stored());
  }

  @Test
  void blockedOutputChangesNeitherBuffer() {
    var input = new LongEnergyBuffer(2048, 128, true, () -> 0, () -> {
    });
    var output = new LongEnergyBuffer(64, 128, false, () -> 0, () -> {
    });
    input.load(128);
    assertFalse(input.transferExactTo(output, 128));
    assertEquals(128, input.stored());
    assertEquals(0, output.stored());
  }

  @Test
  void exactTransferConservesEnergyAndLimitsCumulativeIo() {
    AtomicLong tick = new AtomicLong();
    var input = new LongEnergyBuffer(2048, 128, true, tick::get, () -> {
    });
    var output = new LongEnergyBuffer(2048, 128, false, tick::get, () -> {
    });
    input.load(512);
    assertTrue(input.transferExactTo(output, 128));
    assertFalse(input.transferExactTo(output, 1));
    assertEquals(512, input.stored() + output.stored());
    assertEquals(128, output.extractEnergy(256, false));
    assertEquals(0, output.extractEnergy(1, false));
    tick.incrementAndGet();
    assertTrue(input.transferExactTo(output, 128));
    assertEquals(256, input.stored());
    assertEquals(128, output.stored());
  }

  @Test
  void externalDirectionAndInvalidAmountsAreRejected() {
    var input = new LongEnergyBuffer(2048, 128, true, () -> 0, () -> {
    });
    var output = new LongEnergyBuffer(2048, 128, false, () -> 0, () -> {
    });
    input.load(128);
    output.load(128);
    assertEquals(0, input.extractEnergy(10, false));
    assertEquals(0, output.receiveEnergy(10, false));
    assertEquals(0, input.receiveEnergy(-1, false));
    assertFalse(input.transferExactTo(input, 1));
    assertFalse(output.transferExactTo(input, 1));
  }

  @Test
  void energyLeaseRollsBackAndChargesTheCurrentTickExtractionBudget() {
    AtomicLong tick = new AtomicLong();
    var input = new LongEnergyBuffer(1024, 8, true, tick::get, () -> {
    });
    input.load(10);
    var rollback = input.acquire();
    assertTrue(rollback.extractExact(8));
    assertFalse(rollback.extractExact(1));
    assertThrows(IllegalStateException.class, input::stored);
    rollback.resolve(false);
    assertEquals(10, input.stored());
    var commit = input.acquire();
    assertTrue(commit.extractExact(8));
    commit.resolve(true);
    assertEquals(2, input.stored());
    assertEquals(0, input.extractableNow());
    tick.incrementAndGet();
    assertEquals(2, input.extractableNow());
  }
}
