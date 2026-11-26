package com.lw.modularmachine.machine.recipe.resource.keyed;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning.Status;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

final class KeyedRequestAllocation {
  private enum Phase {
    CONNECT,
    ALLOCATE_CONFIRMED,
    ENABLE_FALLBACK,
    ALLOCATE_FALLBACK,
    COLLECT_CONFIRMED,
    COLLECT_FALLBACK
  }

  record Request(KeyedRecipeExecution.Port port, ResourceLocation key, long confirmed, long fallback) {
  }

  private final Node source = new Node();
  private final Node sink = new Node();
  private final List<Deficit> deficits = new ArrayList<>();
  private final List<Edge> destinations = new ArrayList<>();
  private final List<Route> routes = new ArrayList<>();
  private final List<Request> requests = new ArrayList<>();
  private Phase phase = Phase.CONNECT;
  private int deficitCursor;
  private int portCursor;
  private int supplyCursor;
  private int unsatisfied;
  private boolean hasConfirmed;
  private boolean hasFallback;
  private Map<Node, Path> visited;
  private ArrayDeque<Node> pending;
  private Set<Node> queued;
  private Iterator<Edge> neighbors = Collections.emptyIterator();
  private Node visiting;
  private Edge augment;
  private long increase;

  void addDeficit(ResourceLocation key, long amount) {
    deficits.add(new Deficit(key, source.connect(new Node(), amount)));
  }

  List<Request> requests() {
    return requests;
  }

  Status step(List<KeyedRecipeExecution.Port> ports) {
    if (deficits.isEmpty()) {
      return Status.READY;
    }
    if (ports.isEmpty()) {
      return Status.REJECTED;
    }
    return switch (phase) {
      case CONNECT -> connect(ports);
      case ALLOCATE_CONFIRMED, ALLOCATE_FALLBACK -> augment();
      case ENABLE_FALLBACK -> enableFallback();
      case COLLECT_CONFIRMED, COLLECT_FALLBACK -> collectRequests();
    };
  }

  private Status connect(List<KeyedRecipeExecution.Port> ports) {
    var deficit = deficits.get(deficitCursor);
    var port = ports.get(portCursor);
    if (deficitCursor == 0) {
      destinations.add(new Node().connect(sink, port.hatch().requestSpace()));
    }
    deficit.edge().room -= Math.min(deficit.edge().room, port.hatch().outstandingRequest(deficit.key(), null));
    var destination = destinations.get(portCursor);
    long limit = Math.min(deficit.edge().room, destination.room);
    if (limit > 0 && port.hatch().canRequest(deficit.key())) {
      long available = Math.clamp(port.hatch().requestSupply(deficit.key(), limit), 0, limit);
      var from = deficit.edge().target;
      var to = destination.reverse.target;
      Edge confirmed = available == 0 ? null : from.connect(to, available, 0);
      Edge fallback = available == limit ? null : from.connect(to, 0, 1);
      routes.add(new Route(port, deficit.key(), confirmed, fallback, limit - available));
      hasConfirmed |= available > 0;
      hasFallback |= limit > available;
    }
    if (++portCursor == ports.size()) {
      portCursor = 0;
      if (deficit.edge().room > 0) {
        unsatisfied++;
      }
      if (++deficitCursor == deficits.size()) {
        if (routes.isEmpty() || unsatisfied == 0) {
          return Status.REJECTED;
        }
        if (hasConfirmed) {
          phase = Phase.ALLOCATE_CONFIRMED;
          beginPath();
        } else {
          phase = Phase.ENABLE_FALLBACK;
        }
      }
    }
    return Status.PENDING;
  }

  private void beginPath() {
    visited = new HashMap<>();
    visited.put(source, new Path(null, Long.MAX_VALUE, 0));
    pending = new ArrayDeque<>();
    pending.add(source);
    queued = new HashSet<>();
    queued.add(source);
    neighbors = Collections.emptyIterator();
  }

  private Status augment() {
    if (augment != null) {
      var previous = augment.reverse.target;
      augment.room -= increase;
      augment.reverse.room += increase;
      if (previous == source && augment.room == 0) {
        unsatisfied--;
        if (unsatisfied == 0) {
          phase = Phase.COLLECT_CONFIRMED;
          return Status.PENDING;
        }
      }
      augment = visited.get(previous).edge();
      if (augment == null) {
        beginPath();
      }
    } else if (neighbors.hasNext()) {
      var edge = neighbors.next();
      var path = visited.get(visiting);
      int cost = path.cost() + edge.cost;
      var previous = visited.get(edge.target);
      if (edge.room > 0 && edge.target != source && (previous == null || cost < previous.cost())) {
        long amount = Math.min(edge.room, path.amount());
        visited.put(edge.target, new Path(edge, amount, cost));
        if (edge.target == sink && phase == Phase.ALLOCATE_CONFIRMED) {
          augment = edge;
          increase = amount;
        } else if (edge.target != sink && queued.add(edge.target)) {
          pending.add(edge.target);
        }
      }
    } else {
      visiting = pending.poll();
      if (visiting == null) {
        var path = visited.get(sink);
        if (path != null) {
          augment = path.edge();
          increase = path.amount();
        } else if (phase == Phase.ALLOCATE_CONFIRMED && hasFallback) {
          phase = Phase.ENABLE_FALLBACK;
        } else {
          return Status.REJECTED;
        }
      } else {
        queued.remove(visiting);
        neighbors = visiting.edges.iterator();
      }
    }
    return Status.PENDING;
  }

  private Status enableFallback() {
    if (supplyCursor < routes.size()) {
      var route = routes.get(supplyCursor++);
      if (route.fallback != null) {
        // Every unconfirmed unit costs one. Reverse edges undo that cost when
        // a later assignment can use confirmed supply at another port.
        route.fallback.room = route.fallbackLimit;
      }
    } else {
      phase = Phase.ALLOCATE_FALLBACK;
      supplyCursor = 0;
      beginPath();
    }
    return Status.PENDING;
  }

  private Status collectRequests() {
    if (supplyCursor == routes.size()) {
      if (phase == Phase.COLLECT_FALLBACK || !hasFallback) {
        return Status.READY;
      }
      phase = Phase.COLLECT_FALLBACK;
      supplyCursor = 0;
      return Status.PENDING;
    }
    var route = routes.get(supplyCursor++);
    long confirmed = route.confirmed == null ? 0 : route.confirmed.reverse.room;
    long fallback = route.fallback == null ? 0 : route.fallback.reverse.room;
    // Recheck fully covered sources first so a shrinking deficit cannot spend
    // its last units on a fallback hint while confirmed supply is still pending.
    boolean publishInThisPass = phase == Phase.COLLECT_CONFIRMED ? fallback == 0 : fallback > 0;
    if ((confirmed > 0 || fallback > 0) && publishInThisPass) {
      requests.add(new Request(route.port, route.key, confirmed, fallback));
    }
    return Status.PENDING;
  }

  private record Deficit(ResourceLocation key, Edge edge) {
  }

  private record Route(
      KeyedRecipeExecution.Port port,
      ResourceLocation key,
      Edge confirmed,
      Edge fallback,
      long fallbackLimit) {
  }

  private record Path(Edge edge, long amount, int cost) {
  }

  private static final class Node {
    private final List<Edge> edges = new ArrayList<>();

    private Edge connect(Node target, long room) {
      return connect(target, room, 0);
    }

    private Edge connect(Node target, long room, int cost) {
      var forward = new Edge(target, room, cost);
      var reverse = new Edge(this, 0, -cost);
      forward.reverse = reverse;
      reverse.reverse = forward;
      edges.add(forward);
      target.edges.add(reverse);
      return forward;
    }
  }

  private static final class Edge {
    private final Node target;
    private final int cost;
    private long room;
    private Edge reverse;

    private Edge(Node target, long room, int cost) {
      this.target = target;
      this.room = room;
      this.cost = cost;
    }
  }
}
