package com.lw.modularmachine.machine.recipe.resource.world;

import com.lw.modularmachine.api.recipe.WorldPoolRecipeData;
import com.lw.modularmachine.api.recipe.WorldPoolRecipeData.Claim;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning.Status;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.Mods;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

/** Plans one shared physical balance per loaded chunk and resource kind. */
@ApiStatus.Internal
public final class WorldPoolExecution {
  private static final int MAX_CHUNK_VISITS = 512;
  private final RecipeResourceType<WorldPoolRecipeData> type;
  private final Blocks blocks;
  private final Supplier<Access> access;

  public WorldPoolExecution(RecipeResourceType<WorldPoolRecipeData> type, Blocks blocks, Supplier<Access> access) {
    this.type = type;
    this.blocks = blocks;
    this.access = access;
  }

  public void register(Mods dependency, Consumer<WorldPoolRecipeData> validation) {
    RecipeResourceExecutionRegistry.registerPlannedStart(
        type,
        input -> output(input.ticket(), input.data().scaled(input.instances()).output().stream().toList()),
        this::start,
        new RecipeResourceExecution.Tick<>() {
          @Override
          public boolean required(WorldPoolRecipeData data) {
            return false;
          }

          @Override
          public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<WorldPoolRecipeData>> runs) {
            throw new IllegalStateException("Chunk resources have no per-tick payment");
          }
        },
        (ticket, runs) -> output(ticket, runs.stream().flatMap(run -> run.data().output().stream()).toList()),
        WorldPoolRecipeData::scaled,
        (data, registries) -> {
          if (!dependency.isLoaded()) {
            throw new IllegalArgumentException(type.id() + " requires " + dependency.modid);
          }
          validation.accept(data);
        });
  }

  public RecipeSearchResource search(RecipeCatalog catalog) {
    var entries = catalog.recipes().candidates().stream()
        .map(candidate -> new RequiredInputIndex.RecipeInputs<String>(candidate.id(), List.of())).toList();
    var query = new RequiredInputQuery<>(new RequiredInputIndex<>(entries), Set.of());
    return (ticket, resources) -> new RecipeSearchResource.Captured() {
      private final Snapshot inputs = new Snapshot(ticket, false);
      private final Snapshot outputs = new Snapshot(ticket, true);

      @Override
      public ResourceInputQuery inputs() {
        return query;
      }

      @Override
      public RecipeResourcePlanning planInput(String recipeId, MachineRecipe definition) {
        return inputs.plan(definition.resourceData().get(type).input().stream().toList());
      }

      @Override
      public boolean inputCurrent(String recipeId, MachineRecipe definition, RecipeResourcePlanning plan) {
        return ((Allocation) plan).current();
      }

      @Override
      public RecipeResourcePlanning outputs(String recipeId, MachineRecipe definition) {
        return output(outputs, definition.resourceData().get(type).output().stream().toList());
      }
    };
  }

  private RecipeResourceExecution.Preparation start(RecipeExecutionTicket ticket, List<WorldPoolRecipeData> inputs) {
    return new Snapshot(ticket, false).plan(inputs.stream().flatMap(input -> input.input().stream()).toList());
  }

  private RecipeResourceExecution.Output output(RecipeExecutionTicket ticket, List<Claim> claims) {
    return output(new Snapshot(ticket, true), claims);
  }

  private RecipeResourceExecution.Output output(Snapshot snapshot, List<Claim> claims) {
    var plan = snapshot.plan(claims);
    return new RecipeResourceExecution.Output() {
      @Override
      public Status status() {
        return plan.status();
      }

      @Override
      public Progress advance(int budget) {
        return plan.advance(budget);
      }

      @Override
      public PreparedRecipeResource prepared(RecipeExecutionTicket refreshed) {
        return plan.prepared(refreshed);
      }

      @Override
      public boolean searchCurrent(RecipeExecutionTicket refreshed) {
        return status() == Status.READY && plan.current();
      }
    };
  }

  private final class Snapshot {
    private final RecipeExecutionTicket ticket;
    private final boolean output;
    private final Map<Coverage, CoverageCapture> coverages = new HashMap<>();
    private final Map<Long, Optional<LevelChunk>> chunks = new HashMap<>();
    private final Map<Address, Optional<Pool>> pools = new HashMap<>();

    private Snapshot(RecipeExecutionTicket ticket, boolean output) {
      this.ticket = ticket;
      this.output = output;
    }

    private Allocation plan(List<Claim> claims) {
      return new Allocation(this, claims);
    }

    private CoverageCapture coverage(Coverage coverage) {
      var block = blocks.select().apply(output, coverage.multichunk());
      var positions = block == null ? List.<BlockPos>of().iterator() : ticket.componentPositionCandidates(block);
      return new CoverageCapture(
          positions,
          coverage.radius(),
          position -> chunk(ChunkPos.asLong(position)).map(loaded -> loaded.getBlockState(position).is(block))
              .orElse(false));
    }

    private Optional<LevelChunk> chunk(long address) {
      return chunks.computeIfAbsent(
          address,
          position -> Optional.ofNullable(
              ticket.level().getChunkSource().getChunkNow(ChunkPos.getX(position), ChunkPos.getZ(position))));
    }

    private Optional<Pool> pool(Access adapter, Address address, BlockPos hatch) {
      return chunk(address.position()).map(loaded -> adapter.open(ticket.level(), loaded, hatch, address.kind()));
    }

  }

  private static final class CoverageCapture {
    private final Iterator<BlockPos> hatches;
    private final int radius;
    private final Predicate<BlockPos> matches;
    private final Map<Long, BlockPos> positions = new LinkedHashMap<>();
    private final Set<Long> centers = new LinkedHashSet<>();
    private BlockPos hatch;
    private int offsetX;
    private int offsetZ;
    private boolean overflow;

    private CoverageCapture(Iterator<BlockPos> positions, int radius, Predicate<BlockPos> matches) {
      hatches = positions;
      this.radius = radius;
      this.matches = matches;
    }

    private boolean complete() {
      return overflow || hatch == null && !hatches.hasNext();
    }

    private void step() {
      if (hatch == null) {
        var next = hatches.next();
        if (!matches.test(next)) {
          return;
        }
        if (centers.add(ChunkPos.asLong(next))) {
          hatch = next;
          offsetX = -radius;
          offsetZ = -radius;
        } else if (radius == 0) {
          positions.merge(ChunkPos.asLong(next), next, CoverageCapture::first);
        }
        return;
      }
      long chunk = ChunkPos.asLong((hatch.getX() >> 4) + offsetX, (hatch.getZ() >> 4) + offsetZ);
      positions.merge(chunk, hatch, CoverageCapture::first);
      overflow = positions.size() > MAX_CHUNK_VISITS;
      if (++offsetZ > radius) {
        offsetZ = -radius;
        if (++offsetX > radius) {
          hatch = null;
        }
      }
    }

    private static BlockPos first(BlockPos left, BlockPos right) {
      return left.compareTo(right) <= 0 ? left : right;
    }
  }

  private final class Allocation implements RecipeResourceExecution.Preparation {
    private final Snapshot snapshot;
    private final Iterator<Claim> pending;
    private final ClaimBatch batch;
    private final Map<Address, Balance> balances = new LinkedHashMap<>();
    private final Set<Long> visited = new LinkedHashSet<>();
    private final Set<Balance> changed = new LinkedHashSet<>();
    private final TreeMap<BlockPos, Balance> local = new TreeMap<>();
    private final Access adapter;
    private Status status = Status.PENDING;
    private Iterator<Claim> claims;
    private Claim claim;
    private CoverageCapture coverage;
    private Iterator<Map.Entry<Long, BlockPos>> positions;
    private long remaining;

    private Allocation(Snapshot snapshot, List<Claim> claims) {
      this.snapshot = snapshot;
      pending = claims.iterator();
      if (claims.size() > 1) {
        batch = new ClaimBatch(snapshot.output);
      } else {
        batch = null;
        this.claims = pending;
      }
      adapter = claims.isEmpty() ? null : access.get();
      if (claims.isEmpty()) {
        status = Status.READY;
      } else if (adapter == null) {
        status = Status.REJECTED;
      }
    }

    @Override
    public Status status() {
      return status;
    }

    @Override
    public Progress advance(int budget) {
      int used = 0;
      while (status == Status.PENDING && used < budget) {
        step();
        used++;
      }
      return new Progress(status, used);
    }

    @Override
    public PreparedRecipeResource prepared(RecipeExecutionTicket refreshed) {
      return status == Status.READY ? new Batch(List.copyOf(changed)) : null;
    }

    private boolean current() {
      return changed.stream().allMatch(balance -> balance.pool.current());
    }

    private void step() {
      if (claims == null) {
        if (pending.hasNext()) {
          if (!batch.add(pending.next())) {
            status = Status.REJECTED;
          }
          return;
        }
        claims = batch.iterator();
      }
      if (claim == null) {
        if (!claims.hasNext()) {
          status = Status.READY;
          return;
        }
        claim = claims.next();
        coverage = snapshot.coverages
            .computeIfAbsent(new Coverage(claim.multichunk(), claim.radius()), snapshot::coverage);
        remaining = claim.amount();
        return;
      }
      if (!coverage.complete()) {
        coverage.step();
        return;
      }
      if (coverage.overflow) {
        status = Status.REJECTED;
        return;
      }
      if (positions == null) {
        positions = coverage.positions.entrySet().iterator();
      }
      if ((remaining > 0 || batch != null) && positions.hasNext()) {
        visit(claim, positions.next());
        return;
      }
      if (!local.isEmpty()) {
        if (remaining > 0) {
          allocateLocal(claim.limit());
          return;
        }
        local.clear();
      }
      if (remaining != 0) {
        status = Status.REJECTED;
      } else if (!claims.hasNext()) {
        status = Status.READY;
      } else {
        claim = null;
        positions = null;
      }
    }

    private void visit(Claim claim, Map.Entry<Long, BlockPos> entry) {
      visited.add(entry.getKey());
      if (visited.size() > MAX_CHUNK_VISITS) {
        status = Status.REJECTED;
        return;
      }
      if (remaining == 0) {
        return;
      }
      var address = new Address(entry.getKey(), claim.kind());
      var balance = balances.computeIfAbsent(
          address,
          key -> snapshot.pools.computeIfAbsent(key, ignored -> snapshot.pool(adapter, key, entry.getValue()))
              .map(Balance::new).orElse(null));
      if (balance == null) {
        return;
      }
      var position = balance.pool.localOutput();
      if (position != null) {
        local.put(position, balance);
      } else {
        long allowed = snapshot.output ? Math
            .min(balance.pool.capacity(), claim.limit()) - balance.amount : balance.amount - claim.limit();
        allocate(balance, Math.min(remaining, Math.max(0, allowed)), claim.limit());
      }
    }

    private void allocateLocal(long limit) {
      var component = new ArrayList<Balance>(4);
      component.add(local.pollFirstEntry().getValue());
      for (int index = 0; index < component.size(); index++) {
        var position = component.get(index).pool.localOutput();
        for (var direction : Direction.values()) {
          var adjacent = local.remove(position.relative(direction));
          if (adjacent != null) {
            component.add(adjacent);
          }
        }
      }
      var nodes = new ArrayList<LocalAuraAllocation.Node>(component.size());
      for (var balance : component) {
        var position = balance.pool.localOutput();
        long surrounding = balance.amount;
        for (var other : component) {
          if (position.distManhattan(other.pool.localOutput()) == 1) {
            surrounding += other.amount - other.before;
          }
        }
        nodes.add(
            new LocalAuraAllocation.Node(
                position,
                balance.pool.capacity() - balance.amount,
                limit - surrounding,
                balance.amount == balance.before ? Long.MAX_VALUE : balance.outputLimit - surrounding));
      }
      var amounts = LocalAuraAllocation.allocate(nodes, remaining);
      for (int index = 0; index < amounts.length; index++) {
        allocate(component.get(index), amounts[index], limit);
      }
    }

    private void allocate(Balance balance, long amount, long limit) {
      if (amount > 0) {
        balance.amount += snapshot.output ? amount : -amount;
        balance.outputLimit = Math.min(balance.outputLimit, limit);
        changed.add(balance);
        remaining -= amount;
      }
    }
  }

  static final class ClaimBatch {
    private record Key(String kind, boolean multichunk, int radius, long limit) {
    }

    private final Map<Key, Long> totals;

    ClaimBatch(boolean output) {
      var byLimit = Comparator.comparingLong(Key::limit);
      totals = new TreeMap<>(
          Comparator.comparingInt(Key::radius).thenComparing(output ? byLimit : byLimit.reversed())
              .thenComparing(Key::kind).thenComparing(Key::multichunk));
    }

    boolean add(Claim claim) {
      try {
        totals.merge(
            new Key(claim.kind(), claim.multichunk(), claim.radius(), claim.limit()),
            claim.amount(),
            Math::addExact);
        return true;
      } catch (ArithmeticException overflow) {
        return false;
      }
    }

    Iterator<Claim> iterator() {
      return totals.entrySet().stream().map(entry -> {
        var key = entry.getKey();
        return new Claim(key.kind(), entry.getValue(), key.multichunk(), key.radius(), key.limit());
      }).iterator();
    }
  }

  public record Blocks(BiFunction<Boolean, Boolean, Block> select) {
  }

  @FunctionalInterface
  public interface Access {
    Pool open(ServerLevel level, LevelChunk chunk, BlockPos hatch, String kind);
  }

  public interface Pool {
    default BlockPos localOutput() {
      return null;
    }

    long amount();

    long capacity();

    boolean current();

    RecipeResourceParticipant acquire(long before, long after);

    void publish();
  }

  private record Address(long position, String kind) {
  }

  private record Coverage(boolean multichunk, int radius) {
  }

  private static final class Balance {
    private final Pool pool;
    private final long before;
    private long amount;
    private long outputLimit = Long.MAX_VALUE;

    private Balance(Pool pool) {
      this.pool = pool;
      before = pool.amount();
      amount = before;
    }
  }

  private record Batch(List<Balance> balances, RecipeResourceTransaction transaction)
      implements
        PreparedRecipeResource {
    private Batch(List<Balance> balances) {
      this(balances, new RecipeResourceTransaction());
    }

    @Override
    public boolean current() {
      return balances.stream().allMatch(balance -> balance.pool.current());
    }

    @Override
    public void acquire() {
      if (!current()) {
        throw new IllegalStateException("World resource balances changed before acquisition");
      }
      var phase = transaction.phase();
      for (var balance : balances) {
        phase.enlist(balance.pool.acquire(balance.before, balance.amount));
      }
    }

    @Override
    public void resolve(boolean commit) {
      transaction.resolve(commit);
    }

    @Override
    public void publish() {
      balances.forEach(balance -> balance.pool.publish());
    }
  }
}
