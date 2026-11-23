package com.lw.modularmachine.machine.recipe.resource.keyed;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.lw.modularmachine.api.recipe.KeyedAmountRecipeData;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource;
import com.lw.modularmachine.machine.recipe.search.ResourcePlanningSequence;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.ApiStatus;

/** Shared native payment/output planning for exact keyed hatch resources. */
@ApiStatus.Internal
public final class KeyedRecipeExecution {
  private final RecipeResourceType<KeyedAmountRecipeData> type;
  private final Supplier<Set<? extends Block>> inputs;
  private final Supplier<Set<? extends Block>> outputs;
  private final ResourceLocation unrestrictedInput;

  public KeyedRecipeExecution(RecipeResourceType<KeyedAmountRecipeData> type, Supplier<Set<? extends Block>> inputs, Supplier<Set<? extends Block>> outputs, ResourceLocation unrestrictedInput) {
    this.type = type;
    this.inputs = inputs;
    this.outputs = outputs;
    this.unrestrictedInput = unrestrictedInput;
  }

  public void register(RecipeResourceExecution.Validation<KeyedAmountRecipeData> validation) {
    RecipeResourceExecutionRegistry.registerPlannedStart(
        type,
        input -> plan(input.ticket(), List.of(input.data().scaled(input.instances()).output()), true, false),
        (ticket, data) -> plan(ticket, data.stream().map(KeyedAmountRecipeData::input).toList(), false, false),
        new RecipeResourceExecution.Tick<>() {
          @Override
          public boolean required(KeyedAmountRecipeData data) {
            return false;
          }

          @Override
          public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<KeyedAmountRecipeData>> runs) {
            throw new IllegalStateException("Keyed hatch resources have no per-tick payment");
          }
        },
        (ticket, runs) -> plan(ticket, runs.stream().map(run -> run.data().output()).toList(), true, false),
        KeyedAmountRecipeData::scaled,
        validation);
  }

  public RecipeSearchResource search(RecipeCatalog catalog) {
    var entries = catalog.recipes().candidates().stream()
        .map(candidate -> new RequiredInputIndex.RecipeInputs<String>(candidate.id(), List.of())).toList();
    var query = new RequiredInputQuery<>(new RequiredInputIndex<>(entries), Set.of());
    return (ticket, resources) -> new RecipeSearchResource.Captured() {
      @Override
      public ResourceInputQuery inputs() {
        return query;
      }

      @Override
      public RecipeResourcePlanning planInput(String recipeId, MachineRecipe definition) {
        var data = definition.resourceData().get(type);
        return plan(ticket, List.of(data.input()), false, unrestrictedInput == null);
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        return plan(ticket, List.of(definition.resourceData().get(type).output()), true, false);
      }
    };
  }

  private Allocation plan(RecipeExecutionTicket ticket, List<Map<ResourceLocation, Long>> claims, boolean output, boolean replenish) {
    var totals = new TreeMap<ResourceLocation, Long>();
    try {
      claims.forEach(map -> map.forEach((key, amount) -> totals.merge(key, amount, Math::addExact)));
    } catch (ArithmeticException overflow) {
      return new Allocation(List.of(), Collections.emptyIterator(), output, replenish, true);
    }
    var ordered = new ArrayList<>(totals.entrySet());
    if (!output && unrestrictedInput != null) {
      ordered.sort(Comparator.comparing(entry -> unrestrictedInput.equals(entry.getKey())));
    }
    var ports = ordered.isEmpty() ? Collections
        .<KeyedResourcePort>emptyIterator() : ports(ticket, (output ? outputs : inputs).get());
    return new Allocation(ordered, ports, output, replenish, false);
  }

  private static Iterator<KeyedResourcePort> ports(RecipeExecutionTicket ticket, Set<? extends Block> blocks) {
    var positions = ticket.componentPositionCandidates(blocks);
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return positions.hasNext();
      }

      @Override
      public KeyedResourcePort next() {
        BlockPos position = positions.next();
        var chunk = ticket.level().getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        return chunk != null
            && blocks.contains(chunk.getBlockState(position).getBlock())
            && chunk.getBlockEntity(position) instanceof KeyedResourcePort port ? port : null;
      }
    };
  }

  static final class Allocation implements RecipeResourceExecution.Output, RecipeSearchResource.Replenishment, RecipeSearchResource.Recheckable {
    private final List<Map.Entry<ResourceLocation, Long>> claims;
    private final Iterator<? extends KeyedResourcePort> candidates;
    private final boolean output;
    private final KeyedRequestAllocation replenishment;
    private final Set<Object> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Port> ports = new ArrayList<>();
    private final Map<Port, Long> credits = new HashMap<>();
    private final Map<StockAddress, Long> changes = new LinkedHashMap<>();
    private final Map<Port, Long> reservedStock = new HashMap<>();
    private final Map<ResourceLocation, Long> deficits = new LinkedHashMap<>();
    private Map<ResourceLocation, Long> liveDeficits;
    private RecipeSearchResource.ReplenishmentProof liveDeficitProof;
    private Status status;
    private boolean capturing = true;
    private int claimCursor;
    private int portCursor;
    private Iterator<ResourceLocation> keys;
    private long remaining;
    private PeekingIterator<KeyedRequestAllocation.Request> publication;
    private boolean requested;

    Allocation(List<Map.Entry<ResourceLocation, Long>> claims, Iterator<? extends KeyedResourcePort> candidates, boolean output, boolean replenish, boolean overflow) {
      this.claims = claims;
      this.candidates = candidates;
      this.output = output;
      replenishment = replenish ? new KeyedRequestAllocation() : null;
      status = overflow ? Status.REJECTED : claims.isEmpty() ? Status.READY : Status.PENDING;
      remaining = claims.isEmpty() ? 0 : claims.getFirst().getValue();
    }

    @Override
    public Status status() {
      return status;
    }

    @Override
    public Progress advance(int budget) {
      int used = 0;
      while (status == Status.PENDING && used < budget) {
        used++;
        if (capturing) {
          capturePort();
        } else if (claimCursor == claims.size()) {
          status = replenishment.step(ports);
        } else {
          advanceClaim();
        }
      }
      return new Progress(status, used);
    }

    private void capturePort() {
      if (!candidates.hasNext()) {
        capturing = false;
        return;
      }
      var port = candidates.next();
      var address = port == null ? null : port.resourceAddress();
      if (address != null && distinct.add(address)) {
        ports.add(new Port(port, address));
      }
    }

    private void advanceClaim() {
      var key = claims.get(claimCursor).getKey();
      if (portCursor == ports.size()) {
        if (replenishment != null) {
          replenishment.addDeficit(key, remaining);
          deficits.put(key, remaining);
          nextClaim();
        } else {
          status = Status.REJECTED;
        }
        return;
      }
      var port = ports.get(portCursor);
      if (keys == null) {
        keys = output ? List.of(key).iterator() : port.hatch().inputKeys(key).iterator();
      }
      if (keys.hasNext()) {
        allocateStock(port, keys.next());
      }
      if (!keys.hasNext()) {
        keys = null;
        portCursor++;
      }
      if (remaining == 0) {
        nextClaim();
      }
    }

    private void nextClaim() {
      claimCursor++;
      if (claimCursor == claims.size()) {
        if (replenishment == null) {
          status = Status.READY;
        }
      } else {
        remaining = claims.get(claimCursor).getValue();
        portCursor = 0;
        keys = null;
      }
    }

    private void allocateStock(Port port, ResourceLocation key) {
      var stock = new StockAddress(port, key);
      long allowance = credits
          .computeIfAbsent(port, value -> output ? value.hatch().totalSpace() : value.hatch().totalAvailable());
      var hatch = port.hatch();
      long amount = output ? hatch.space(key) : hatch.available(key) + changes.getOrDefault(stock, 0L);
      long accepted = Math.min(remaining, Math.min(allowance, amount));
      if (accepted > 0) {
        credits.put(port, allowance - accepted);
        changes.merge(stock, output ? accepted : -accepted, Long::sum);
        if (replenishment != null) {
          reservedStock.merge(port, accepted, Long::sum);
        }
        remaining -= accepted;
      }
    }

    @Override
    public boolean waiting() {
      return status == Status.READY && replenishment != null && !replenishment.requests().isEmpty();
    }

    @Override
    public boolean requested() {
      return requested;
    }

    @Override
    public ResourceLocation nextRequestKey() {
      if (publication == null) {
        publication = Iterators.peekingIterator(replenishment.requests().values().iterator());
      }
      return publication.peek().key();
    }

    @Override
    public Progress advanceRequests(int budget, BooleanSupplier current) {
      if (!waiting()) {
        throw new IllegalStateException("Keyed inputs have no ready replenishment");
      }
      if (liveDeficits == null) {
        throw new IllegalStateException("Keyed inputs require a live deficit check");
      }
      if (publication == null) {
        nextRequestKey();
      }
      int used = 0;
      while (publication.hasNext() && used < budget) {
        if (!current.getAsBoolean()) {
          return new Progress(Status.REJECTED, used);
        }
        publishRequest(publication.next(), current);
        used++;
      }
      return new Progress(publication.hasNext() ? Status.PENDING : Status.READY, used);
    }

    private void publishRequest(KeyedRequestAllocation.Request request, BooleanSupplier current) {
      var port = request.port();
      var hatch = port.hatch();
      long deficit = liveDeficits.getOrDefault(request.key(), 0L);
      if (deficit == 0 || hatch.resourceAddress() != port.address()) {
        return;
      }
      long space = Math.min(deficit, hatch.requestSpace());
      long confirmed = Math.min(request.confirmed(), space);
      if (confirmed > 0) {
        confirmed = Math.clamp(hatch.requestSupply(request.key(), confirmed), 0, confirmed);
      }
      long amount = confirmed + Math.min(request.fallback(), space - confirmed);
      if (amount > 0 && current.getAsBoolean()) {
        long accepted = hatch.request(request.key(), amount, liveDeficitProof);
        if (accepted > 0) {
          requested = true;
          liveDeficits.put(request.key(), Math.max(0, deficit - accepted));
        }
      }
    }

    @Override
    public RecipeResourcePlanning recheck(RecipeSearchResource.ReplenishmentProof proof) {
      var stock = new Verification(changes, proof);
      return waiting() ? new ResourcePlanningSequence(List.of(() -> stock, () -> new DeficitCheck(proof))) : stock;
    }

    private final class DeficitCheck implements RecipeResourcePlanning {
      private final RecipeSearchResource.ReplenishmentProof proof;
      private final Iterator<Map.Entry<ResourceLocation, Long>> keys = deficits.entrySet().iterator();
      private final Map<Port, Long> remainingStock = new HashMap<>();
      private final Map<ResourceLocation, Long> checked = new HashMap<>();
      private Map.Entry<ResourceLocation, Long> claim = keys.next();
      private long missing = claim.getValue();
      private int portIndex;
      private Status status = Status.PENDING;

      private DeficitCheck(RecipeSearchResource.ReplenishmentProof proof) {
        this.proof = proof;
      }

      @Override
      public Status status() {
        return status;
      }

      @Override
      public Progress advance(int budget) {
        int used = 0;
        while (status == Status.PENDING && used < budget) {
          var key = claim.getKey();
          missing -= extraStock(ports.get(portIndex++), key);
          used++;
          if (missing == 0 || portIndex == ports.size()) {
            checked.put(key, missing);
            if (keys.hasNext()) {
              claim = keys.next();
              missing = claim.getValue();
              portIndex = 0;
            } else {
              liveDeficits = checked;
              liveDeficitProof = proof;
              status = Status.READY;
            }
          }
        }
        return new Progress(status, used);
      }

      private long extraStock(Port port, ResourceLocation key) {
        var hatch = port.hatch();
        boolean liveCheck = proof != null && !hatch.watchInputAmount(proof);
        if (hatch.resourceAddress() != port.address()) {
          if (liveCheck) {
            proof.observeDeficit(key, () -> hatch.resourceAddress() != port.address());
          }
          return 0;
        }
        var address = new StockAddress(port, key);
        long available = hatch.available(key);
        long total = hatch.totalAvailable();
        if (liveCheck) {
          proof.observeDeficit(
              key,
              () -> hatch.resourceAddress() == port.address()
                  && hatch.available(key) == available
                  && hatch.totalAvailable() == total);
        }
        long free = remainingStock
            .computeIfAbsent(port, value -> Math.max(0, total - reservedStock.getOrDefault(value, 0L)));
        long extra = Math.min(missing, Math.min(free, Math.max(0, available + changes.getOrDefault(address, 0L))));
        if (proof != null && extra > 0 && total != Long.MAX_VALUE) {
          // An earlier key's extra stock now affects the credit available to later keys.
          proof.shareDeficitCredit();
        }
        remainingStock.put(port, free - extra);
        long outstanding = missing == extra ? 0 : Math.min(missing - extra, hatch.outstandingRequest(key, proof));
        return extra + outstanding;
      }
    }

    @Override
    public PreparedRecipeResource prepared(RecipeExecutionTicket refreshed) {
      return status == Status.READY && !waiting() ? new Batch(changes) : null;
    }

  }

  record Port(KeyedResourcePort hatch, Object address) {
  }

  record StockAddress(Port port, ResourceLocation key) {
  }

  private static boolean current(StockAddress stock, long delta) {
    var port = stock.port();
    var hatch = port.hatch();
    return port.address() == hatch.resourceAddress()
        && (delta < 0 ? hatch.available(stock.key()) >= -delta : hatch.space(stock.key()) >= delta);
  }

  private static final class Verification implements RecipeResourcePlanning {
    private final Iterator<Map.Entry<StockAddress, Long>> changes;
    private final Map<Port, Credit> credits = new HashMap<>();
    private final RecipeSearchResource.ReplenishmentProof proof;
    private Status status;

    private record Credit(long limit, long used, boolean liveCheck) {
    }

    private Verification(Map<StockAddress, Long> changes, RecipeSearchResource.ReplenishmentProof proof) {
      this.changes = changes.entrySet().iterator();
      this.proof = proof;
      status = changes.isEmpty() ? Status.READY : Status.PENDING;
    }

    @Override
    public Status status() {
      return status;
    }

    @Override
    public Progress advance(int budget) {
      int used = 0;
      while (status == Status.PENDING && used < budget) {
        var change = changes.next();
        var stock = change.getKey();
        long delta = change.getValue();
        var credit = credits.computeIfAbsent(stock.port(), port -> credit(port, delta < 0));
        used++;
        long amount = Math.abs(delta);
        if (credit.limit() - credit.used() < amount || !current(stock, delta)) {
          status = Status.REJECTED;
        } else {
          long total = credit.used() + amount;
          credits.put(stock.port(), new Credit(credit.limit(), total, credit.liveCheck()));
          if (credit.liveCheck()) {
            proof.addLiveCheck(() -> current(stock, delta) && stock.port().hatch().totalAvailable() >= total);
          }
          if (!changes.hasNext()) {
            status = Status.READY;
          }
        }
      }
      return new Progress(status, used);
    }

    private Credit credit(Port port, boolean input) {
      var hatch = port.hatch();
      if (input) {
        boolean liveCheck = proof != null && !hatch.watchInput(proof);
        return new Credit(hatch.totalAvailable(), 0, liveCheck);
      }
      if (proof != null) {
        hatch.watchOutput(proof);
      }
      return new Credit(hatch.totalSpace(), 0, false);
    }
  }

  private record Batch(Map<StockAddress, Long> changes, RecipeResourceTransaction transaction)
      implements
        PreparedRecipeResource {
    private Batch(Map<StockAddress, Long> changes) {
      this(changes, new RecipeResourceTransaction());
    }

    @Override
    public boolean current() {
      return new Verification(changes, null).advance(changes.size()).status() == RecipeResourcePlanning.Status.READY;
    }

    @Override
    public void acquire() {
      var phase = transaction.phase();
      for (var change : changes.entrySet()) {
        var stock = change.getKey();
        if (!KeyedRecipeExecution.current(stock, change.getValue())) {
          throw new IllegalStateException("Keyed hatch identity, stock or space changed");
        }
        phase.enlist(stock.port().hatch().acquire(stock.key(), change.getValue()));
      }
    }

    @Override
    public void resolve(boolean commit) {
      transaction.resolve(commit);
    }

    @Override
    public void publish() {
      changes.keySet().stream().map(stock -> stock.port().hatch()).distinct().forEach(KeyedResourcePort::publish);
    }
  }
}
