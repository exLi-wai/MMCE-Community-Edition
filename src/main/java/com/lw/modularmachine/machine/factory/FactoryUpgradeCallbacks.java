package com.lw.modularmachine.machine.factory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Shared reentry guard for read-only upgrade predicates and notifications. */
final class FactoryUpgradeCallbacks {
  private static final Set<UpgradeBusOwner> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());

  private FactoryUpgradeCallbacks() {
  }

  static boolean contains(UpgradeBusOwner factory) {
    return ACTIVE.contains(factory);
  }

  static boolean any() {
    return !ACTIVE.isEmpty();
  }

  static boolean enter(UpgradeBusOwner factory) {
    FactoryUpgradeHandlers.requireServer();

    return ACTIVE.add(factory);
  }

  static void exit(UpgradeBusOwner factory) {
    ACTIVE.remove(factory);
  }

  static void clear() {
    ACTIVE.clear();
  }
}
