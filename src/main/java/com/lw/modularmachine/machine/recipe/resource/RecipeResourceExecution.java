package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import org.jetbrains.annotations.ApiStatus;

/**
 * Resource-specific server strategies; each stage has its own narrow contract.
 */
@ApiStatus.Internal
public final class RecipeResourceExecution {
  private RecipeResourceExecution() {
  }

  @FunctionalInterface
  public interface Validation<D> {
    /**
     * Validates detached declarations during reload preparation; no world access or
     * writes.
     */
    void validate(D data, HolderLookup.Provider registries);
  }

  public record Input<D>(RecipeExecutionTicket ticket, D data, int instances, Optional<FrozenRecipeRun> run) {
    public Input(RecipeExecutionTicket ticket, D data, int instances) {
      this(ticket, data, instances, Optional.empty());
    }

    public Input {
      Objects.requireNonNull(ticket, "ticket");
      Objects.requireNonNull(data, "data");
      Objects.requireNonNull(run, "run");
      if (instances < 1) {
        throw new IllegalArgumentException("Invalid resource instances");
      }
    }
  }

  /**
   * Data is from the frozen definition; the run also retains its frozen instance
   * count.
   */
  public record Run<D>(FrozenRecipeRun run, D data) {
    public Run {
      Objects.requireNonNull(run, "run");
      Objects.requireNonNull(data, "data");
    }
  }

  @FunctionalInterface
  public interface Start<D> {
    /**
     * Null means the full shared physical input allocation is unavailable. This
     * only prepares private state; it must not acquire resources, register
     * strategies or perform callback/world writes. Parallel probes may discard this
     * value without resolving it. Feasibility on the same ticket must be monotone
     * in instances: rejecting a count also rejects every larger count.
     */
    PreparedRecipeResource prepare(List<Input<D>> inputs);
  }

  @FunctionalInterface
  public interface PlannedStart<D> {
    /** Budgeted preparation for deterministic debits specified by frozen data. */
    Preparation prepare(RecipeExecutionTicket ticket, List<D> frozenData);
  }

  @FunctionalInterface
  public interface Admission<D> {
    /**
     * Checks the requested batch's output capacity and resource-specific numeric
     * limits without freezing a run or acquiring resources. Input allocation is
     * checked first. The plan must retain progress and report work; feasibility on
     * the same ticket must be monotone in instances for binary admission.
     * Arithmetic overflow denotes an infeasible count. The ticket is refreshed when
     * this plan is created; continuation uses detached quantities and dependency
     * identity, not the original ticket's same-tick commit proof.
     */
    RecipeResourcePlanning prepare(Input<D> input);
  }

  public interface Tick<D> {
    boolean required(D data);

    TickResult prepare(StructureService.StructureSubject owner, List<Run<D>> runs);
  }

  public enum TickStatus {
    READY,
    STALE,
    MISSING_INPUT,
    MISSING_OUTPUT,
    OUTPUT_BLOCKED,
    INSUFFICIENT
  }

  public record TickResult(TickStatus status, PreparedRecipeResource prepared) {
    public TickResult {
      Objects.requireNonNull(status, "status");
      if ((status == TickStatus.READY) != (prepared != null)) {
        throw new IllegalArgumentException("Invalid resource tick preparation");
      }
    }

    public static TickResult ready(PreparedRecipeResource prepared) {
      return new TickResult(TickStatus.READY, prepared);
    }

    public static TickResult failed(TickStatus status) {
      return new TickResult(status, null);
    }
  }

  @FunctionalInterface
  public interface Finish<D> {
    /**
     * Captures a read-only output snapshot. A rejected plan may be discarded and
     * recreated on a later owner tick within the shared output budget, including
     * when only private storage changed and native ticket dependencies did not.
     */
    Output prepare(RecipeExecutionTicket ticket, List<Run<D>> runs);
  }

  /** Freezes one run's amounts exactly once, before that run can be paid. */
  @FunctionalInterface
  public interface Freeze<D> {
    D freeze(D data, int instances);
  }

  public interface Preparation extends RecipeResourcePlanning {
    /** Called only after READY and a fresh commit proof; no writes happen here. */
    PreparedRecipeResource prepared(RecipeExecutionTicket ticket);
  }

  public interface Output extends Preparation {
    default boolean searchCurrent(RecipeExecutionTicket ticket) {
      var result = prepared(ticket);
      return result != null && result.current();
    }
  }
}
