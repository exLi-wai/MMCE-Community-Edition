package com.lw.modularmachine.machine.smart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Pure assignment of explicit roles, retained unique types, and excess
 * fallback.
 */
public final class SmartInterfaceAssignment {
  public static final String AUTOMATIC = "*";

  public record Slot(int partIndex, String partId, String explicitType, String previousType) {
    public Slot {
      if (partIndex < 0 || partId == null || partId.isBlank() || explicitType == null || previousType == null) {
        throw new IllegalArgumentException("Invalid smart interface assignment slot");
      }
    }
  }

  private SmartInterfaceAssignment() {
  }

  public static Map<Integer, String> assign(Map<String, SmartInterfaceType> types, List<Slot> slots) {
    if (slots.size() > 4096 || types.size() > SmartInterfaceType.MAX_TYPES || !slots.isEmpty() && types.isEmpty()) {
      throw new IllegalArgumentException("Invalid smart interface assignment capacity");
    }
    if (types.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue().id()))) {
      throw new IllegalArgumentException("Smart interface assignment type key does not match its ID");
    }
    var ordered = new ArrayList<>(slots);
    ordered.sort(Comparator.comparing(Slot::partId).thenComparingInt(Slot::partIndex));
    var indices = new HashSet<Integer>();
    var ids = new HashSet<String>();
    var assigned = new HashMap<Integer, String>();
    var used = new HashSet<String>();
    for (var slot : ordered) {
      if (!indices.add(slot.partIndex()) || !ids.add(slot.partId())) {
        throw new IllegalArgumentException("Duplicate smart interface assignment slot");
      }
      if (!slot.explicitType().isEmpty()) {
        if (!types.containsKey(slot.explicitType()) || !used.add(slot.explicitType())) {
          throw new IllegalArgumentException("Unknown or duplicate explicit smart interface type");
        }
        assigned.put(slot.partIndex(), slot.explicitType());
      }
    }
    // Reserve explicit roles before retaining automatic values. Reordering the
    // definition or changing priorities does not reset a valid unique binding.
    for (var slot : ordered) {
      if (!assigned.containsKey(slot.partIndex())
          && types.containsKey(slot.previousType())
          && used.add(slot.previousType())) {
        assigned.put(slot.partIndex(), slot.previousType());
      }
    }
    var byPriority = types.values().stream()
        .sorted(Comparator.comparingInt(SmartInterfaceType::priority).reversed().thenComparing(SmartInterfaceType::id))
        .toList();
    int cursor = 0;
    for (var slot : ordered) {
      if (assigned.containsKey(slot.partIndex())) {
        continue;
      }
      while (cursor < byPriority.size() && used.contains(byPriority.get(cursor).id())) {
        cursor++;
      }
      String type = byPriority.get(cursor < byPriority.size() ? cursor : 0).id();
      assigned.put(slot.partIndex(), type);
      used.add(type);
    }

    return Map.copyOf(assigned);
  }
}
