package com.lw.modularmachine.machine.recipe.resource.keyed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning.Status;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource.ReplenishmentProof;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class KeyedRecipeAllocationTest {
  private static final ResourceLocation AIR = ResourceLocation.fromNamespaceAndPath("test", "air");
  private static final ResourceLocation FIRE = ResourceLocation.fromNamespaceAndPath("test", "fire");

  @Test
  void availableMatchingSupplyTakesPrecedenceOverAnEmptyOrWrongAspectPipe() {
    var pipe = new Port(64, Map.of(FIRE, 64L));
    var bus = new Port(64, Map.of(AIR, 16L));
    var allocation = allocation(List.of(Map.entry(AIR, 16L)), List.of(pipe, bus));

    assertEquals(Status.READY, finishSliced(allocation));
    assertTrue(allocation.waiting());
    assertEquals(Status.READY, finishSliced(allocation.recheck(new ReplenishmentProof())));
    assertEquals(0, pipe.requested + bus.requested);
    assertEquals(Status.READY, allocation.advanceRequests(1, () -> true).status());
    assertEquals(0, pipe.requested);
    assertEquals(16, bus.requested);
  }

  @Test
  void confirmedCapacityIsUsedBeforeFallbackAndEachHintCostsWork() {
    var emptyPipe = new Port(64, Map.of());
    var bus = new Port(64, Map.of(AIR, 3L));
    var allocation = allocation(List.of(Map.entry(AIR, 8L)), List.of(emptyPipe, bus));

    assertEquals(Status.READY, finishSliced(allocation));
    assertEquals(Status.READY, finishSliced(allocation.recheck(new ReplenishmentProof())));
    assertEquals(new RecipeResourcePlanning.Progress(Status.PENDING, 1), allocation.advanceRequests(1, () -> true));
    assertEquals(0, emptyPipe.requested);
    assertEquals(3, bus.requested);
    assertEquals(new RecipeResourcePlanning.Progress(Status.READY, 1), allocation.advanceRequests(1, () -> true));
    assertEquals(5, emptyPipe.requested);
  }

  @Test
  void movesFlexibleSupplyToLeaveRoomForTheOnlySourceOfAnotherAspect() {
    var shared = new Port(8, Map.of(AIR, 8L, FIRE, 8L));
    var airOnly = new Port(8, Map.of(AIR, 8L));
    var allocation = allocation(List.of(Map.entry(AIR, 8L), Map.entry(FIRE, 8L)), List.of(shared, airOnly));

    assertEquals(Status.READY, finishSliced(allocation));
    assertEquals(Status.READY, finishSliced(allocation.recheck(new ReplenishmentProof())));
    assertEquals(Status.READY, allocation.advanceRequests(2, () -> true).status());
    assertEquals(Map.of(FIRE, 8L), shared.demands);
    assertEquals(Map.of(AIR, 8L), airOnly.demands);
  }

  @Test
  void fallbackForAnEarlierAspectDoesNotConsumeConfirmedCapacityForAnother() {
    var bus = new Port(8, Map.of(FIRE, 8L));
    var emptyPipe = new Port(8, Map.of());
    var allocation = allocation(List.of(Map.entry(AIR, 8L), Map.entry(FIRE, 8L)), List.of(bus, emptyPipe));

    assertEquals(Status.READY, finishSliced(allocation));
    assertEquals(Status.READY, finishSliced(allocation.recheck(new ReplenishmentProof())));
    assertEquals(Status.PENDING, allocation.advanceRequests(1, () -> true).status());
    assertEquals(Map.of(FIRE, 8L), bus.demands);
    assertTrue(emptyPipe.demands.isEmpty());
    assertEquals(Status.READY, allocation.advanceRequests(1, () -> true).status());
    assertEquals(Map.of(AIR, 8L), emptyPipe.demands);
  }

  @Test
  void confirmedAndFallbackOnTheSamePortBecomeOneDemandTarget() {
    var port = new Port(8, Map.of(AIR, 3L));
    var allocation = allocation(List.of(Map.entry(AIR, 8L)), List.of(port));

    assertEquals(Status.READY, finishSliced(allocation));
    assertEquals(Status.READY, finishSliced(allocation.recheck(new ReplenishmentProof())));
    assertEquals(Status.READY, allocation.advanceRequests(1, () -> true).status());
    assertEquals(Map.of(AIR, 8L), port.demands);
  }

  @Test
  void mergedRequestRechecksItsConfirmedShareAndKeepsOnlyThePlannedFallback() {
    var supply = new HashMap<>(Map.of(AIR, 3L));
    var port = new Port(8, supply);
    var allocation = allocation(List.of(Map.entry(AIR, 8L)), List.of(port));
    assertEquals(Status.READY, finishSliced(allocation));

    supply.clear();

    assertEquals(Status.READY, finishSliced(allocation.recheck(new ReplenishmentProof())));
    assertEquals(Status.READY, allocation.advanceRequests(1, () -> true).status());
    assertEquals(Map.of(AIR, 5L), port.demands);
  }

  @Test
  void aSourceThatEmptiesWhileWaitingForPublicationDoesNotRegisterAStaleHint() {
    var pipe = new Port(64, Map.of());
    var stock = new HashMap<>(Map.of(AIR, 16L));
    var bus = new Port(64, stock);
    var allocation = allocation(List.of(Map.entry(AIR, 16L)), List.of(pipe, bus));

    assertEquals(Status.READY, finishSliced(allocation));
    stock.clear();
    assertEquals(Status.READY, finishSliced(allocation.recheck(new ReplenishmentProof())));
    assertEquals(Status.READY, allocation.advanceRequests(1, () -> true).status());
    assertFalse(allocation.requested());
    assertEquals(0, pipe.requested + bus.requested);
  }

  @Test
  void stockArrivingInAnyInputHatchReducesTheRequestBeforePublication() {
    var bus = new Port(16, Map.of(AIR, 16L));
    var other = new Port(16, Map.of());
    var allocation = allocation(List.of(Map.entry(AIR, 8L)), List.of(bus, other));
    assertEquals(Status.READY, finishSliced(allocation));
    var oldProof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(allocation.recheck(oldProof)));

    other.stock.move(AIR, 6, false);

    assertFalse(oldProof.current());
    assertEquals(Status.REJECTED, allocation.advanceRequests(1, oldProof::current).status());
    var proof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(allocation.recheck(proof)));
    assertEquals(Status.READY, allocation.advanceRequests(1, proof::current).status());
    assertEquals(Map.of(AIR, 2L), bus.demands);
  }

  @Test
  void aLaterHintCountsEarlierOutstandingRequestsAndNewStockOnlyOnce() {
    var first = new Port(4, Map.of(AIR, 4L));
    var second = new Port(4, Map.of(AIR, 4L));
    var other = new Port(8, Map.of());
    var allocation = allocation(List.of(Map.entry(AIR, 8L)), List.of(first, second, other));
    assertEquals(Status.READY, finishSliced(allocation));
    var oldProof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(allocation.recheck(oldProof)));
    assertEquals(Status.PENDING, allocation.advanceRequests(1, oldProof::current).status());

    first.stock.move(AIR, 2, false);
    other.stock.move(AIR, 4, false);

    assertFalse(oldProof.current());
    var proof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(allocation.recheck(proof)));
    assertEquals(Status.READY, allocation.advanceRequests(1, proof::current).status());
    assertEquals(Map.of(AIR, 4L), first.demands);
    assertTrue(second.demands.isEmpty());
  }

  @Test
  void aNewSearchCountsPartialStockAndOutstandingRequestsFromEarlierSearches() {
    var first = new Port(4, Map.of(AIR, 4L));
    var second = new Port(4, Map.of(AIR, 4L));
    var other = new Port(8, Map.of(AIR, 8L));
    var claims = List.of(Map.entry(AIR, 8L));
    publish(allocation(claims, List.of(first, second)));
    first.stock.move(AIR, 2, false);

    var next = allocation(claims, List.of(other, first, second));
    assertEquals(Status.REJECTED, finishSliced(next));
    assertFalse(next.requested());
    assertTrue(other.demands.isEmpty());

    second.demands.clear();
    publish(allocation(claims, List.of(other, first, second)));
    assertEquals(Map.of(AIR, 4L), other.demands);
  }

  @Test
  void aNewSearchDoesNotRequestTheSameDeficitFromARecoveredSource() {
    var first = new Port(16, Map.of(AIR, 8L));
    var recoveredSupply = new HashMap<ResourceLocation, Long>();
    var second = new Port(16, recoveredSupply);
    var claims = List.of(Map.entry(AIR, 8L));
    var ports = List.<KeyedResourcePort>of(second, first);
    publish(allocation(claims, ports));
    assertEquals(8, first.outstandingRequest(AIR));

    recoveredSupply.put(AIR, 8L);
    var next = allocation(claims, ports);
    assertEquals(Status.REJECTED, finishSliced(next));

    assertFalse(next.requested());
    assertEquals(8, first.outstandingRequest(AIR) + second.outstandingRequest(AIR));

    first.demands.clear();
    publish(allocation(claims, ports));
    assertEquals(8, second.outstandingRequest(AIR));
  }

  @Test
  void anInterleavedRequestInvalidatesAnotherSearchThatObservedZeroOutstanding() {
    interleaveRequests(0);
  }

  @Test
  void anInterleavedRequestInvalidatesAnotherSearchThatObservedPositiveOutstanding() {
    interleaveRequests(3);
  }

  @Test
  void anInterleavedRequestAtAnotherHatchReducesTheRemainingPublication() {
    var first = new Port(4, Map.of(AIR, 4L));
    var second = new Port(8, Map.of(AIR, 8L));
    var original = allocation(List.of(Map.entry(AIR, 8L)), List.of(first, second));
    var proof = verifyRequests(original);
    assertEquals(Status.PENDING, original.advanceRequests(1, proof::current).status());
    assertTrue(proof.liveCurrent());

    publish(allocation(List.of(Map.entry(AIR, 4L)), List.of(second)));

    assertFalse(proof.current());
    assertEquals(Status.REJECTED, original.advanceRequests(1, proof::current).status());
    var refreshed = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(original.recheck(refreshed)));
    assertTrue(refreshed.liveCurrent());
    assertEquals(Status.READY, original.advanceRequests(1, refreshed::current).status());
    assertEquals(8, first.outstandingRequest(AIR) + second.outstandingRequest(AIR));
  }

  private static void interleaveRequests(long initial) {
    var bus = new Port(16, Map.of(AIR, 16L));
    var ports = List.<KeyedResourcePort>of(bus);
    if (initial > 0) {
      publish(allocation(List.of(Map.entry(AIR, initial)), ports));
    }
    var claims = List.of(Map.entry(AIR, 8L));
    var first = allocation(claims, ports);
    var second = allocation(claims, ports);
    var firstProof = verifyRequests(first);
    var secondProof = verifyRequests(second);

    assertEquals(Status.READY, first.advanceRequests(1, firstProof::current).status());
    assertTrue(firstProof.current());
    if (secondProof.liveCurrent()) {
      second.advanceRequests(1, secondProof::current);
    }
    assertEquals(8, bus.outstandingRequest(AIR));
    assertFalse(second.requested());
    assertFalse(secondProof.current());

    var refreshed = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(second.recheck(refreshed)));
    assertTrue(refreshed.liveCurrent());
    assertEquals(Status.READY, second.advanceRequests(1, refreshed::current).status());
    assertEquals(8, bus.outstandingRequest(AIR));
  }

  @Test
  void aNewSearchExtendsTheSameHatchRequestOnlyByTheUncoveredAmount() {
    var bus = new Port(16, Map.of(AIR, 16L));
    var ports = List.<KeyedResourcePort>of(bus);
    publish(allocation(List.of(Map.entry(AIR, 4L)), ports));
    bus.stock.move(AIR, 2, false);

    publish(allocation(List.of(Map.entry(AIR, 7L)), ports));

    assertEquals(7, bus.requested);
    assertEquals(5, bus.outstandingRequest(AIR));
  }

  @Test
  void aHatchWithAllSpacePromisedLeavesTheOtherSourceEligible() {
    var occupied = new Port(8, Map.of(FIRE, 8L, AIR, 8L));
    var free = new Port(8, Map.of(AIR, 8L));
    publish(allocation(List.of(Map.entry(FIRE, 8L)), List.of(occupied)));

    publish(allocation(List.of(Map.entry(AIR, 8L)), List.of(occupied, free)));

    assertEquals(Map.of(FIRE, 8L), occupied.demands);
    assertEquals(Map.of(AIR, 8L), free.demands);
  }

  @Test
  void aHatchWithAllSpacePromisedStillCoversPartOfTheSameDeficit() {
    var occupied = new Port(4, Map.of(AIR, 4L));
    var free = new Port(4, Map.of(AIR, 4L));
    publish(allocation(List.of(Map.entry(AIR, 4L)), List.of(occupied)));

    publish(allocation(List.of(Map.entry(AIR, 8L)), List.of(occupied, free)));

    assertEquals(Map.of(AIR, 4L), occupied.demands);
    assertEquals(Map.of(AIR, 4L), free.demands);
  }

  @Test
  void aPreviouslyCountedRequestMustStillBeOutstandingAtPublication() {
    var first = new Port(4, Map.of(AIR, 4L));
    var second = new Port(8, Map.of(AIR, 8L));
    publish(allocation(List.of(Map.entry(AIR, 4L)), List.of(first)));
    var next = allocation(List.of(Map.entry(AIR, 8L)), List.of(second, first));
    assertEquals(Status.READY, finishSliced(next));
    var proof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(next.recheck(proof)));
    assertTrue(proof.liveCurrent());

    first.gameTime = 61;

    assertFalse(proof.current());
    assertFalse(proof.liveCurrent());
    assertEquals(Status.REJECTED, next.advanceRequests(1, proof::liveCurrent).status());
    assertTrue(second.demands.isEmpty());
  }

  @Test
  void reconnectingAnOldRequestRequiresARecheckBeforePublishingElsewhere() {
    var disconnected = new Port(8, Map.of(AIR, 8L));
    var backup = new Port(8, Map.of(AIR, 8L));
    var claims = List.of(Map.entry(AIR, 8L));
    publish(allocation(claims, List.of(disconnected)));
    disconnected.connected = false;
    var next = allocation(claims, List.of(disconnected, backup));
    var proof = verifyRequests(next);

    disconnected.connected = true;

    assertFalse(proof.liveCurrent());
    assertEquals(Status.REJECTED, next.advanceRequests(1, proof::liveCurrent).status());
    assertTrue(backup.demands.isEmpty());
  }

  @Test
  void idlingAndBudgetSlicesDoNotScanAllPortsOrRepeatCapture() {
    var visits = new AtomicInteger();
    var port = new Port(0, Map.of());
    var positions = Collections.nCopies(40, port).stream().map(value -> {
      visits.incrementAndGet();
      return value;
    }).iterator();
    var allocation = new KeyedRecipeExecution.Allocation(List.of(Map.entry(AIR, 8L)), positions, false, true, false);

    assertEquals(new RecipeResourcePlanning.Progress(Status.PENDING, 0), allocation.advance(0));
    assertEquals(0, visits.get());
    for (int visited = 1; visited <= 40; visited++) {
      assertEquals(new RecipeResourcePlanning.Progress(Status.PENDING, 1), allocation.advance(1));
      assertEquals(visited, visits.get());
    }
    assertEquals(Status.REJECTED, finishSliced(allocation));
    assertFalse(allocation.waiting());
    assertEquals(1, port.probes);
  }

  @Test
  void eachAllocatedOutputIsRecheckedUnderBudgetAndSharesPhysicalRoom() {
    var port = new Port(16, Map.of());
    var allocation = new KeyedRecipeExecution.Allocation(
        List.of(Map.entry(AIR, 8L), Map.entry(FIRE, 8L)),
        List.<KeyedResourcePort>of(port).iterator(),
        true,
        false,
        false);

    assertEquals(Status.READY, finishSliced(allocation));
    port.stock.move(FIRE, 4, false);
    var recheck = allocation.recheck(new ReplenishmentProof());
    assertEquals(new RecipeResourcePlanning.Progress(Status.PENDING, 1), recheck.advance(1));
    assertEquals(new RecipeResourcePlanning.Progress(Status.REJECTED, 1), recheck.advance(1));
  }

  @Test
  void aCompletedOutputProofBlocksFirstPublicationAfterTheHatchFills() {
    var output = new Port(8, Map.of());
    var outputPlan = new KeyedRecipeExecution.Allocation(
        List.of(Map.entry(FIRE, 8L)),
        List.of(output).iterator(),
        true,
        false,
        false);
    var input = new Port(8, Map.of(AIR, 8L));
    var inputPlan = allocation(List.of(Map.entry(AIR, 8L)), List.of(input));
    assertEquals(Status.READY, finishSliced(outputPlan));
    assertEquals(Status.READY, finishSliced(inputPlan));
    var proof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(outputPlan.recheck(proof)));
    assertEquals(Status.READY, finishSliced(inputPlan.recheck(proof)));

    output.stock.move(AIR, 1, false);

    assertFalse(proof.current());
    assertEquals(Status.REJECTED, inputPlan.advanceRequests(1, proof::current).status());
    assertFalse(inputPlan.requested());
    assertEquals(Status.REJECTED, finishSliced(outputPlan.recheck(new ReplenishmentProof())));
  }

  @Test
  void outputProofSurvivesBudgetSlicesButStopsLaterHintsAfterOutputChanges() {
    var firstOutput = new Port(4, Map.of());
    var secondOutput = new Port(4, Map.of());
    var outputPlan = new KeyedRecipeExecution.Allocation(
        List.of(Map.entry(FIRE, 8L)),
        List.of(firstOutput, secondOutput).iterator(),
        true,
        false,
        false);
    var firstInput = new Port(4, Map.of(AIR, 4L));
    var secondInput = new Port(4, Map.of(AIR, 4L));
    var inputPlan = allocation(List.of(Map.entry(AIR, 8L)), List.of(firstInput, secondInput));
    assertEquals(Status.READY, finishSliced(outputPlan));
    assertEquals(Status.READY, finishSliced(inputPlan));
    var proof = new ReplenishmentProof();
    var recheck = outputPlan.recheck(proof);
    assertEquals(new RecipeResourcePlanning.Progress(Status.PENDING, 1), recheck.advance(1));
    assertEquals(new RecipeResourcePlanning.Progress(Status.READY, 1), recheck.advance(1));
    assertEquals(Status.READY, finishSliced(inputPlan.recheck(proof)));
    assertEquals(Status.PENDING, inputPlan.advanceRequests(1, proof::current).status());

    firstOutput.stock.move(AIR, 1, false);

    assertEquals(Status.REJECTED, inputPlan.advanceRequests(1, proof::current).status());
    assertEquals(Map.of(AIR, 4L), firstInput.demands);
    assertTrue(secondInput.demands.isEmpty());

    firstOutput.stock.move(AIR, -1, false);
    var newProof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(outputPlan.recheck(newProof)));
    assertEquals(Status.READY, finishSliced(inputPlan.recheck(newProof)));
    assertEquals(Status.READY, inputPlan.advanceRequests(1, newProof::current).status());
    assertEquals(Map.of(AIR, 4L), secondInput.demands);
  }

  @Test
  void losingVerifiedStockStopsLaterHintsUntilInputsAreRechecked() {
    var stored = new Port(4, Map.of());
    stored.stock.move(AIR, 4, false);
    var first = new Port(4, Map.of(FIRE, 4L));
    var second = new Port(4, Map.of(FIRE, 4L));
    var allocation = allocation(List.of(Map.entry(AIR, 4L), Map.entry(FIRE, 8L)), List.of(stored, first, second));
    assertEquals(Status.READY, finishSliced(allocation));
    var proof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(allocation.recheck(proof)));
    assertEquals(Status.PENDING, allocation.advanceRequests(1, proof::current).status());

    stored.stock.move(AIR, -1, false);

    assertEquals(Status.REJECTED, allocation.advanceRequests(1, proof::current).status());
    assertEquals(Map.of(FIRE, 4L), first.demands);
    assertTrue(second.demands.isEmpty());
    assertEquals(Status.REJECTED, finishSliced(allocation.recheck(new ReplenishmentProof())));

    stored.stock.move(AIR, 1, false);

    var newProof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(allocation.recheck(newProof)));
    assertEquals(Status.READY, allocation.advanceRequests(1, newProof::current).status());
    assertEquals(Map.of(FIRE, 4L), second.demands);
  }

  @Test
  void liveInputRechecksKeysAndTheirSharedLimitBeforePublication() {
    var network = new Port(8, Map.of());
    network.watchStock = false;
    network.stock.move(AIR, 4, false);
    network.stock.move(FIRE, 4, false);
    var pipe = new Port(4, Map.of(FIRE, 4L));
    var allocation = allocation(List.of(Map.entry(AIR, 4L), Map.entry(FIRE, 8L)), List.of(network, pipe));
    assertEquals(Status.READY, finishSliced(allocation));
    var proof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(allocation.recheck(proof)));
    assertTrue(proof.liveCurrent());

    network.inputLimit = 7;
    assertFalse(proof.liveCurrent());
    network.inputLimit = 8;
    network.stock.move(AIR, -1, false);

    assertEquals(Status.REJECTED, allocation.advanceRequests(1, proof::liveCurrent).status());
    assertTrue(pipe.demands.isEmpty());
  }

  private static KeyedRecipeExecution.Allocation allocation(List<Map.Entry<ResourceLocation, Long>> claims, List<KeyedResourcePort> ports) {
    return new KeyedRecipeExecution.Allocation(claims, ports.iterator(), false, true, false);
  }

  private static ReplenishmentProof verifyRequests(KeyedRecipeExecution.Allocation allocation) {
    assertEquals(Status.READY, finishSliced(allocation));
    var proof = new ReplenishmentProof();
    assertEquals(Status.READY, finishSliced(allocation.recheck(proof)));
    assertTrue(proof.liveCurrent());
    return proof;
  }

  private static void publish(KeyedRecipeExecution.Allocation allocation) {
    var proof = verifyRequests(allocation);
    RecipeResourcePlanning.Progress progress;
    do {
      assertTrue(proof.liveCurrent());
      progress = allocation.advanceRequests(1, proof::current);
      assertEquals(1, progress.workUsed());
    } while (progress.status() == Status.PENDING);
    assertEquals(Status.READY, progress.status());
  }

  private static Status finishSliced(RecipeResourcePlanning planning) {
    for (int step = 0; step < 200; step++) {
      var progress = planning.advance(1);
      assertEquals(1, progress.workUsed());
      if (progress.status() != Status.PENDING) {
        return progress.status();
      }
    }
    throw new AssertionError("Keyed allocation did not resume");
  }

  private static final class Port implements KeyedResourcePort {
    private final Map<ResourceLocation, Long> supply;
    private final Map<ResourceLocation, Long> demands = new HashMap<>();
    private final KeyedStock stock;
    private final KeyedRequestObservers requestObservers = new KeyedRequestObservers();
    private boolean watchStock = true;
    private boolean connected = true;
    private long gameTime;
    private long inputLimit = Long.MAX_VALUE;
    private long requested;
    private int probes;

    private Port(long room, Map<ResourceLocation, Long> supply) {
      this.supply = supply;
      stock = new KeyedStock(room);
    }

    @Override
    public Object resourceAddress() {
      return this;
    }

    @Override
    public long available(ResourceLocation key) {
      probes++;
      return stock.amount(key);
    }

    @Override
    public long space(ResourceLocation key) {
      return stock.space();
    }

    @Override
    public long totalSpace() {
      return stock.space();
    }

    @Override
    public long totalAvailable() {
      return inputLimit;
    }

    @Override
    public boolean watchInput(ReplenishmentProof proof) {
      if (watchStock) {
        stock.watchInput(proof);
      }
      return watchStock;
    }

    @Override
    public void watchOutput(ReplenishmentProof proof) {
      stock.watchOutput(proof);
    }

    @Override
    public boolean watchInputAmount(ReplenishmentProof proof) {
      if (!watchInput(proof)) {
        return false;
      }
      stock.watchOutput(proof);
      return true;
    }

    @Override
    public long requestSpace() {
      return connected ? Math
          .max(0, stock.space() - demands.keySet().stream().mapToLong(this::outstandingRequest).sum()) : 0;
    }

    @Override
    public long requestSupply(ResourceLocation key, long maximum) {
      return Math.min(maximum, supply.getOrDefault(key, 0L));
    }

    public long outstandingRequest(ResourceLocation key) {
      return gameTime > 60 ? 0 : Math.max(0, demands.getOrDefault(key, 0L) - stock.amount(key));
    }

    @Override
    public long outstandingRequest(ResourceLocation key, ReplenishmentProof proof) {
      requestObservers.watch(key, proof);
      long pending = outstandingRequest(key);
      if (pending > 0 && proof != null) {
        boolean wasConnected = connected;
        proof.observeExpiry(60, () -> gameTime);
        proof.addLiveCheck(() -> connected == wasConnected);
      }
      return connected ? pending : 0;
    }

    @Override
    public long request(ResourceLocation key, long additional, ReplenishmentProof publisher) {
      long accepted = Math.min(additional, requestSpace());
      if (accepted > 0) {
        demands.put(key, stock.amount(key) + outstandingRequest(key) + accepted);
        requestObservers.changed(key, publisher);
        requested += accepted;
      }
      return accepted;
    }

    @Override
    public RecipeResourceParticipant acquire(ResourceLocation key, long delta) {
      throw new AssertionError("Planning should not mutate stock");
    }

    @Override
    public void publish() {
      throw new AssertionError("Planning should not publish stock mutations");
    }
  }
}
