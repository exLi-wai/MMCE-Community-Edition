package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.client.controller.ControllerClientEvents;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal client-script declarations. Registry resources and listeners are
 * sealed together.
 */
@ApiStatus.Internal
public final class KubeJSControllerClientRegistration {
  public record Handler<T>(ResourceLocation id, ResourceLocation machine, T listener) {
  }

  public record Model(String name, ResourceLocation geometry, ResourceLocation texture, ResourceLocation animation) {
  }

  public record Snapshot(
      List<Handler<ControllerClientEvents.GuiListener>> gui,
      List<Handler<ControllerClientEvents.ModelListener>> model,
      List<Handler<ControllerClientEvents.AnimationListener>> animation,
      Map<String, Model> models,
      Map<ResourceLocation, String> assignments) {
    public static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), List.of(), Map.of(), Map.of());
    public Snapshot {
      gui = List.copyOf(gui);
      model = List.copyOf(model);
      animation = List.copyOf(animation);
      models = Map.copyOf(models);
      assignments = Map.copyOf(assignments);
    }
  }

  private final Thread thread = Thread.currentThread();
  private final Set<ResourceLocation> identifiers = new HashSet<>();
  private final List<Handler<ControllerClientEvents.GuiListener>> gui = new ArrayList<>();
  private final List<Handler<ControllerClientEvents.ModelListener>> model = new ArrayList<>();
  private final List<Handler<ControllerClientEvents.AnimationListener>> animation = new ArrayList<>();
  private final Map<String, Model> models = new LinkedHashMap<>();
  private final Map<ResourceLocation, String> assignments = new LinkedHashMap<>();
  private boolean open = true;
  private boolean valid = true;

  public void onControllerGUIRender(String id, String machine, ControllerClientEvents.GuiListener listener) {
    add(gui, id, machine, listener);
  }

  public void onControllerModelGet(String id, String machine, ControllerClientEvents.ModelListener listener) {
    add(model, id, machine, listener);
  }

  public void onControllerModelAnimation(String id, String machine, ControllerClientEvents.AnimationListener listener) {
    add(animation, id, machine, listener);
  }

  public void registerGeoMachineModel(String name, String geo, String texture, String animations) {
    mutable();
    ControllerClientEvents.requireName(name);
    if (models.size() >= 256 || models.containsKey(name)) {
      throw reject("Duplicate or excessive controller models");
    }
    models.put(name, new Model(name, resource(geo), resource(texture), resource(animations)));
  }

  public void setMachineGeoModel(String machine, String name) {
    mutable();
    ControllerClientEvents.requireName(name);
    var id = resource(machine);
    if (assignments.size() >= 256 || assignments.putIfAbsent(id, name) != null) {
      throw reject("Duplicate or excessive controller model assignments");
    }
  }

  private <T> void add(List<Handler<T>> handlers, String text, String machine, T listener) {
    mutable();
    var id = KubeJSRegistrationIds.parse(text);
    if (id == null
        || listener == null
        || !KubeJSRegistrationIds.claim(identifiers, id, KubeJSRegistrationIds.MAX_HANDLERS)) {
      throw reject("Invalid controller client listener registration");
    }
    handlers.add(new Handler<>(id, resource(machine), listener));
  }

  private ResourceLocation resource(String text) {
    var id = text == null ? null : ResourceLocation.tryParse(text);
    if (id == null) {
      throw reject("Invalid controller client resource ID");
    }
    return id;
  }

  private IllegalArgumentException reject(String reason) {
    valid = false;
    return new IllegalArgumentException(reason);
  }

  private void mutable() {
    if (!open || Thread.currentThread() != thread) {
      throw new IllegalStateException("Controller client registration is sealed or used outside its creating thread");
    }
  }

  public Snapshot snapshot() {
    mutable();
    open = false;
    if (!valid || assignments.values().stream().anyMatch(name -> !models.containsKey(name))) {
      return Snapshot.EMPTY;
    }
    return new Snapshot(gui, model, animation, models, assignments);
  }
}
