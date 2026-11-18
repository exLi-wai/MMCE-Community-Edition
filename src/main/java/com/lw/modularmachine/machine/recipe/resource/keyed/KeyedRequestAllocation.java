package com.lw.modularmachine.machine.recipe.resource.keyed;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning.Status;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

final class KeyedRequestAllocation {
  private enum Phase {
    CONNECT,
    AUGMENT,
    CONFIRMED,
    FALLBACK
  }

  record Request(KeyedRecipeExecution.Port port, ResourceLocation key, long confirmed, long fallback) {
  }

  private final Node source = new Node();
  private final Node sink = new Node();
  private final List<Deficit> deficits = new ArrayList<>();
  private final List<Destination> destinations = new ArrayList<>();
  private final List<Supply> supplies = new ArrayList<>();
  private final Map<KeyedRecipeExecution.StockAddress, Request> requests = new LinkedHashMap<>();
  private Phase phase = Phase.CONNECT;
  private int deficitCursor;
  private int portCursor;
  private int supplyCursor;
  private Map<Node, Path> visited;
  private ArrayDeque<Node> pending;
  private Iterator<Edge> neighbors = Collections.emptyIterator();
  private Edge augment;
  private long increase;

  void addDeficit(ResourceLocation key, long amount) {
    deficits.add(new Deficit(key, source.connect(new Node(), amount)));
  }

  Map<KeyedRecipeExecution.StockAddress, Request> requests() {
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
      case AUGMENT -> augment();
      case CONFIRMED -> collectConfirmed();
      case FALLBACK -> collectFallback();
    };
  }

  private Status connect(List<KeyedRecipeExecution.Port> ports) {
    var deficit = deficits.get(deficitCursor);
    var port = ports.get(portCursor);
    if (deficitCursor == 0) {
      destinations.add(new Destination(port, new Node().connect(sink, port.hatch().requestSpace())));
    }
    deficit.edge().room -= Math.min(deficit.edge().room, port.hatch().outstandingRequest(deficit.key(), null));
    var destination = destinations.get(portCursor);
    long limit = Math.min(deficit.edge().room, destination.edge().room);
    if (limit > 0) {
      long available = Math.clamp(port.hatch().requestSupply(deficit.key(), limit), 0, limit);
      if (available > 0) {
        var edge = deficit.edge().target.connect(destination.edge().reverse.target, available);
        supplies.add(new Supply(port, deficit.key(), edge));
      }
    }
    if (++portCursor == ports.size()) {
      portCursor = 0;
      if (++deficitCursor == deficits.size()) {
        if (supplies.isEmpty()) {
          phase = Phase.FALLBACK;
          deficitCursor = 0;
        } else {
          phase = Phase.AUGMENT;
          beginPath();
        }
      }
    }
    return Status.PENDING;
  }

  private void beginPath() {
    visited = new HashMap<>();
    visited.put(source, new Path(null, Long.MAX_VALUE));
    pending = new ArrayDeque<>();
    pending.add(source);
    neighbors = Collections.emptyIterator();
  }

  private Status augment() {
    if (augment != null) {
      var previous = augment.reverse.target;
      augment.room -= increase;
      augment.reverse.room += increase;
      augment = visited.get(previous).edge();
      if (augment == null) {
        beginPath();
      }
    } else if (neighbors.hasNext()) {
      var edge = neighbors.next();
      if (edge.room > 0 && !visited.containsKey(edge.target)) {
        long amount = Math.min(edge.room, visited.get(edge.reverse.target).amount());
        visited.put(edge.target, new Path(edge, amount));
        if (edge.target == sink) {
          augment = edge;
          increase = amount;
        } else {
          pending.add(edge.target);
        }
      }
    } else {
      var next = pending.poll();
      if (next == null) {
        phase = Phase.CONFIRMED;
        visited = null;
        pending = null;
      } else {
        neighbors = next.edges.iterator();
      }
    }
    return Status.PENDING;
  }

  private Status collectConfirmed() {
    if (supplyCursor < supplies.size()) {
      var supply = supplies.get(supplyCursor++);
      long supplied = supply.edge().reverse.room;
      if (supplied > 0) {
        var address = new KeyedRecipeExecution.StockAddress(supply.port(), supply.key());
        requests.put(address, new Request(supply.port(), supply.key(), supplied, 0));
      }
    } else {
      phase = Phase.FALLBACK;
      deficitCursor = 0;
    }
    return Status.PENDING;
  }

  private Status collectFallback() {
    if (deficitCursor == deficits.size()) {
      return requests.isEmpty() ? Status.REJECTED : Status.READY;
    }
    var deficit = deficits.get(deficitCursor);
    if (deficit.edge().room == 0) {
      deficitCursor++;
    } else if (portCursor == destinations.size()) {
      return Status.REJECTED;
    } else {
      var destination = destinations.get(portCursor);
      long amount = Math.min(deficit.edge().room, destination.edge().room);
      if (amount > 0) {
        var address = new KeyedRecipeExecution.StockAddress(destination.port(), deficit.key());
        var confirmed = requests.remove(address);
        long supplied = confirmed == null ? 0 : confirmed.confirmed();
        requests.put(address, new Request(destination.port(), deficit.key(), supplied, amount));
        deficit.edge().room -= amount;
        destination.edge().room -= amount;
      }
      if (destination.edge().room == 0) {
        portCursor++;
      }
    }
    return Status.PENDING;
  }

  private record Deficit(ResourceLocation key, Edge edge) {
  }

  private record Destination(KeyedRecipeExecution.Port port, Edge edge) {
  }

  private record Supply(KeyedRecipeExecution.Port port, ResourceLocation key, Edge edge) {
  }

  private record Path(Edge edge, long amount) {
  }

  private static final class Node {
    private final List<Edge> edges = new ArrayList<>();

    private Edge connect(Node target, long room) {
      var forward = new Edge(target, room);
      var reverse = new Edge(this, 0);
      forward.reverse = reverse;
      reverse.reverse = forward;
      edges.add(forward);
      target.edges.add(reverse);
      return forward;
    }
  }

  private static final class Edge {
    private final Node target;
    private long room;
    private Edge reverse;

    private Edge(Node target, long room) {
      this.target = target;
      this.room = room;
    }
  }
}
