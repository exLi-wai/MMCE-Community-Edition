package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.machine.factory.FactoryUpgradeKubeJSHandlers;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubeJSServerHandlersTest {
  @Test
  void coldStartRetainsDeclarationsUntilServerInstallation() {
    var pending = new KubeJSServerHandlers.Pending();
    var next = snapshot(pending.begin());

    assertTrue(pending.stage(next));
    assertSame(next, pending.snapshot());
  }

  @Test
  void supersededReloadCannotRestoreItsDeclarations() {
    var pending = new KubeJSServerHandlers.Pending();
    var old = snapshot(pending.begin());
    assertTrue(pending.stage(old));
    var next = snapshot(pending.begin());

    assertNull(pending.snapshot());
    assertFalse(pending.stage(old));
    assertTrue(pending.stage(next));
    assertFalse(pending.stage(old));
    assertSame(next, pending.snapshot());
  }

  @Test
  void shutdownFenceRejectsWorkQueuedBeforeTheNextServer() {
    var pending = new KubeJSServerHandlers.Pending();
    var old = snapshot(pending.begin());
    assertTrue(pending.stage(old));
    pending.begin();
    pending.begin();

    assertNull(pending.snapshot());
    assertFalse(pending.stage(old));
    var restarted = snapshot(pending.begin());
    assertTrue(pending.stage(restarted));
    assertFalse(pending.stage(old));
    assertSame(restarted, pending.snapshot());
  }

  @Test
  void queuedSnapshotDoesNotRetainMutableRegistrationLists() {
    var machines = new ArrayList<KubeJSMachineEventRegistration.Handler>();
    var next = new KubeJSServerHandlers.Snapshot(
        1,
        machines,
        FactoryUpgradeKubeJSHandlers.Snapshot.EMPTY,
        List.of(),
        List.of(),
        List.of(),
        List.of());
    machines.add(null);

    assertTrue(next.machines().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> next.machines().add(null));
  }

  private static KubeJSServerHandlers.Snapshot snapshot(long generation) {
    return new KubeJSServerHandlers.Snapshot(
        generation,
        List.of(),
        FactoryUpgradeKubeJSHandlers.Snapshot.EMPTY,
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
