package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Shared reentry guard for read-only upgrade predicates and notifications. */
@ApiStatus.Internal
final class UpgradeCallbackGuard {
  private static final Set<UpgradeBusOwner> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());

  private UpgradeCallbackGuard() {
  }

  static boolean contains(UpgradeBusOwner factory) {
    return ACTIVE.contains(factory);
  }

  static boolean any() {
    return !ACTIVE.isEmpty();
  }

  static boolean enter(UpgradeBusOwner factory) {
    UpgradeHandlerRegistry.requireServer();

    return ACTIVE.add(factory);
  }

  static void exit(UpgradeBusOwner factory) {
    ACTIVE.remove(factory);
  }

  static void clear() {
    ACTIVE.clear();
  }
}
