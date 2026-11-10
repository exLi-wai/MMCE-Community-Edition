package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.smart.SmartInterfaceEvents;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Registration builder for read-only smart interface notifications. */
public final class KubeJSSmartInterfaceRegistration implements KubeEvent {
  private final Map<ResourceLocation, SmartInterfaceEvents.Listener> handlers = new LinkedHashMap<>();
  private final Set<ResourceLocation> identifiers = new LinkedHashSet<>();
  private boolean valid = true;

  public void onSmartInterfaceUpdate(String text, SmartInterfaceEvents.Listener listener) {
    ResourceLocation id = KubeJSRegistrationIds.parse(text);
    if (id == null
        || listener == null
        || !KubeJSRegistrationIds.claim(identifiers, id, KubeJSRegistrationIds.MAX_HANDLERS)) {
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
