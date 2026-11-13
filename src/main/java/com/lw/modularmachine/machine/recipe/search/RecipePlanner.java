package com.lw.modularmachine.machine.recipe.search;

import org.jetbrains.annotations.ApiStatus;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact-key planner. Sessions hold only immutable snapshots and private
 * resumable scratch state.
 */
@ApiStatus.Internal
public final class RecipePlanner {
  private static final int DEFAULT_SEARCH_BUDGET = 65_536;
  public static final int MAX_OUTPUT_SLOTS = 8192;

  private RecipePlanner() {
  }

  public enum Status {
    SELECTED,
    NO_MATCH,
    BUDGET_EXHAUSTED
  }

  public record SearchResult<K>(Status status, Optional<RecipePlan<K>> plan, int workUsed) {
    public SearchResult {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(plan, "plan");
      if ((status == Status.SELECTED) != plan.isPresent() || workUsed < 0) {
        throw new IllegalArgumentException("Invalid search result");
      }
    }
  }

  /**
   * Convenience entry point; runtime callers compile once and retain a Session
   * instead.
   */
  public static <K> Optional<RecipePlan<K>> select(ResourceSnapshot<K> snapshot, Collection<RecipeCandidate<K>> candidates) {
    var result = search(snapshot, candidates, DEFAULT_SEARCH_BUDGET);
    if (result.status() == Status.BUDGET_EXHAUSTED) {
      throw new IllegalStateException("Recipe planning budget exhausted; resume a Session");
    }
    return result.plan();
  }

  public static <K> SearchResult<K> search(ResourceSnapshot<K> snapshot, Collection<RecipeCandidate<K>> candidates, int workBudget) {
    return prepare(snapshot, new CompiledRecipeSet<>(candidates)).advance(workBudget);
  }

  public static <K> Session<K> prepare(ResourceSnapshot<K> snapshot, CompiledRecipeSet<K> recipes) {
    return new Session<>(snapshot, recipes, false, null);
  }

  static <K> Session<K> prepare(ResourceSnapshot<K> snapshot, CompiledRecipeSet<K> recipes, Set<String> allowedIds) {
    return new Session<>(snapshot, recipes, false, allowedIds);
  }

  public static <K> Session<K> prepareOutputs(ResourceSnapshot<K> snapshot, RecipeCandidate<K> paidOutput) {
    return new Session<>(snapshot, new CompiledRecipeSet<>(List.of(paidOutput)), true, null);
  }

  /**
   * One owner at a time; advance never touches the world or re-sorts/restarts
   * candidates.
   */
  public static final class Session<K> {
    private enum Phase {
      NEXT,
      INPUT,
      EXISTING,
      CAPACITY,
      EMPTY
    }

    private final ResourceSnapshot<K> snapshot;
    private final List<CompiledRecipeSet.Entry<K>> candidates;
    private final boolean outputOnly;
    private final List<RecipePlan.Insertion<K>> insertions = new ArrayList<>();
    private final List<Integer> emptySlots = new ArrayList<>();
    private Phase phase = Phase.NEXT;
    private CompiledRecipeSet.Entry<K> current;
    private int candidateCursor, inputCursor, outputCursor, depth, missingTypes;
    private long[] remaining, amounts;
    private int[] next, chosen;
    private boolean[] triedPlacement;
    private Status terminal;
    private RecipePlan<K> selected;
    private long totalWork;
    private BigInteger emptyCapacity;
    private int capacityKey, capacitySlot;
    private long capacityCount;

    private Session(ResourceSnapshot<K> snapshot, CompiledRecipeSet<K> recipes, boolean outputOnly, Set<String> allowedIds) {
      this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(recipes, "recipes");
      this.outputOnly = outputOnly;
      candidates = recipes.entriesFor(snapshot, outputOnly, allowedIds);
      if (snapshot.outputs().size() > MAX_OUTPUT_SLOTS
          || snapshot.inputs().size() > CompiledRecipeSet.MAX_TOTAL_REQUIREMENTS) {
        throw new IllegalArgumentException("Snapshot exceeds planner support limits");
      }
      if (candidates.isEmpty()) {
        terminal = Status.NO_MATCH;
      }
    }

    public long totalWork() {
      return totalWork;
    }

    /**
     * Continue in the same priority order when another resource rejects this
     * candidate.
     */
    void rejectSelected() {
      if (terminal != Status.SELECTED || outputOnly) {
        throw new IllegalStateException("No start candidate to reject");
      }
      selected = null;
      terminal = null;
      phase = Phase.NEXT;
    }

    public SearchResult<K> advance(int workBudget) {
      if (workBudget < 0) {
        throw new IllegalArgumentException("Negative planning budget");
      }
      int used = 0;
      while (terminal == null && used < workBudget) {
        used++;
        totalWork++;
        switch (phase) {
          case NEXT -> nextCandidate();
          case INPUT -> checkInput();
          case EXISTING -> fillExisting();
          case CAPACITY -> checkCapacity();
          case EMPTY -> assignEmpty();
        }
      }
      return new SearchResult<>(
          terminal == null ? Status.BUDGET_EXHAUSTED : terminal,
          Optional.ofNullable(selected),
          used);
    }

    private void nextCandidate() {
      if (candidateCursor == candidates.size()) {
        terminal = Status.NO_MATCH;
        return;
      }
      current = candidates.get(candidateCursor++);
      if (!outputOnly && current.anchorKey != null && snapshot.inputCount(current.anchorKey) < current.anchorAmount) {
        phase = Phase.NEXT;
        return;
      }
      remaining = current.outputAmounts();
      missingTypes = remaining.length;
      inputCursor = outputCursor = depth = 0;
      insertions.clear();
      emptySlots.clear();
      emptyCapacity = BigInteger.ZERO;
      phase = outputOnly ? Phase.EXISTING : Phase.INPUT;
    }

    private void checkInput() {
      if (inputCursor == current.inputEntries.size()) {
        phase = Phase.EXISTING;
        return;
      }
      var entry = current.inputEntries.get(inputCursor++);
      if (snapshot.inputCount(entry.getKey()) < entry.getValue()) {
        phase = Phase.NEXT;
      }
    }

    private void fillExisting() {
      if (outputCursor == snapshot.outputs().size()) {
        BigInteger needed = BigInteger.ZERO;
        for (long amount : remaining) {
          needed = needed.add(BigInteger.valueOf(amount));
        }
        if (needed.compareTo(emptyCapacity) > 0) {
          phase = Phase.NEXT;
          return;
        }
        capacityKey = capacitySlot = 0;
        capacityCount = 0;
        phase = Phase.CAPACITY;
        return;
      }
      int slotIndex = outputCursor++;
      var slot = snapshot.outputs().get(slotIndex);
      if (slot.key() == null) {
        if (slot.capacity() > 0) {
          long max = 0;
          for (var key : current.outputKeys) {
            max = Math.max(max, slot.capacityFor(key));
          }
          if (max > 0) {
            emptySlots.add(slotIndex);
            emptyCapacity = emptyCapacity.add(BigInteger.valueOf(max));
          }
        }
        return;
      }
      Integer key = current.outputIndices.get(slot.key());
      if (key == null || remaining[key] == 0) {
        return;
      }
      long amount = Math.min(remaining[key], Math.max(0, slot.capacityFor(slot.key()) - slot.count()));
      if (amount == 0) {
        return;
      }
      remaining[key] -= amount;
      if (remaining[key] == 0) {
        missingTypes--;
      }
      insertions.add(new RecipePlan.Insertion<>(slotIndex, slot.key(), amount));
      if (missingTypes == 0) {
        finish();
      }
    }

    private void checkCapacity() {
      if (capacityKey == remaining.length) {
        next = new int[emptySlots.size()];
        chosen = new int[emptySlots.size()];
        amounts = new long[emptySlots.size()];
        triedPlacement = new boolean[emptySlots.size()];
        phase = Phase.EMPTY;
        return;
      }
      long needed = remaining[capacityKey];
      if (needed == 0 || capacityCount >= needed) {
        capacityKey++;
        capacitySlot = 0;
        capacityCount = 0;
        return;
      }
      if (capacitySlot == emptySlots.size()) {
        phase = Phase.NEXT;
        return;
      }
      long available = snapshot.outputs().get(emptySlots.get(capacitySlot++))
          .capacityFor(current.outputKeys.get(capacityKey));
      capacityCount += Math.min(available, needed - capacityCount);
    }

    private void assignEmpty() {
      if (missingTypes == 0) {
        finish();
        return;
      }
      // Each remaining kind needs its own slot, even if total capacity is enough.
      if (missingTypes > emptySlots.size() - depth) {
        backtrack();
        return;
      }
      int key = next[depth]++;
      if (key < remaining.length) {
        if (remaining[key] == 0) {
          return;
        }
        long amount = Math.min(
            remaining[key],
            snapshot.outputs().get(emptySlots.get(depth)).capacityFor(current.outputKeys.get(key)));
        if (amount == 0) {
          return;
        }
        triedPlacement[depth] = true;
        chosen[depth] = key;
        amounts[depth] = amount;
        remaining[key] -= amount;
        if (remaining[key] == 0) {
          missingTypes--;
        }
        descend();
      } else {
        if (key == remaining.length && !triedPlacement[depth]) {
          // Some slots reject all remaining keys. Skipping must be an explicit branch.
          chosen[depth] = -1;
          amounts[depth] = 0;
          descend();
        } else {
          // A solution that leaves this slot empty could move a compatible output
          // here from a later slot. Once all placements failed, skipping cannot help.
          backtrack();
        }
      }
    }

    private void descend() {
      depth++;
      if (depth < next.length) {
        next[depth] = 0;
        triedPlacement[depth] = false;
      }
    }

    private void backtrack() {
      if (depth == 0) {
        phase = Phase.NEXT;
        return;
      }
      depth--;
      int key = chosen[depth];
      if (key >= 0) {
        if (remaining[key] == 0) {
          missingTypes++;
        }
        remaining[key] += amounts[depth];
      }
    }

    private void finish() {
      for (int i = 0; i < depth; i++) {
        if (chosen[i] >= 0) {
          insertions.add(new RecipePlan.Insertion<>(emptySlots.get(i), current.outputKeys.get(chosen[i]), amounts[i]));
        }
      }
      selected = new RecipePlan<>(
          snapshot.revision(),
          current.recipe,
          outputOnly ? Map.of() : current.input,
          insertions);
      terminal = Status.SELECTED;
    }
  }
}
