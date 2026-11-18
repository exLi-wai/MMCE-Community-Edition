package com.lw.modularmachine.integration.neovitae;

import com.breakinblocks.neovitae.api.soul.AnimaTicket;
import com.breakinblocks.neovitae.common.datacomponent.Anima;
import com.lw.modularmachine.storage.LongEnergyBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LifeEssenceTransferTest {
  @Test
  void emptyNetworkDoesNotChangeInputBuffer() {
    var anima = network(0);
    var buffer = buffer(true, 20);
    try (var lease = buffer.acquire()) {
      assertEquals(0, LifeEssenceHatchBlockEntity.pullFromNetwork(anima, 30, lease));
      lease.resolve(true);
    }
    assertEquals(0, anima.getCurrentEV());
    assertEquals(20, buffer.stored());
  }

  @Test
  void inputCanReceivePartOfTheRequestedAmount() {
    var anima = network(15);
    var buffer = buffer(true, 20);
    try (var lease = buffer.acquire()) {
      assertEquals(15, LifeEssenceHatchBlockEntity.pullFromNetwork(anima, 30, lease));
      lease.resolve(true);
    }
    assertEquals(0, anima.getCurrentEV());
    assertEquals(35, buffer.stored());
  }

  @Test
  void outputKeepsTheAmountTheNetworkCannotAccept() {
    var anima = network(95);
    var buffer = buffer(false, 20);
    try (var lease = buffer.acquire()) {
      assertEquals(5, LifeEssenceHatchBlockEntity.pushToNetwork(anima, 20, 100, false, lease));
      lease.resolve(true);
    }
    try (var lease = buffer.acquire()) {
      assertEquals(0, LifeEssenceHatchBlockEntity.pushToNetwork(anima, 15, 100, false, lease));
      lease.resolve(true);
    }
    assertEquals(100, anima.getCurrentEV());
    assertEquals(15, buffer.stored());
  }

  @Test
  void inputBufferCanReturnItsStockToTheBoundNetwork() {
    var anima = network(60);
    var buffer = buffer(true, 20);
    try (var lease = buffer.acquire()) {
      assertEquals(20, LifeEssenceHatchBlockEntity.pushToNetwork(anima, 20, 100, true, lease));
      lease.resolve(true);
    }
    assertEquals(80, anima.getCurrentEV());
    assertEquals(0, buffer.stored());
  }

  private static Anima network(int amount) {
    var anima = Anima.newEmpty(UUID.randomUUID(), null);
    anima.set(AnimaTicket.create(amount), 100);
    return anima;
  }

  private static LongEnergyBuffer buffer(boolean input, long stock) {
    var buffer = new LongEnergyBuffer(100, 100, input, () -> 0, () -> {
    });
    buffer.load(stock);
    return buffer;
  }
}
