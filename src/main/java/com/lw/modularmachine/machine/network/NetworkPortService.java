package com.lw.modularmachine.machine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Shared bounded main-thread work; no external storage callback enters recipe
 * planning.
 */
@ApiStatus.Internal
public final class NetworkPortService {
  private static final ArrayDeque<NetworkPortBlockEntity> READY = new ArrayDeque<>();
  private static final Set<NetworkPortBlockEntity> MEMBERS = Collections.newSetFromMap(new IdentityHashMap<>());

  private NetworkPortService() {
  }

  public static void add(NetworkPortBlockEntity port) {
    if (port.getLevel() instanceof ServerLevel && MEMBERS.add(port)) {
      READY.addLast(port);
    }
  }

  public static void remove(NetworkPortBlockEntity port) {
    if (MEMBERS.remove(port)) {
      READY.remove(port);
    }
  }

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post event) {
    long deadline = System.nanoTime() + 2_000_000L;
    int visits = Math.min(64, READY.size());
    for (int work = 0; work < visits && !READY.isEmpty() && System.nanoTime() < deadline; work++) {
      var port = READY.removeFirst();
      if (!(port.getLevel() instanceof ServerLevel level)
          || level.getServer() != event.getServer()
          || port.isRemoved()
          || !level.hasChunkAt(port.getBlockPos())
          || level.getBlockEntity(port.getBlockPos()) != port) {
        MEMBERS.remove(port);
        continue;
      }
      try {
        port.networkStep();
      } catch (RuntimeException failure) {
        MEMBERS.remove(port);
        ModularMachine.LOGGER.error(
            "Network port transfer stopped at {} in {}",
            port.getBlockPos(),
            level.dimension().location(),
            failure);
      }
      if (MEMBERS.contains(port)) {
        READY.addLast(port);
      }
    }
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    READY.clear();
    MEMBERS.clear();
  }
}
