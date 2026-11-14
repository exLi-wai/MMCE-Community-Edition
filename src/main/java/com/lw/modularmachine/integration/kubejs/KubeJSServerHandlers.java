package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeCommandActions;
import com.lw.modularmachine.machine.factory.FactoryUpgradeKubeJSHandlers;
import com.lw.modularmachine.machine.recipe.command.RecipeCommandService;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal server-script declarations, retained until a server can install
 * them.
 */
@ApiStatus.Internal
public final class KubeJSServerHandlers {
  record Snapshot(
      long generation,
      List<KubeJSMachineEventRegistration.Handler> machines,
      FactoryUpgradeKubeJSHandlers.Snapshot upgrades,
      List<KubeJSSmartInterfaceRegistration.Handler> smart,
      List<KubeJSRecipeLifecycleRegistration.Handler> recipes,
      List<KubeJSRecipeLifecycleRegistration.FailurePolicy> failures,
      List<RecipeCommandActions.Action> commands) {
    Snapshot {
      machines = List.copyOf(machines);
      upgrades = Objects.requireNonNull(upgrades);
      smart = List.copyOf(smart);
      recipes = List.copyOf(recipes);
      failures = List.copyOf(failures);
      commands = List.copyOf(commands);
    }
  }

  /**
   * Generation fencing is independent of whether cold startup has a server yet.
   */
  static final class Pending {
    private long generation;
    private Snapshot snapshot;

    long begin() {
      generation = Math.incrementExact(generation);
      snapshot = null;
      return generation;
    }

    boolean stage(Snapshot candidate) {
      if (candidate.generation() != generation) {
        return false;
      }
      snapshot = candidate;
      return true;
    }

    Snapshot snapshot() {
      return snapshot;
    }
  }

  private static final Pending PENDING = new Pending();
  private static boolean registered;
  private static MinecraftServer owner, stopping;
  private static long installedGeneration = -1;

  private KubeJSServerHandlers() {
  }

  static synchronized void register() {
    if (!registered) {
      NeoForge.EVENT_BUS.register(KubeJSServerHandlers.class);
      registered = true;
    }
  }

  static synchronized long begin() {
    return PENDING.begin();
  }

  static void stage(Snapshot snapshot) {
    MinecraftServer server;
    synchronized (KubeJSServerHandlers.class) {
      server = ServerLifecycleHooks.getCurrentServer();
      if (server != null && server == stopping || !PENDING.stage(snapshot)) {
        return;
      }
      owner = server;
    }
    if (server != null) {
      if (server.isSameThread()) {
        install(server, snapshot);
      } else {
        server.execute(() -> install(server, snapshot));
      }
    }
  }

  private static synchronized void install(MinecraftServer server, Snapshot snapshot) {
    if (snapshot == null
        || PENDING.snapshot() != snapshot
        || ServerLifecycleHooks.getCurrentServer() != server
        || owner != null && owner != server
        || stopping == server
        || installedGeneration == snapshot.generation()) {
      return;
    }
    ServerCallbackRegistry.requireServer();
    owner = server;
    installDomain(
        "machine",
        () -> KubeJSMachineEventRegistration.install(snapshot.machines()),
        () -> KubeJSMachineEventRegistration.install(List.of()));
    installDomain(
        "runtime upgrade",
        () -> FactoryUpgradeKubeJSHandlers.install(snapshot.upgrades()),
        () -> FactoryUpgradeKubeJSHandlers.install(FactoryUpgradeKubeJSHandlers.Snapshot.EMPTY));
    installDomain(
        "smart interface",
        () -> KubeJSSmartInterfaceHandlers.install(snapshot.smart()),
        () -> KubeJSSmartInterfaceHandlers.install(List.of()));
    installDomain(
        "recipe lifecycle",
        () -> KubeJSRecipeLifecycleHandlers.install(snapshot.recipes(), snapshot.failures()),
        () -> KubeJSRecipeLifecycleHandlers.install(List.of(), List.of()));
    installDomain(
        "recipe command",
        () -> RecipeCommandService.installKubeJS(snapshot.commands()),
        () -> RecipeCommandService.installKubeJS(List.of()));
    installedGeneration = snapshot.generation();
  }

  private static void installDomain(String domain, Runnable install, Runnable clear) {
    try {
      install.run();
    } catch (RuntimeException failure) {
      clear.run();
      ModularMachine.LOGGER.warn("KubeJS {} handler registration failed", domain, failure);
    }
  }

  @SubscribeEvent
  public static void starting(ServerAboutToStartEvent event) {
    synchronized (KubeJSServerHandlers.class) {
      install(event.getServer(), PENDING.snapshot());
    }
  }

  @SubscribeEvent
  public static synchronized void stopping(ServerStoppingEvent event) {
    if (owner == null || owner == event.getServer()) {
      stopping = event.getServer();
      PENDING.begin();
    }
  }

  @SubscribeEvent
  public static synchronized void stopped(ServerStoppedEvent event) {
    if (owner == null || owner == event.getServer()) {
      PENDING.begin();
      owner = null;
      stopping = null;
      installedGeneration = -1;
      KubeJSMachineEventRegistration.clearOnServerStop();
    }
  }
}
