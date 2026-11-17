package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.controller.ControllerScriptData;
import com.lw.modularmachine.api.controller.MachineEvents;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.smart.SmartInterfaceEvents;
import com.lw.modularmachine.api.client.controller.ControllerClientEvents;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal side-specific declarations under the original MMEvents event group.
 */
@ApiStatus.Internal
public final class KubeJSMachineRegistration implements KubeEvent {
  private final boolean clientSide;
  private final KubeJSMachineEventRegistration server = new KubeJSMachineEventRegistration();
  private final KubeJSControllerClientRegistration client = new KubeJSControllerClientRegistration();
  private final KubeJSSmartInterfaceRegistration smart = new KubeJSSmartInterfaceRegistration();

  public void onSmartInterfaceUpdate(String id, String machine, SmartInterfaceEvents.Listener callback) {
    serverSide();
    smart.onSmartInterfaceUpdate(id, machine, callback);
  }

  public List<KubeJSSmartInterfaceRegistration.Handler> smartSnapshot() {
    serverSide();
    smart.seal();
    return smart.snapshot();
  }

  public KubeJSMachineRegistration(boolean clientSide) {
    this.clientSide = clientSide;
  }

  private void serverSide() {
    if (clientSide) {
      throw new IllegalStateException("Machine lifecycle events require server_scripts");
    }
  }

  private void clientSide() {
    if (!clientSide) {
      throw new IllegalStateException("Controller visual events require client_scripts");
    }
  }

  public void onStructureFormed(String id, String machine, MachineEvents.Listener callback) {
    serverSide();
    server.onStructureFormed(id, machine, callback);
  }

  public void onStructureUpdate(String id, String machine, MachineEvents.Listener callback) {
    serverSide();
    server.onStructureUpdate(id, machine, callback);
  }

  public void onMachinePreTick(String id, String machine, MachineEvents.Listener callback) {
    serverSide();
    server.onMachinePreTick(id, machine, callback);
  }

  public void onMachinePostTick(String id, String machine, MachineEvents.Listener callback) {
    serverSide();
    server.onMachinePostTick(id, machine, callback);
  }

  public void onMachineTick(String id, String machine, MachineEvents.Listener callback) {
    serverSide();
    server.onMachineTick(id, machine, callback);
  }

  public void onStructureFormedAction(String id, String machine, MachineEvents.ScriptAction callback) {
    serverSide();
    server.onStructureFormedAction(id, machine, callback);
  }

  public void onStructureUpdateAction(String id, String machine, MachineEvents.ScriptAction callback) {
    serverSide();
    server.onStructureUpdateAction(id, machine, callback);
  }

  public void onMachinePreTickAction(String id, String machine, MachineEvents.ScriptAction callback) {
    serverSide();
    server.onMachinePreTickAction(id, machine, callback);
  }

  public void onMachinePostTickAction(String id, String machine, MachineEvents.ScriptAction callback) {
    serverSide();
    server.onMachinePostTickAction(id, machine, callback);
  }

  public void onMachineTickAction(String id, String machine, MachineEvents.ScriptAction callback) {
    serverSide();
    server.onMachineTickAction(id, machine, callback);
  }

  public void onControllerGUIRender(String id, String machine, ControllerClientEvents.GuiListener callback) {
    clientSide();
    client.onControllerGUIRender(id, machine, callback);
  }

  public void onControllerModelGet(String id, String machine, ControllerClientEvents.ModelListener callback) {
    clientSide();
    client.onControllerModelGet(id, machine, callback);
  }

  public void onControllerModelAnimation(String id, String machine, ControllerClientEvents.AnimationListener callback) {
    clientSide();
    client.onControllerModelAnimation(id, machine, callback);
  }

  public void registerGeoMachineModel(String name, String geometry, String texture, String animation) {
    clientSide();
    client.registerGeoMachineModel(name, geometry, texture, animation);
  }

  public void setMachineGeoModel(String machine, String name) {
    clientSide();
    client.setMachineGeoModel(machine, name);
  }

  /** Returns an editable detached copy; changes require an Action proposal. */
  public Map<String, Object> customData(ControllerScriptData snapshot) {
    return scriptObject(JsonParser.parseString(snapshot.customData()).getAsJsonObject());
  }

  /** KubeJS converts ordinary script objects before capturing the proposal. */
  public MachineEvents.ScriptProposal scriptData(JsonObject customData, Map<ResourceLocation, RecipeModifier> modifiers) {
    return MachineEvents.scriptData(Objects.requireNonNull(customData, "customData").toString(), modifiers);
  }

  private static Map<String, Object> scriptObject(JsonObject source) {
    var result = new LinkedHashMap<String, Object>();
    source.entrySet().forEach(entry -> result.put(entry.getKey(), scriptValue(entry.getValue())));

    return result;
  }

  private static Object scriptValue(JsonElement value) {
    if (value.isJsonObject()) {
      return scriptObject(value.getAsJsonObject());
    }
    if (value.isJsonArray()) {
      var result = new ArrayList<Object>();
      value.getAsJsonArray().forEach(element -> result.add(scriptValue(element)));

      return result;
    }
    if (value.isJsonNull()) {
      return null;
    }
    var primitive = value.getAsJsonPrimitive();
    if (primitive.isBoolean()) {
      return primitive.getAsBoolean();
    }
    if (primitive.isNumber()) {
      return primitive.getAsNumber();
    }

    return primitive.getAsString();
  }

  public List<KubeJSMachineEventRegistration.Handler> serverSnapshot() {
    serverSide();
    server.seal();
    return server.snapshot();
  }

  public KubeJSControllerClientRegistration.Snapshot clientSnapshot() {
    clientSide();
    return client.snapshot();
  }
}
