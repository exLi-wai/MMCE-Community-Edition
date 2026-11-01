package com.lw.modularmachine.util.callback;

import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Copy-on-write callbacks; a domain-owned hook runs before each changed
 * publication.
 */
public final class ServerCallbackRegistry<T> {
  public static final int MAX_HANDLERS = 1024;
  private final Runnable beforePublish;
  private Map<ResourceLocation, T> handlers = Map.of();
  private List<Map.Entry<ResourceLocation, T>> orderedHandlers = List.of();

  public ServerCallbackRegistry(Runnable beforePublish) {
    this.beforePublish = Objects.requireNonNull(beforePublish, "beforePublish");
  }

  public boolean register(ResourceLocation id, T handler) {
    requireServer();
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(handler, "handler");
    if (id.toString().length() > 256 || !handlers.containsKey(id) && handlers.size() >= MAX_HANDLERS) {
      throw new IllegalArgumentException("Server callback handler limit exceeded");
    }
    if (handlers.get(id) == handler) {
      return false;
    }
    var updated = new LinkedHashMap<>(handlers);
    updated.put(id, handler);
    publish(updated);

    return true;
  }

  public boolean remove(ResourceLocation id) {
    requireServer();
    if (!handlers.containsKey(id)) {
      return false;
    }
    var updated = new LinkedHashMap<>(handlers);
    updated.remove(id);
    publish(updated);

    return true;
  }

  public void invalidate(ResourceLocation id) {
    requireServer();
    if (!handlers.containsKey(id)) {
      throw new IllegalArgumentException("Unknown server callback handler: " + id);
    }
    beforePublish.run();
  }

  private void publish(Map<ResourceLocation, T> updated) {
    var next = Map.copyOf(updated);
    var ordered = next.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString())).toList();
    beforePublish.run();
    handlers = next;
    orderedHandlers = ordered;
  }

  public T find(ResourceLocation id) {
    return handlers.get(id);
  }

  public Map<ResourceLocation, T> snapshot() {
    return handlers;
  }

  public List<Map.Entry<ResourceLocation, T>> orderedSnapshot() {
    return orderedHandlers;
  }

  public boolean isEmpty() {
    return handlers.isEmpty();
  }

  public void clear() {
    handlers = Map.of();
    orderedHandlers = List.of();
  }

  public static MinecraftServer requireServer() {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) {
      throw new IllegalStateException("Server callbacks require a running server");
    }
    ServerThreadGuard.require(server, "Server callback registration");

    return server;
  }
}
