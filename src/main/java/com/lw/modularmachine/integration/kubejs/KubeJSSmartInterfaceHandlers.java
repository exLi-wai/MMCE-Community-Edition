package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.smart.SmartInterfaceEvents;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Installs one native dispatcher so script reloads replace their listeners as a
 * unit.
 */
final class KubeJSSmartInterfaceHandlers {
  private static final ResourceLocation DISPATCHER_ID = ResourceLocation
      .fromNamespaceAndPath("kubejs", "mmcece_smart_interface_dispatcher");

  private KubeJSSmartInterfaceHandlers() {
  }

  static void install(List<KubeJSSmartInterfaceRegistration.Handler> handlers) {
    ServerCallbackRegistry.requireServer();
    if (handlers.isEmpty()) {
      SmartInterfaceEvents.remove(DISPATCHER_ID);
    } else {
      SmartInterfaceEvents.register(DISPATCHER_ID, new Dispatcher(handlers));
    }
  }

  private static final class Dispatcher implements SmartInterfaceEvents.Listener {
    private final List<KubeJSSmartInterfaceRegistration.Handler> handlers;
    private final Set<ResourceLocation> reported = new HashSet<>();

    private Dispatcher(List<KubeJSSmartInterfaceRegistration.Handler> handlers) {
      this.handlers = List.copyOf(handlers);
    }

    @Override
    public void onUpdate(SmartInterfaceEvents.Context context) {
      for (var handler : handlers) {
        try {
          handler.listener().onUpdate(context);
        } catch (RuntimeException failure) {
          if (reported.add(handler.id())) {
            ModularMachine.LOGGER.warn(
                "KubeJS smart interface handler {} failed at {}",
                handler.id(),
                context.interfacePosition(),
                failure);
          }
        }
      }
    }
  }
}
