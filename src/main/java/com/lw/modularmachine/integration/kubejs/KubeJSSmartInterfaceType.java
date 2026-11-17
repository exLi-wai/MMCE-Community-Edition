package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import org.jetbrains.annotations.ApiStatus;
import com.google.gson.JsonArray;

/**
 * Original chainable numerical interface declaration; machines capture values
 * by copy.
 */
@ApiStatus.Internal
public final class KubeJSSmartInterfaceType {
  private final Thread owner = Thread.currentThread();
  private final JsonObject declaration = new JsonObject();

  private KubeJSSmartInterfaceType(String type, double defaultValue) {
    declaration.addProperty("id", type);
    declaration.addProperty("default", defaultValue);
    validate();
  }

  public static KubeJSSmartInterfaceType create(String type, double defaultValue) {
    return new KubeJSSmartInterfaceType(type, defaultValue);
  }

  public KubeJSSmartInterfaceType setHeaderInfo(String text) {
    return text("header", text);
  }

  public KubeJSSmartInterfaceType setValueInfo(String text) {
    return text("value_label", text);
  }

  public KubeJSSmartInterfaceType setFooterInfo(String text) {
    return text("footer", text);
  }

  public KubeJSSmartInterfaceType setNotEqualMessage(String text) {
    return text("not_equal_message", text);
  }

  public KubeJSSmartInterfaceType setPriority(int priority) {
    mutable();
    declaration.addProperty("priority", priority);
    return this;
  }

  public KubeJSSmartInterfaceType setJeiTooltip(String text, int argsCount) {
    text("jei_tooltip", text);
    declaration.addProperty("jei_tooltip_args", argsCount);
    validate();
    return this;
  }

  private KubeJSSmartInterfaceType text(String name, String text) {
    mutable();
    if (text == null || text.length() > 256) {
      throw new IllegalArgumentException("Smart interface text exceeds 256 characters");
    }
    declaration.addProperty(name, text);
    return this;
  }

  private void mutable() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("Smart interface declaration used outside its creating thread");
    }
  }

  private void validate() {
    var values = new JsonArray();
    values.add(declaration.deepCopy());
    SmartInterfaceType.parse(values, "SmartInterfaceType");
  }

  JsonObject capture() {
    mutable();
    validate();
    return declaration.deepCopy();
  }
}
