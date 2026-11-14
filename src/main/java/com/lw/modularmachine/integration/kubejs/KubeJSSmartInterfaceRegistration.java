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
  private final Thread registrationThread = Thread.currentThread();
  private boolean valid = true;
  private boolean open = true;

  public void onSmartInterfaceUpdate(String text, SmartInterfaceEvents.Listener listener) {
    requireOpen();
    ResourceLocation id = KubeJSRegistrationIds.parse(text);
    if (id == null
        || listener == null
        || !KubeJSRegistrationIds.claim(identifiers, id, KubeJSRegistrationIds.MAX_HANDLERS)) {
      valid = false;
      return;
    }
    handlers.put(id, listener);
  }

  public void onSmartInterfaceUpdate(String id, String machine, SmartInterfaceEvents.Listener listener) {
    addFiltered(id, machine, null, listener);
  }

  public void onBound(String id, SmartInterfaceEvents.Listener listener) {
    addFiltered(id, null, SmartInterfaceEvents.Reason.BOUND, listener);
  }

  public void onBound(String id, String machine, SmartInterfaceEvents.Listener listener) {
    addFiltered(id, machine, SmartInterfaceEvents.Reason.BOUND, listener);
  }

  public void onRebound(String id, SmartInterfaceEvents.Listener listener) {
    addFiltered(id, null, SmartInterfaceEvents.Reason.REBOUND, listener);
  }

  public void onRebound(String id, String machine, SmartInterfaceEvents.Listener listener) {
    addFiltered(id, machine, SmartInterfaceEvents.Reason.REBOUND, listener);
  }

  public void onValueChanged(String id, SmartInterfaceEvents.Listener listener) {
    addFiltered(id, null, SmartInterfaceEvents.Reason.VALUE_CHANGED, listener);
  }

  public void onValueChanged(String id, String machine, SmartInterfaceEvents.Listener listener) {
    addFiltered(id, machine, SmartInterfaceEvents.Reason.VALUE_CHANGED, listener);
  }

  private void addFiltered(String id, String machineText, SmartInterfaceEvents.Reason reason, SmartInterfaceEvents.Listener listener) {
    requireOpen();
    var machine = machineText == null ? null : ResourceLocation.tryParse(machineText);
    if (listener == null || machineText != null && machine == null) {
      valid = false;
      throw new IllegalArgumentException("Invalid filtered smart interface observer");
    }
    onSmartInterfaceUpdate(id, context -> {
      if (machine != null && !context.machine().equals(machine)
          || reason != null && context.change().reason() != reason) {
        return;
      }
      listener.onUpdate(context);
    });
  }

  private void requireOpen() {
    if (Thread.currentThread() != registrationThread) {
      throw new IllegalStateException("Smart interface registration used outside its creating thread");
    }
    if (!open) {
      throw new IllegalStateException("Smart interface registration is closed");
    }
  }

  void seal() {
    requireOpen();
    open = false;
  }

  boolean valid() {
    return valid;
  }

  List<Handler> snapshot() {
    if (open) {
      throw new IllegalStateException("Smart interface registration must be sealed before snapshot");
    }
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
