package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.PropertySpace;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureModifierDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal, pure projection intent; selected groups are never production proof.
 */
@ApiStatus.Internal
public final class ProjectionRules {
  private static final int MAX_WORK = 8_388_608;
  private static final int MAX_TOTAL_ALTERNATIVES = 1_048_576;

  public enum Failure {
    UNKNOWN_GROUP,
    DUPLICATE_GROUP,
    UNSUPPORTED_ORIENTATION,
    CONFLICT,
    UNSUPPORTED_LEGACY,
    WORK_LIMIT
  }

  public static final class SelectionException extends IllegalArgumentException {
    private final Failure failure;
    private final String group;
    private final String part;

    private SelectionException(Failure failure, String group, String part) {
      super("Projection modifier selection " + failure + ": " + group + "/" + part);
      this.failure = failure;
      this.group = group;
      this.part = part;
    }

    public Failure failure() {
      return failure;
    }

    public String group() {
      return group;
    }

    public String part() {
      return part;
    }
  }

  private static final class Budget {
    private int work;
    private int alternatives;

    void use(int amount) {
      if (Thread.currentThread().isInterrupted()) {
        throw new CancellationException("Projection modifier rules cancelled");
      }
      if (amount > MAX_WORK - work) {
        throw failure(Failure.WORK_LIMIT, "", "");
      }
      work += amount;
    }

    void alternatives(int amount) {
      if (amount > MAX_TOTAL_ALTERNATIVES - alternatives) {
        throw failure(Failure.WORK_LIMIT, "", "");
      }
      alternatives += amount;
    }
  }

  private ProjectionRules() {
  }

  /** Canonical declaration order makes equivalent selections share one cache. */
  public static List<String> normalizeSelection(MachineDefinition definition, List<String> selectedGroups) {
    if (selectedGroups.size() > StructureModifierDefinition.MAX_GROUPS) {
      throw failure(Failure.WORK_LIMIT, "", "");
    }
    var selected = new HashSet<String>();
    for (String group : selectedGroups) {
      if (group == null) {
        throw failure(Failure.UNKNOWN_GROUP, "", "");
      }
      if (!selected.add(group)) {
        throw failure(Failure.DUPLICATE_GROUP, group, "");
      }
    }
    var result = new ArrayList<String>(selected.size());
    for (var group : definition.sourceDefinition().structureModifiers()) {
      if (selected.remove(group.id())) {
        result.add(group.id());
      }
    }
    if (!selected.isEmpty()) {
      throw failure(Failure.UNKNOWN_GROUP, selected.iterator().next(), "");
    }

    return List.copyOf(result);
  }

  /**
   * Nonempty selections produce world-space constraints. Empty selections retain
   * the established basic assembly path, including its main-thread legacy
   * capture.
   */
  public static List<MachineDefinition.Rule> compile(MachineDefinition definition, MachineOrientation orientation, List<String> selectedGroups) {
    var normalized = normalizeSelection(definition, selectedGroups);
    if (!definition.allows(orientation)) {
      throw failure(Failure.UNSUPPORTED_ORIENTATION, "", "");
    }
    var basic = definition.rulesFor(orientation);
    if (normalized.isEmpty()) {
      return basic;
    }
    var selected = new HashSet<>(normalized);
    var constraints = new LinkedHashMap<Integer, MachineDefinition.Rule>();
    var replacements = new HashSet<Integer>();
    var requireBasic = new HashSet<Integer>();
    var lastGroup = new HashMap<Integer, String>();
    var budget = new Budget();
    for (var group : definition.sourceDefinition().structureModifiers()) {
      if (!selected.contains(group.id())) {
        continue;
      }
      if (!group.compilation().allows(orientation)) {
        throw failure(Failure.UNSUPPORTED_ORIENTATION, group.id(), "");
      }
      // Production's optional-group tracker matches these compiled predicates
      // directly against world states, including declared LEGACY predicates.
      var rules = group.compilation().rulesFor(orientation);
      for (int index = 0; index < group.partIndices().size(); index++) {
        int part = group.partIndices().get(index);
        String partId = definition.parts().get(part).id();
        var rule = rules.get(index);
        budget.use(1 + rule.alternatives().size());
        budget.alternatives(rule.alternatives().size());
        if (rule.port() != null || rule.alternatives().isEmpty()) {
          throw failure(Failure.CONFLICT, group.id(), partId);
        }
        var previous = constraints.get(part);
        if (previous != null) {
          rule = intersect(previous, rule, group.id(), partId, budget);
        }
        constraints.put(part, rule);
        lastGroup.put(part, group.id());
        if (group.allowReplacement()) {
          replacements.add(part);
        } else {
          requireBasic.add(part);
        }
      }
    }
    var result = new ArrayList<MachineDefinition.Rule>(basic.size());
    for (int index = 0; index < basic.size(); index++) {
      var basePart = definition.parts().get(index);
      var constraint = constraints.get(index);
      boolean needsBasic = constraint == null || !replacements.contains(index) || requireBasic.contains(index);
      budget.use(1);
      var rule = basic.get(index);
      if (needsBasic) {
        if (rule.port() == null && rule.alternatives().isEmpty()) {
          throw failure(Failure.UNSUPPORTED_ORIENTATION, lastGroup.getOrDefault(index, ""), basePart.id());
        }
        // Legacy base checks apply BlockState.rotate callbacks on the main thread.
        // A detached compiler cannot prove their inverse against arbitrary blocks.
        // Reject constrained rotated legacy bases instead of guessing a predicate.
        if (basePart.propertiesSpace() == PropertySpace.LEGACY
            && orientation != MachineOrientation.IDENTITY
            && rule.alternatives().stream().anyMatch(alternative -> !alternative.properties().isEmpty())) {
          throw failure(Failure.UNSUPPORTED_LEGACY, lastGroup.getOrDefault(index, ""), basePart.id());
        }
      }
      if (constraint != null) {
        if (needsBasic) {
          rule = intersect(rule, constraint, lastGroup.get(index), basePart.id(), budget);
        } else {
          rule = constraint;
        }
      }
      result.add(rule);
    }

    return List.copyOf(result);
  }

  private static MachineDefinition.Rule intersect(MachineDefinition.Rule left, MachineDefinition.Rule right, String group, String part, Budget budget) {
    if (left.port() != null || right.port() != null) {
      throw failure(Failure.CONFLICT, group, part);
    }
    var alternatives = new LinkedHashSet<MachineDefinition.Alternative>();
    var byBlock = new IdentityHashMap<Block, List<MachineDefinition.Alternative>>();
    for (var alternative : right.alternatives()) {
      budget.use(1);
      byBlock.computeIfAbsent(alternative.block(), ignored -> new ArrayList<>()).add(alternative);
    }
    for (var first : left.alternatives()) {
      budget.use(1);
      for (var second : byBlock.getOrDefault(first.block(), List.of())) {
        budget.use(1);
        budget.use(first.properties().size());
        var properties = new HashMap<Property<?>, Comparable<?>>(first.properties());
        boolean compatible = true;
        for (var entry : second.properties().entrySet()) {
          budget.use(1);
          var previous = properties.putIfAbsent(entry.getKey(), entry.getValue());
          if (previous != null && !previous.equals(entry.getValue())) {
            compatible = false;
            break;
          }
        }
        if (compatible) {
          budget.use(first.snapshot().properties().size() + second.snapshot().properties().size());
          var snapshots = new HashMap<>(first.snapshot().properties());
          snapshots.putAll(second.snapshot().properties());
          var snapshot = new StateOrientationAdapters.BlockSnapshot(first.snapshot().id(), first.block(), snapshots);
          if (alternatives.add(new MachineDefinition.Alternative(snapshot, properties))) {
            budget.alternatives(1);
          }
        }
      }
    }
    if (alternatives.isEmpty()) {
      throw failure(Failure.CONFLICT, group, part);
    }

    return new MachineDefinition.Rule(null, List.copyOf(alternatives));
  }

  private static SelectionException failure(Failure failure, String group, String part) {
    return new SelectionException(failure, group, part);
  }
}
