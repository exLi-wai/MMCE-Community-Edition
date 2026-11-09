package com.lw.modularmachine.machine.assembly;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal capacitated matching of detached resource indices; no platform
 * access.
 */
@ApiStatus.Internal
final class MaterialAllocation {
  record Demand(long count, List<Integer> resources) {
    Demand {
      resources = List.copyOf(resources);
    }
  }

  record Plan(List<Map<Integer, Long>> full, List<Map<Integer, Long>> partial, long[] remaining) {
  }

  record Replacement(List<Map<Integer, Long>> assignments, int resource) {
  }

  /** Reassign one position without reducing any remaining group's reservation. */
  static Replacement reassign(long[] capacities, List<Demand> demands, int target, Set<Integer> excluded) {
    var current = demands.get(target);
    if (current.count() == 0) {
      return null;
    }
    var retry = new ArrayList<Demand>();
    retry.add(new Demand(1, current.resources().stream().filter(resource -> !excluded.contains(resource)).toList()));
    for (int group = 0; group < demands.size(); group++) {
      var demand = demands.get(group);
      retry.add(new Demand(demand.count() - (group == target ? 1 : 0), demand.resources()));
    }
    var allocation = allocate(capacities, retry);
    for (int group = 0; group < retry.size(); group++) {
      if (allocation.get(group).values().stream().mapToLong(Long::longValue).sum() != retry.get(group).count()) {
        return null;
      }
    }
    int resource = allocation.getFirst().keySet().iterator().next();
    var result = new ArrayList<Map<Integer, Long>>();
    for (int group = 0; group < demands.size(); group++) {
      var assigned = new LinkedHashMap<>(allocation.get(group + 1));
      if (group == target) {
        assigned.merge(resource, 1L, Long::sum);
      }
      result.add(assigned);
    }
    return new Replacement(result, resource);
  }

  static Plan plan(long[] quantity, int[] unit, List<Demand> demands) {
    var remnants = new ArrayList<Integer>();
    for (int resource = 0; resource < quantity.length; resource++) {
      if (quantity[resource] % unit[resource] > 0) {
        remnants.add(resource);
      }
    }
    remnants
        .sort(java.util.Comparator.comparingLong((Integer resource) -> quantity[resource] % unit[resource]).reversed());
    var capacity = new long[quantity.length + remnants.size()];
    for (int resource = 0; resource < quantity.length; resource++) {
      capacity[resource] = quantity[resource] / unit[resource];
    }
    Arrays.fill(capacity, quantity.length, capacity.length, 1);
    var expanded = new ArrayList<Demand>();
    for (var demand : demands) {
      var allowed = new ArrayList<>(demand.resources());
      for (int index = 0; index < remnants.size(); index++) {
        if (demand.resources().contains(remnants.get(index))) {
          allowed.add(quantity.length + index);
        }
      }
      expanded.add(new Demand(demand.count(), allowed));
    }
    // First maximize complete placements, then add each remainder in descending
    // volume order. Residual edges may reroute full allocations, so a broad group
    // can use a partial B while its full A is moved to an A-only group.
    var matching = allocate(capacity, expanded, quantity.length);
    var full = new ArrayList<Map<Integer, Long>>();
    var partial = new ArrayList<Map<Integer, Long>>();
    var remaining = new long[demands.size()];
    for (int group = 0; group < demands.size(); group++) {
      var complete = new LinkedHashMap<Integer, Long>();
      var incomplete = new LinkedHashMap<Integer, Long>();
      remaining[group] = demands.get(group).count();
      for (var entry : matching.get(group).entrySet()) {
        if (entry.getKey() < quantity.length) {
          complete.put(entry.getKey(), entry.getValue());
        } else {
          incomplete.put(remnants.get(entry.getKey() - quantity.length), entry.getValue());
        }
        remaining[group] -= entry.getValue();
      }
      full.add(complete);
      partial.add(incomplete);
    }
    return new Plan(full, partial, remaining);
  }

  private static final class Edge {
    final int to;
    final long initial;
    long remaining;
    Edge reverse;

    Edge(int to, long capacity) {
      this.to = to;
      initial = remaining = capacity;
    }
  }

  private MaterialAllocation() {
  }

  static List<Map<Integer, Long>> allocate(long[] capacities, List<Demand> demands) {
    return allocate(capacities, demands, capacities.length);
  }

  private static List<Map<Integer, Long>> allocate(long[] capacities, List<Demand> demands, int initialResources) {
    int source = capacities.length + demands.size(), sink = source + 1;
    var graph = new ArrayList<List<Edge>>(sink + 1);
    for (int node = 0; node <= sink; node++) {
      graph.add(new ArrayList<>());
    }
    var sourceEdges = new ArrayList<Edge>();
    for (int resource = 0; resource < capacities.length; resource++) {
      sourceEdges.add(connect(graph, source, resource, resource < initialResources ? capacities[resource] : 0));
    }
    for (int group = 0; group < demands.size(); group++) {
      var demand = demands.get(group);
      int node = capacities.length + group;
      connect(graph, node, sink, demand.count());
      for (int resource : demand.resources()) {
        connect(graph, resource, node, Math.min(capacities[resource], demand.count()));
      }
    }
    maximize(graph, source, sink);
    for (int resource = initialResources; resource < capacities.length; resource++) {
      sourceEdges.get(resource).remaining = capacities[resource];
      maximize(graph, source, sink);
    }
    var result = new ArrayList<Map<Integer, Long>>(demands.size());
    for (int group = 0; group < demands.size(); group++) {
      var allocated = new LinkedHashMap<Integer, Long>();
      for (var edge : graph.get(capacities.length + group)) {
        if (edge.to < capacities.length && edge.reverse.initial > edge.reverse.remaining) {
          allocated.put(edge.to, edge.reverse.initial - edge.reverse.remaining);
        }
      }
      result.add(allocated);
    }
    return result;
  }

  private static void maximize(List<List<Edge>> graph, int source, int sink) {
    var path = new Edge[sink + 1];
    var parent = new int[sink + 1];
    var queue = new ArrayDeque<Integer>();
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        throw new CancellationException("Material planning interrupted");
      }
      Arrays.fill(parent, -1);
      parent[source] = source;
      queue.clear();
      queue.add(source);
      while (!queue.isEmpty() && parent[sink] < 0) {
        int node = queue.removeFirst();
        for (var edge : graph.get(node)) {
          if (edge.remaining > 0 && parent[edge.to] < 0) {
            parent[edge.to] = node;
            path[edge.to] = edge;
            queue.addLast(edge.to);
          }
        }
      }
      if (parent[sink] < 0) {
        break;
      }
      long count = Long.MAX_VALUE;
      for (int node = sink; node != source; node = parent[node]) {
        count = Math.min(count, path[node].remaining);
      }
      for (int node = sink; node != source; node = parent[node]) {
        path[node].remaining -= count;
        path[node].reverse.remaining += count;
      }
    }
  }

  private static Edge connect(List<List<Edge>> graph, int from, int to, long capacity) {
    if (capacity < 0) {
      throw new IllegalArgumentException("Negative material capacity");
    }
    var edge = new Edge(to, capacity);
    var reverse = new Edge(from, 0);
    edge.reverse = reverse;
    reverse.reverse = edge;
    graph.get(from).add(edge);
    graph.get(to).add(reverse);
    return edge;
  }
}
