package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.OrientationCompilation;
import com.lw.modularmachine.machine.recipe.RecipeModifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Optional predicates share structure subscriptions and budget; their
 * mismatches mean inactive, not invalid.
 */
public final class StructureModifierTracker {
  record Condition(int part, int group, MachineDefinition.Rule rule) {
  }

  static final class Plan {
    final List<StructureModifierDefinition> groups;
    final List<Condition> conditions;
    final Map<Integer, List<Integer>> replacements;

    Plan(List<StructureModifierDefinition> groups, List<Condition> conditions) {
      this.groups = List.copyOf(groups);
      this.conditions = List.copyOf(conditions);
      var mutable = new HashMap<Integer, List<Integer>>();
      for (int group = 0; group < groups.size(); group++) {
        if (groups.get(group).allowReplacement()) {
          for (int part : groups.get(group).partIndices()) {
            mutable.computeIfAbsent(part, ignored -> new ArrayList<>()).add(group);
          }
        }
      }
      var frozen = new HashMap<Integer, List<Integer>>();
      mutable.forEach((part, indices) -> frozen.put(part, List.copyOf(indices)));
      replacements = Map.copyOf(frozen);
    }

    CheckResult read(ServerLevel level, CompiledStructure pattern, Condition condition) {
      var position = pattern.position(condition.part());
      if (level.isOutsideBuildHeight(position) || !level.hasChunkAt(position)) {
        return CheckResult.UNAVAILABLE;
      }

      return condition.rule() != null
          && condition.rule().matches(level.getBlockState(position)) ? CheckResult.MATCH : CheckResult.MISMATCH;
    }

    Reference reference(ServerLevel level, CompiledStructure pattern) {
      var active = new boolean[groups.size()];
      Arrays.fill(active, true);
      int checks = 0;
      for (var condition : conditions) {
        CheckResult result;
        try {
          result = read(level, pattern, condition);
        } catch (RuntimeException invalid) {
          return new Reference(this, active, List.of(), VerificationFailure.CHECK_ERROR, condition.part(), checks + 1);
        }
        checks++;
        if (result == CheckResult.UNAVAILABLE) {
          return new Reference(
              this,
              active,
              List.of(),
              VerificationFailure.CHUNK_UNAVAILABLE,
              condition.part(),
              checks);
        }
        active[condition.group()] &= result == CheckResult.MATCH;
      }
      var rules = new ArrayList<RecipeModifier>();
      for (int index = 0; index < groups.size(); index++) {
        if (active[index]) {
          rules.addAll(groups.get(index).modifiers());
        }
      }

      return new Reference(this, active, List.copyOf(rules), VerificationFailure.NONE, -1, checks);
    }

    Reference referenceSnapshot(IntFunction<BlockState> states) {
      var active = new boolean[groups.size()];
      Arrays.fill(active, true);
      int checks = 0;
      for (var condition : conditions) {
        var state = states.apply(condition.part());
        checks++;
        if (state == null) {
          return new Reference(
              this,
              active,
              List.of(),
              VerificationFailure.CHUNK_UNAVAILABLE,
              condition.part(),
              checks);
        }
        active[condition.group()] &= condition.rule() != null && condition.rule().matches(state);
      }
      var rules = new ArrayList<RecipeModifier>();
      for (int index = 0; index < groups.size(); index++) {
        if (active[index]) {
          rules.addAll(groups.get(index).modifiers());
        }
      }

      return new Reference(this, active, List.copyOf(rules), VerificationFailure.NONE, -1, checks);
    }

    boolean allows(int part, boolean[] active) {
      for (int group : replacements.getOrDefault(part, List.of())) {
        if (active[group]) {
          return true;
        }
      }

      return false;
    }

    boolean allows(int part, Reference reference) {
      if (!reference.belongsTo(this)) {
        throw new IllegalArgumentException("Reference belongs to a different structure");
      }

      return allows(part, reference.active);
    }
  }

  /** Per-call full reference result, never a cached production certificate. */
  public static final class Reference {
    private final Plan source;
    private final boolean[] active;
    private final List<RecipeModifier> rules;
    private final VerificationFailure failure;
    private final int failedPart;
    private final int checks;

    private Reference(Plan source, boolean[] active, List<RecipeModifier> rules, VerificationFailure failure, int failedPart, int checks) {
      this.source = source;
      this.active = active;
      this.rules = rules;
      this.failure = failure;
      this.failedPart = failedPart;
      this.checks = checks;
    }

    boolean belongsTo(Plan plan) {
      return source == plan;
    }

    public List<RecipeModifier> rules() {
      return rules;
    }

    public VerificationFailure failure() {
      return failure;
    }

    public int failedPart() {
      return failedPart;
    }

    public int checks() {
      return checks;
    }
  }

  static final class Builder {
    private final List<StructureModifierDefinition> groups;
    private final MachineOrientation orientation;
    private final List<Condition> conditions = new ArrayList<>();
    private int group;
    private int part;
    private OrientationCompilation.RulePreparation preparation;
    private Plan result;

    Builder(MachineDefinition definition, MachineOrientation orientation) {
      groups = definition.structureModifiers();
      this.orientation = orientation;
      if (groups.isEmpty()) {
        result = new Plan(groups, List.of());
      }
    }

    int advance(int budget) {
      int used = 0;
      while (used < budget && result == null) {
        var current = groups.get(group);
        boolean supported = current.compilation().allows(orientation);
        if (supported && preparation == null) {
          preparation = current.compilation().prepareRules(orientation);
        }
        if (preparation != null && !preparation.complete()) {
          used += preparation.advance(budget - used);
          if (!preparation.complete() || used == budget) {
            break;
          }
        }
        conditions.add(
            new Condition(current.partIndices().get(part), group, supported ? preparation.result().get(part) : null));
        used++;
        if (++part == current.partIndices().size()) {
          part = 0;
          preparation = null;
          if (++group == groups.size()) {
            result = new Plan(groups, conditions);
          }
        }
      }

      return used;
    }

    boolean complete() {
      return result != null;
    }

    Plan result() {
      if (result == null) {
        throw new IllegalStateException("Structure modifier predicates are still preparing");
      }

      return result;
    }
  }

  private final Plan plan;
  private final CompiledStructure pattern;
  private final IncrementalVerifier verifier;
  private final Map<Integer, Integer> watchedGroups;
  private final Map<Integer, int[]> affectedGroups;
  private final boolean[] matched;
  private final int[] missing;
  private boolean[] active;
  private List<RecipeModifier> rules = List.of();

  StructureModifierTracker(CompiledStructure pattern, long now) {
    this.pattern = pattern;
    plan = pattern.modifierPlan();
    matched = new boolean[plan.conditions.size()];
    missing = new int[plan.groups.size()];
    active = new boolean[plan.groups.size()];
    var localGroups = new LinkedHashMap<Integer, List<Integer>>();
    for (int index = 0; index < plan.conditions.size(); index++) {
      var condition = plan.conditions.get(index);
      missing[condition.group()]++;
      int sectionGroup = pattern.groupFor(pattern.position(condition.part()));
      localGroups.computeIfAbsent(sectionGroup, ignored -> new ArrayList<>()).add(index);
    }
    var watch = new HashMap<Integer, Integer>();
    var checks = new ArrayList<int[]>();
    localGroups.forEach((original, indices) -> {
      watch.put(original, checks.size());
      checks.add(indices.stream().mapToInt(Integer::intValue).toArray());
    });
    watchedGroups = Map.copyOf(watch);
    verifier = checks.isEmpty() ? null : new IncrementalVerifier(checks.toArray(int[][]::new), now);
    var propagation = new HashMap<Integer, LinkedHashSet<Integer>>();
    for (var group : plan.groups) {
      var sections = new LinkedHashSet<Integer>();
      for (int partIndex : group.partIndices()) {
        sections.add(pattern.groupFor(pattern.position(partIndex)));
      }
      for (int section : sections) {
        propagation.computeIfAbsent(section, ignored -> new LinkedHashSet<>()).addAll(sections);
      }
    }
    var frozen = new HashMap<Integer, int[]>();
    propagation
        .forEach((section, affected) -> frozen.put(section, affected.stream().mapToInt(Integer::intValue).toArray()));
    affectedGroups = Map.copyOf(frozen);
  }

  int[] markDirty(int group, long now) {
    var local = watchedGroups.get(group);
    if (local != null) {
      verifier.markDirty(local, now);
    }

    return affectedGroups.getOrDefault(group, new int[0]);
  }

  void markAllDirty(long now) {
    if (verifier != null) {
      verifier.markAllDirty(now);
    }
  }

  int advance(ServerLevel level, int budget, long now, int timeout) {
    if (verifier == null) {
      return 0;
    }
    int used = verifier.advance(budget, now, timeout, index -> {
      var condition = plan.conditions.get(index);
      var result = plan.read(level, pattern, condition);
      if (result == CheckResult.UNAVAILABLE) {
        return result;
      }
      boolean value = result == CheckResult.MATCH;
      if (matched[index] != value) {
        matched[index] = value;
        missing[condition.group()] += value ? -1 : 1;
      }

      return CheckResult.MATCH;
    });
    if (used > 0 && verifier.canCommit()) {
      var next = new ArrayList<RecipeModifier>();
      var nextActive = new boolean[plan.groups.size()];
      for (int group = 0; group < missing.length; group++) {
        nextActive[group] = missing[group] == 0;
        if (nextActive[group]) {
          next.addAll(plan.groups.get(group).modifiers());
        }
      }
      active = nextActive;
      rules = List.copyOf(next);
    }

    return used;
  }

  boolean canCommit() {
    return verifier == null || verifier.canCommit();
  }

  void acceptSnapshot(Reference reference) {
    if (!reference.belongsTo(plan) || reference.failure() != VerificationFailure.NONE) {
      throw new IllegalArgumentException("Invalid structure modifier snapshot");
    }
    active = reference.active.clone();
    rules = reference.rules();
    Arrays.fill(missing, 0);
    for (int index = 0; index < matched.length; index++) {
      matched[index] = active[plan.conditions.get(index).group()];
      if (!matched[index]) {
        missing[plan.conditions.get(index).group()]++;
      }
    }
    if (verifier != null) {
      verifier.confirmSnapshot();
    }
  }

  VerificationFailure failure() {
    return verifier == null ? VerificationFailure.NONE : verifier.failure();
  }

  boolean invalid() {
    return verifier != null && verifier.state() == VerificationState.INVALID;
  }

  int failedPart() {
    return verifier == null || verifier.failurePosition() < 0 ? -1 : plan.conditions.get(verifier.failurePosition())
        .part();
  }

  boolean allows(int part) {
    return canCommit() && plan.allows(part, active);
  }

  List<RecipeModifier> rules() {
    if (!canCommit()) {
      throw new IllegalStateException("Structure modifier proof is pending or invalid");
    }

    return rules;
  }
}
