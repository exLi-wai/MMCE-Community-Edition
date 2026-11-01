package com.lw.modularmachine.machine.factory;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;

/**
 * Declarative restrictions are independent of numerical effects and callback
 * results.
 */
public record FactoryUpgradeRestrictions(
    Set<ResourceLocation> incompatible,
    Optional<ResourceLocation> exclusiveGroup,
    int maximumCount) {
  public static final int MAX_REFERENCES = 64;
  public static final int MAX_DIAGNOSTICS = 64;
  public static final FactoryUpgradeRestrictions NONE = new FactoryUpgradeRestrictions(
      Set.of(),
      Optional.empty(),
      Integer.MAX_VALUE);

  public FactoryUpgradeRestrictions {
    incompatible = Set.copyOf(incompatible);
    Objects.requireNonNull(exclusiveGroup, "exclusiveGroup");
    if (maximumCount < 1
        || incompatible.size() > MAX_REFERENCES
        || incompatible.stream().anyMatch(id -> id.getPath().isEmpty() || id.toString().length() > 256)
        || exclusiveGroup.isPresent()
            && (exclusiveGroup.orElseThrow().getPath().isEmpty()
                || exclusiveGroup.orElseThrow().toString().length() > 256)) {
      throw new IllegalArgumentException("Invalid upgrade restrictions");
    }
  }

  public record Installed(ResourceLocation definition, int count, FactoryUpgradeRestrictions restrictions) {
    public Installed {
      Objects.requireNonNull(definition, "definition");
      Objects.requireNonNull(restrictions, "restrictions");
      if (count < 1
          || definition.getPath().isEmpty()
          || definition.toString().length() > 256
          || restrictions.incompatible().contains(definition)) {
        throw new IllegalArgumentException("Invalid installed upgrade restriction source");
      }
    }
  }

  public enum Reason {
    INCOMPATIBLE,
    EXCLUSIVE_GROUP,
    COUNT_LIMIT
  }

  public record Violation(
      Reason reason,
      List<ResourceLocation> upgrades,
      Optional<ResourceLocation> group,
      long actual,
      int allowed) {
    public Violation {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(group, "group");
      upgrades = List.copyOf(upgrades);
      if (upgrades.isEmpty() || upgrades.size() > MAX_DIAGNOSTICS || allowed < 1 || actual <= allowed) {
        throw new IllegalArgumentException("Invalid upgrade conflict diagnostic");
      }
    }
  }

  public record Result(int totalViolations, List<Violation> violations) {
    public Result {
      violations = List.copyOf(violations);
      if (totalViolations < 0
          || violations.size() > MAX_DIAGNOSTICS
          || violations.size() > totalViolations
          || (totalViolations == 0) != violations.isEmpty()) {
        throw new IllegalArgumentException("Invalid upgrade restriction result");
      }
    }

    public boolean valid() {
      return totalViolations == 0;
    }

    public List<ResourceLocation> upgradeIds() {
      return violations.stream().flatMap(violation -> violation.upgrades().stream()).distinct().sorted()
          .limit(MAX_DIAGNOSTICS).toList();
    }
  }

  private record Pair(ResourceLocation first, ResourceLocation second) {
  }

  public static FactoryUpgradeRestrictions parse(JsonObject root, ResourceLocation self) {
    var incompatible = new TreeSet<ResourceLocation>();
    if (root.has("incompatible_upgrades")) {
      var values = root.get("incompatible_upgrades");
      if (!values.isJsonArray() || values.getAsJsonArray().size() > MAX_REFERENCES) {
        throw new IllegalArgumentException("incompatible_upgrades must be a bounded identifier list");
      }
      for (var value : values.getAsJsonArray()) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
          throw new IllegalArgumentException("incompatible_upgrades contains a non-string identifier");
        }
        var id = ResourceLocation.parse(value.getAsString());
        if (id.equals(self) || !incompatible.add(id)) {
          throw new IllegalArgumentException("Duplicate/self upgrade conflict; use max_count for quantity limits");
        }
      }
    }
    Optional<ResourceLocation> group = Optional.empty();
    if (root.has("exclusive_group")) {
      var value = root.get("exclusive_group");
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException("exclusive_group must be an identifier");
      }
      group = Optional.of(ResourceLocation.parse(value.getAsString()));
    }
    int maximum = Integer.MAX_VALUE;
    if (root.has("max_count")) {
      var value = root.get("max_count");
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
        throw new IllegalArgumentException("max_count must be a positive int");
      }
      maximum = value.getAsBigDecimal().intValueExact();
    }

    return new FactoryUpgradeRestrictions(incompatible, group, maximum);
  }

  public static Result evaluate(Collection<Installed> installed) {
    var counts = new TreeMap<ResourceLocation, Long>();
    var policies = new TreeMap<ResourceLocation, FactoryUpgradeRestrictions>();
    for (var source : installed) {
      counts.merge(source.definition(), (long) source.count(), Math::addExact);
      var previous = policies.putIfAbsent(source.definition(), source.restrictions());
      if (previous != null && !previous.equals(source.restrictions())) {
        throw new IllegalArgumentException("Inconsistent restrictions for the same upgrade definition");
      }
    }
    int total = 0;
    var diagnostics = new ArrayList<Violation>();
    var groups = new TreeMap<ResourceLocation, Set<ResourceLocation>>();
    var seenPairs = new HashSet<Pair>();
    for (var entry : policies.entrySet()) {
      var id = entry.getKey();
      var policy = entry.getValue();
      if (counts.get(id) > policy.maximumCount()) {
        total++;
        if (diagnostics.size() < MAX_DIAGNOSTICS) {
          diagnostics.add(
              new Violation(Reason.COUNT_LIMIT, List.of(id), Optional.empty(), counts.get(id), policy.maximumCount()));
        }
      }
      if (policy.exclusiveGroup().isPresent()) {
        groups.computeIfAbsent(policy.exclusiveGroup().orElseThrow(), ignored -> new TreeSet<>()).add(id);
      }
      for (var other : policy.incompatible().stream().sorted().toList()) {
        if (!policies.containsKey(other)) {
          continue;
        }
        var pair = id.compareTo(other) < 0 ? new Pair(id, other) : new Pair(other, id);
        if (seenPairs.add(pair)) {
          total++;
          if (diagnostics.size() < MAX_DIAGNOSTICS) {
            diagnostics
                .add(new Violation(Reason.INCOMPATIBLE, List.of(pair.first(), pair.second()), Optional.empty(), 2, 1));
          }
        }
      }
    }
    for (var group : groups.entrySet()) {
      if (group.getValue().size() > 1) {
        total++;
        if (diagnostics.size() < MAX_DIAGNOSTICS) {
          diagnostics.add(
              new Violation(
                  Reason.EXCLUSIVE_GROUP,
                  group.getValue().stream().limit(MAX_DIAGNOSTICS).toList(),
                  Optional.of(group.getKey()),
                  group.getValue().size(),
                  1));
        }
      }
    }

    return new Result(total, diagnostics);
  }
}
