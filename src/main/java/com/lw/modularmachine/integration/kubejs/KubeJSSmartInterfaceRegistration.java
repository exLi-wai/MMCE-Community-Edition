package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.machine.smart.SmartInterfaceEvents;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Registration builder for read-only smart interface notifications. */
public final class KubeJSSmartInterfaceRegistration implements KubeEvent {
  private static final int MAX_HANDLERS = 256;

  private final Map<ResourceLocation, SmartInterfaceEvents.Listener> handlers = new LinkedHashMap<>();
  private boolean valid = true;

  public void onUpdate(String text, SmartInterfaceEvents.Listener listener) {
    ResourceLocation id = text == null ? null : ResourceLocation.tryParse(text);
    if (id == null
        || !id.getNamespace().equals("kubejs")
        || id.getPath().isEmpty()
        || id.toString().length() > 256
        || !id.toString().equals(text)
        || listener == null
        || handlers.containsKey(id)
        || handlers.size() >= MAX_HANDLERS) {
      valid = false;
      return;
    }
    handlers.put(id, listener);
  }

  boolean valid() {
    return valid;
  }

  List<Handler> snapshot() {
    if (!valid) {
      return List.of();
    }
    var result = new ArrayList<Handler>(handlers.size());
    handlers.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
        .forEach(entry -> result.add(new Handler(entry.getKey(), entry.getValue())));

    return List.copyOf(result);
  }

  record Handler(ResourceLocation id, SmartInterfaceEvents.Listener listener) {
  }
}
