package com.lw.modularmachine.machine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
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
  private static final ArrayDeque<Work> READY = new ArrayDeque<>();
  private static final Set<Work> MEMBERS = Collections.newSetFromMap(new IdentityHashMap<>());
  private static final Map<NetworkPortBlockEntity, Work> PORTS = new IdentityHashMap<>();

  /**
   * One bounded visit in the shared network budget, including order observation.
   */
  public interface Work {
    MinecraftServer server();

    boolean current();

    void networkStep();
  }

  private NetworkPortService() {
  }

  public static void add(NetworkPortBlockEntity port) {
    if (port.getLevel() instanceof ServerLevel && !PORTS.containsKey(port)) {
      var work = new PortWork(port);
      PORTS.put(port, work);
      add(work);
    }
  }

  public static void remove(NetworkPortBlockEntity port) {
    var work = PORTS.remove(port);
    if (work != null) {
      remove(work);
    }
  }

  public static void add(Work work) {
    if (MEMBERS.add(work)) {
      READY.addLast(work);
    }
  }

  public static void remove(Work work) {
    if (MEMBERS.remove(work)) {
      READY.remove(work);
    }
    if (work instanceof PortWork port) {
      PORTS.remove(port.port());
    }
  }

  private record PortWork(NetworkPortBlockEntity port) implements Work {
    @Override
    public MinecraftServer server() {
      return port.getLevel() instanceof ServerLevel level ? level.getServer() : null;
    }

    @Override
    public boolean current() {
      return port.getLevel() instanceof ServerLevel level
          && !port.isRemoved()
          && level.hasChunkAt(port.getBlockPos())
          && level.getBlockEntity(port.getBlockPos()) == port;
    }

    @Override
    public void networkStep() {
      port.networkStep();
    }
  }

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post event) {
    long deadline = System.nanoTime() + 2_000_000L;
    int visits = Math.min(64, READY.size());
    for (int work = 0; work < visits && !READY.isEmpty() && System.nanoTime() < deadline; work++) {
      var workItem = READY.removeFirst();
      if (workItem.server() != event.getServer() || !workItem.current()) {
        remove(workItem);
        continue;
      }
      try {
        workItem.networkStep();
      } catch (RuntimeException failure) {
        remove(workItem);
        ModularMachine.LOGGER.error("Network work stopped for {}", workItem, failure);
      }
      if (MEMBERS.contains(workItem)) {
        READY.addLast(workItem);
      }
    }
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    READY.clear();
    MEMBERS.clear();
    PORTS.clear();
  }
}
