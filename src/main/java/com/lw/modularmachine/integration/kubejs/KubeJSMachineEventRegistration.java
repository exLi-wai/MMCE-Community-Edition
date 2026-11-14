package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.controller.MachineEvents;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Server-only MMEvents declarations; client hooks use their own pure DTOs. */
public final class KubeJSMachineEventRegistration implements KubeEvent {
  public record Handler(
      ResourceLocation id,
      ResourceLocation machine,
      MachineEvents.Type type,
      MachineEvents.Listener listener,
      MachineEvents.ScriptAction action) {
  }

  private static List<ResourceLocation> installed = List.of();
  private final Map<ResourceLocation, Handler> handlers = new LinkedHashMap<>();
  private final Set<ResourceLocation> identifiers = new LinkedHashSet<>();
  private final Thread owner = Thread.currentThread();
  private boolean open = true;
  private boolean valid = true;

  public void onStructureFormed(String id, String machine, MachineEvents.Listener listener) {
    add(id, machine, MachineEvents.Type.STRUCTURE_FORMED, listener, null);
  }

  public void onStructureUpdate(String id, String machine, MachineEvents.Listener listener) {
    add(id, machine, MachineEvents.Type.STRUCTURE_UPDATE, listener, null);
  }

  public void onMachinePreTick(String id, String machine, MachineEvents.Listener listener) {
    add(id, machine, MachineEvents.Type.MACHINE_PRE_TICK, listener, null);
  }

  public void onMachinePostTick(String id, String machine, MachineEvents.Listener listener) {
    add(id, machine, MachineEvents.Type.MACHINE_POST_TICK, listener, null);
  }

  public void onMachineTick(String id, String machine, MachineEvents.Listener listener) {
    add(id, machine, MachineEvents.Type.MACHINE_TICK, listener, null);
  }

  public void onStructureFormedAction(String id, String machine, MachineEvents.ScriptAction action) {
    add(id, machine, MachineEvents.Type.STRUCTURE_FORMED, null, action);
  }

  public void onStructureUpdateAction(String id, String machine, MachineEvents.ScriptAction action) {
    add(id, machine, MachineEvents.Type.STRUCTURE_UPDATE, null, action);
  }

  public void onMachinePreTickAction(String id, String machine, MachineEvents.ScriptAction action) {
    add(id, machine, MachineEvents.Type.MACHINE_PRE_TICK, null, action);
  }

  public void onMachinePostTickAction(String id, String machine, MachineEvents.ScriptAction action) {
    add(id, machine, MachineEvents.Type.MACHINE_POST_TICK, null, action);
  }

  public void onMachineTickAction(String id, String machine, MachineEvents.ScriptAction action) {
    add(id, machine, MachineEvents.Type.MACHINE_TICK, null, action);
  }

  private void add(String text, String machineText, MachineEvents.Type type, MachineEvents.Listener listener, MachineEvents.ScriptAction action) {
    requireOpen();
    var id = KubeJSRegistrationIds.parse(text);
    var machine = machineText == null ? null : ResourceLocation.tryParse(machineText);
    if (id == null
        || machine == null
        || (listener == null) == (action == null)
        || !KubeJSRegistrationIds.claim(identifiers, id, KubeJSRegistrationIds.MAX_HANDLERS)) {
      valid = false;
      throw new IllegalArgumentException("Invalid MMEvents server registration");
    }
    handlers.put(id, new Handler(id, machine, type, listener, action));
  }

  private void requireOpen() {
    if (!open || Thread.currentThread() != owner) {
      throw new IllegalStateException("MMEvents registration is closed or used outside its creating thread");
    }
  }

  public void seal() {
    requireOpen();
    open = false;
  }

  public boolean valid() {
    return valid;
  }

  public List<Handler> snapshot() {
    if (open) {
      throw new IllegalStateException("MMEvents registration must be sealed");
    }
    return valid ? List.copyOf(handlers.values()) : List.of();
  }

  public static void install(List<Handler> next) {
    ServerCallbackRegistry.requireServer();
    installed.forEach(MachineEvents::remove);
    installed = List.of();
    var identifiers = new ArrayList<ResourceLocation>();
    try {
      for (var handler : next) {
        if (handler.listener() != null) {
          MachineEvents.register(handler.id(), Set.of(handler.type()), context -> {
            if (context.machine().equals(handler.machine())) {
              handler.listener().onEvent(context);
            }
          });
        } else {
          MachineEvents.registerAction(
              handler.id(),
              Set.of(handler.type()),
              context -> context.machine().equals(handler.machine()) ? handler.action().propose(context) : null);
        }
        identifiers.add(handler.id());
      }
      installed = List.copyOf(identifiers);
    } catch (RuntimeException failure) {
      identifiers.forEach(MachineEvents::remove);
      throw failure;
    }
  }

  static void clearOnServerStop() {
    installed = List.of();
  }
}
