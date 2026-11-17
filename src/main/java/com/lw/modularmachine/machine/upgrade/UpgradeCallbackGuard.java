package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Shared reentry guard for read-only upgrade predicates and notifications. */
@ApiStatus.Internal
final class UpgradeCallbackGuard {
  private static final Set<UpgradeBusOwner> ACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());
  private static int descriptions;

  private UpgradeCallbackGuard() {
  }

  static boolean contains(UpgradeBusOwner factory) {
    return descriptions > 0 || ACTIVE.contains(factory);
  }

  static boolean any() {
    return !ACTIVE.isEmpty() || descriptions > 0;
  }

  static void enterDescription() {
    UpgradeHandlerRegistry.requireServer();
    if (any()) {
      throw new IllegalStateException("Reentrant upgrade description callback");
    }
    descriptions++;
  }

  static void exitDescription() {
    descriptions--;
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
    descriptions = 0;
  }
}
