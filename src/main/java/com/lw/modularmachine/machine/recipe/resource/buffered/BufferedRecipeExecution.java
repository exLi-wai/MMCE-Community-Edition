package com.lw.modularmachine.machine.recipe.resource.buffered;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.blockentity.BufferedRecipeHatchBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.resource.PreparedRecipeResource;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.storage.LongEnergyBuffer;
import com.lw.modularmachine.util.Mods;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.ApiStatus;

/** Aggregates a paid phase over distinct compatible local buffers. */
@ApiStatus.Internal
public final class BufferedRecipeExecution<D> {
  private final RecipeResourceType<D> type;
  private final Supplier<Set<? extends Block>> blocks;
  private final ToLongFunction<D> quantity;
  private final LongPredicate supported;
  private final Supplier<Set<? extends Block>> outputs;
  private final ToLongFunction<D> outputQuantity;

  public BufferedRecipeExecution(RecipeResourceType<D> type, Supplier<Set<? extends Block>> blocks, ToLongFunction<D> quantity, LongPredicate supported) {
    this(type, blocks, quantity, supported, Set::of, ignored -> 0);
  }

  public BufferedRecipeExecution(RecipeResourceType<D> type, Supplier<Set<? extends Block>> blocks, ToLongFunction<D> quantity, LongPredicate supported, Supplier<Set<? extends Block>> outputs, ToLongFunction<D> outputQuantity) {
    this.type = type;
    this.blocks = blocks;
    this.quantity = quantity;
    this.supported = supported;
    this.outputs = outputs;
    this.outputQuantity = outputQuantity;
  }

  public void register(Mods dependency, LongFunction<D> frozenData) {
    register(
        dependency,
        (data, instances) -> frozenData.apply(Math.multiplyExact(quantity.applyAsLong(data), instances)));
  }

  public void register(Mods dependency, RecipeResourceExecution.Freeze<D> freeze) {
    RecipeResourceExecutionRegistry.register(
        type,
        input -> plan(input.ticket(), Math.multiplyExact(outputQuantity.applyAsLong(input.data()), input.instances())),
        this::prepareStart,
        new RecipeResourceExecution.Tick<>() {
          @Override
          public boolean required(D data) {
            return false;
          }

          @Override
          public RecipeResourceExecution.TickResult prepare(StructureService.StructureSubject owner, List<RecipeResourceExecution.Run<D>> runs) {
            throw new IllegalStateException("Buffered input is only charged at start: " + type.id());
          }
        },
        this::prepareFinish,
        freeze,
        (data, registries) -> {
          if (!dependency.isLoaded()) {
            throw new IllegalArgumentException(type.id() + " requires " + dependency.modid);
          }
        });
  }

  private PreparedRecipeResource prepareStart(List<RecipeResourceExecution.Input<D>> inputs) {
    long total = 0;
    try {
      for (var input : inputs) {
        long amount = Math.multiplyExact(quantity.applyAsLong(input.data()), input.instances());
        if (!supported.test(amount)) {
          return null;
        }
        total = Math.addExact(total, amount);
      }
    } catch (ArithmeticException overflow) {
      return null;
    }
    return allocate(inputs.getFirst().ticket(), total, Flow.INPUT);
  }

  private RecipeResourceExecution.Output prepareFinish(RecipeExecutionTicket ticket, List<RecipeResourceExecution.Run<D>> runs) {
    long total = 0;
    try {
      for (var run : runs) {
        total = Math.addExact(total, outputQuantity.applyAsLong(run.data()));
      }
    } catch (ArithmeticException overflow) {
      return new OutputPlan(RecipeResourcePlanning.Status.REJECTED, 0);
    }
    return plan(ticket, total);
  }

  private OutputPlan plan(RecipeExecutionTicket ticket, long total) {
    var status = allocate(
        ticket,
        total,
        Flow.OUTPUT) == null ? RecipeResourcePlanning.Status.REJECTED : RecipeResourcePlanning.Status.READY;
    return new OutputPlan(status, total);
  }

  public RecipeResourcePlanning outputs(RecipeExecutionTicket ticket, D data) {
    return plan(ticket, outputQuantity.applyAsLong(data));
  }

  private Batch allocate(RecipeExecutionTicket ticket, long total, Flow flow) {
    if (total == 0) {
      return new Batch(List.of(), flow);
    }
    var changes = new ArrayList<Change>();
    long remaining = total;
    for (var hatch : hatches(ticket, flow == Flow.INPUT ? blocks.get() : outputs.get())) {
      long amount = Math.min(flow.available(hatch), remaining);
      if (amount > 0) {
        changes.add(new Change(hatch, amount));
        remaining -= amount;
      }
      if (remaining == 0) {
        break;
      }
    }
    return remaining == 0 ? new Batch(changes, flow) : null;
  }

  public long available(RecipeExecutionTicket ticket) {
    long total = 0;
    for (var hatch : hatches(ticket, blocks.get())) {
      total += Math.min(Long.MAX_VALUE - total, hatch.available());
    }
    return total;
  }

  private List<BufferedRecipeHatchBlockEntity> hatches(RecipeExecutionTicket ticket, Set<? extends Block> candidates) {
    var result = new ArrayList<BufferedRecipeHatchBlockEntity>();
    for (var position : ticket.componentPositions(candidates)) {
      if (ticket.level().getBlockEntity(position) instanceof BufferedRecipeHatchBlockEntity hatch) {
        result.add(hatch);
      }
    }
    return List.copyOf(result);
  }

  private enum Flow {
    INPUT,
    OUTPUT;

    private long available(BufferedRecipeHatchBlockEntity hatch) {
      return this == INPUT ? hatch.available() : hatch.space();
    }

    private boolean apply(LongEnergyBuffer.Lease lease, long amount) {
      return this == INPUT ? lease.extractExact(amount) : lease.insertExact(amount);
    }
  }

  private record Change(BufferedRecipeHatchBlockEntity hatch, long amount) {
  }

  private final class OutputPlan implements RecipeResourceExecution.Output {
    private final Status status;
    private final long total;

    private OutputPlan(Status status, long total) {
      this.status = status;
      this.total = total;
    }

    @Override
    public Status status() {
      return status;
    }

    @Override
    public Progress advance(int workBudget) {
      return new Progress(status, 0);
    }

    @Override
    public PreparedRecipeResource prepared(RecipeExecutionTicket ticket) {
      return status == Status.READY ? allocate(ticket, total, Flow.OUTPUT) : null;
    }
  }

  private static final class Batch implements PreparedRecipeResource {
    private final List<Change> changes;
    private final Flow flow;
    private final RecipeResourceTransaction transaction = new RecipeResourceTransaction();

    private Batch(List<Change> changes, Flow flow) {
      this.changes = List.copyOf(changes);
      this.flow = flow;
    }

    @Override
    public boolean current() {
      return changes.stream().allMatch(change -> flow.available(change.hatch()) >= change.amount());
    }

    @Override
    public void acquire() {
      var phase = transaction.phase();
      for (var change : changes) {
        var lease = phase.enlist(change.hatch().acquireRecipeBuffer(), LongEnergyBuffer.Lease::resolve);
        if (!flow.apply(lease, change.amount())) {
          throw new IllegalStateException("Buffered resource allocation changed before commit");
        }
      }
    }

    @Override
    public void resolve(boolean commit) {
      transaction.resolve(commit);
    }

    @Override
    public void publish() {
      for (var change : changes) {
        change.hatch().setChanged();
      }
    }
  }
}
