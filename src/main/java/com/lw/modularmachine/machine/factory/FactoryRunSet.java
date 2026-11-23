package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.recipe.RecipeRunState;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Immutable collection of independent paid factory runs. */
public final class FactoryRunSet {
  private static final FactoryRunSet EMPTY = new FactoryRunSet(List.of());
  private final List<FrozenRecipeRun> runs;
  private Set<ResourceLocation> recipeIds;

  private FactoryRunSet(List<FrozenRecipeRun> runs) {
    this.runs = List.copyOf(runs);
  }

  public static FactoryRunSet empty() {
    return EMPTY;
  }

  public static FactoryRunSet ofRuns(List<FrozenRecipeRun> runs) {
    Objects.requireNonNull(runs, "runs");
    var ids = new HashSet<UUID>();
    for (var run : runs) {
      if (run == null || !ids.add(run.runId())) {
        throw new IllegalArgumentException("Null or duplicate factory run");
      }
    }
    return new FactoryRunSet(runs);
  }

  public List<FrozenRecipeRun> runs() {
    return runs;
  }

  /**
   * Thread admission consults the same immutable batch for many catalog
   * candidates.
   */
  public boolean containsRecipe(ResourceLocation recipe) {
    if (runs.size() <= 1) {
      return !runs.isEmpty() && runs.getFirst().recipeId().equals(recipe);
    }
    if (recipeIds == null) {
      var ids = new HashSet<ResourceLocation>();
      runs.forEach(run -> ids.add(run.recipeId()));
      recipeIds = Set.copyOf(ids);
    }
    return recipeIds.contains(recipe);
  }

  public Optional<FrozenRecipeRun> find(UUID runId) {
    Objects.requireNonNull(runId, "runId");
    return runs.stream().filter(run -> run.runId().equals(runId)).findFirst();
  }

  public FactoryRunSet add(FrozenRecipeRun run) {
    return addAll(List.of(Objects.requireNonNull(run, "run")));
  }

  public FactoryRunSet addAll(List<FrozenRecipeRun> additions) {
    Objects.requireNonNull(additions, "additions");
    var ids = new HashSet<UUID>();
    for (var run : runs) {
      ids.add(run.runId());
    }
    for (var run : additions) {
      if (run == null || !ids.add(run.runId())) {
        throw new IllegalArgumentException("Null or duplicate factory run");
      }
    }
    if (additions.isEmpty()) {
      return this;
    }
    var next = new ArrayList<FrozenRecipeRun>(Math.addExact(runs.size(), additions.size()));
    next.addAll(runs);
    next.addAll(additions);
    return new FactoryRunSet(next);
  }

  public FactoryRunSet replaceAll(Map<UUID, FrozenRecipeRun> replacements) {
    Objects.requireNonNull(replacements, "replacements");
    if (replacements.isEmpty()) {
      return this;
    }
    var next = new ArrayList<>(runs);
    var replaced = new HashSet<UUID>();
    var retainedIds = recipeIds;
    for (int i = 0; i < next.size(); i++) {
      var current = next.get(i);
      var replacement = replacements.get(current.runId());
      if (replacement != null) {
        if (!replacement.runId().equals(current.runId())) {
          throw new IllegalArgumentException("Replacement run UUID changed");
        }
        next.set(i, replacement);
        if (!replacement.recipeId().equals(current.recipeId())) {
          retainedIds = null;
        }
        replaced.add(current.runId());
      }
    }
    if (replaced.size() != replacements.size()) {
      throw new IllegalArgumentException("Unknown factory run replacement");
    }
    var updated = new FactoryRunSet(next);
    updated.recipeIds = retainedIds;
    return updated;
  }

  public FactoryRunSet replace(FrozenRecipeRun run) {
    Objects.requireNonNull(run, "run");
    var next = new ArrayList<>(runs);
    for (int i = 0; i < next.size(); i++) {
      if (next.get(i).runId().equals(run.runId())) {
        next.set(i, run);
        return new FactoryRunSet(next);
      }
    }
    throw new IllegalArgumentException("Unknown factory run");
  }

  public FactoryRunSet remove(UUID runId) {
    Objects.requireNonNull(runId, "runId");
    var next = runs.stream().filter(run -> !run.runId().equals(runId)).toList();
    if (next.size() == runs.size()) {
      throw new IllegalArgumentException("Unknown factory run");
    }
    return new FactoryRunSet(next);
  }

  public FactoryRunSet applyFailureDecisions(Map<UUID, FrozenRecipeRun> replacements, Set<UUID> discarded) {
    Objects.requireNonNull(replacements, "replacements");
    Objects.requireNonNull(discarded, "discarded");
    if (replacements.isEmpty() && discarded.isEmpty()) {
      return this;
    }
    if (replacements.keySet().stream().anyMatch(discarded::contains)) {
      throw new IllegalArgumentException("A failed run cannot be both replaced and discarded");
    }
    var next = new ArrayList<FrozenRecipeRun>(runs.size());
    var resolved = new HashSet<UUID>();
    for (var current : runs) {
      if (discarded.contains(current.runId())) {
        if (current.pendingOutput() || current.state() != RecipeRunState.RUNNING) {
          throw new IllegalArgumentException("Only active paid runs may be discarded");
        }
        resolved.add(current.runId());
        continue;
      }
      var replacement = replacements.get(current.runId());
      if (replacement != null) {
        if (!current.sameRecipe(replacement)
            || current.state() != RecipeRunState.RUNNING
            || replacement.state() != RecipeRunState.RUNNING
            || replacement.progress() > current.progress()
            || replacement.lastProgressTick() <= current.lastProgressTick()) {
          throw new IllegalArgumentException("Invalid resource failure run replacement");
        }
        next.add(replacement);
        resolved.add(current.runId());
      } else {
        next.add(current);
      }
    }
    var expectedIds = new HashSet<>(replacements.keySet());
    expectedIds.addAll(discarded);
    if (!resolved.equals(expectedIds)) {
      throw new IllegalArgumentException("Unknown resource failure run");
    }

    return new FactoryRunSet(next);
  }
}
