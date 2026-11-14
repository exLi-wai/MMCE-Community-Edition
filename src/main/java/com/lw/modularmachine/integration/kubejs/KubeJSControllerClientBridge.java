package com.lw.modularmachine.integration.kubejs;

import java.util.function.Consumer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal side-safe bridge: common plugin code never loads a client renderer.
 */
@ApiStatus.Internal
public final class KubeJSControllerClientBridge {
  private static Consumer<KubeJSControllerClientRegistration.Snapshot> installer;
  private static KubeJSControllerClientRegistration.Snapshot pending = KubeJSControllerClientRegistration.Snapshot.EMPTY;

  private KubeJSControllerClientBridge() {
  }

  public static synchronized void setInstaller(Consumer<KubeJSControllerClientRegistration.Snapshot> receiver) {
    installer = receiver;
    receiver.accept(pending);
  }

  public static synchronized void install(KubeJSControllerClientRegistration.Snapshot snapshot) {
    pending = snapshot;
    if (installer != null) {
      installer.accept(snapshot);
    }
  }
}
