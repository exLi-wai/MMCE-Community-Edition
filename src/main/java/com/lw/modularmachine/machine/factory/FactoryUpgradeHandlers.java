package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * Copy-on-write server registrations sharing the upgrade catalog invalidation
 * contract.
 */
final class FactoryUpgradeHandlers<T> {
  static final int MAX_HANDLERS = ServerCallbackRegistry.MAX_HANDLERS;

  private final ServerCallbackRegistry<T> handlers = new ServerCallbackRegistry<>(
      FactoryUpgradeService::invalidateDynamicEffects);

  boolean register(ResourceLocation id, T handler) {
    return handlers.register(id, handler);
  }

  boolean remove(ResourceLocation id) {
    return handlers.remove(id);
  }

  void invalidate(ResourceLocation id) {
    handlers.invalidate(id);
  }

  T find(ResourceLocation id) {
    return handlers.find(id);
  }

  boolean isEmpty() {
    return handlers.isEmpty();
  }

  void clear() {
    handlers.clear();
  }

  static MinecraftServer requireServer() {
    return ServerCallbackRegistry.requireServer();
  }
}
