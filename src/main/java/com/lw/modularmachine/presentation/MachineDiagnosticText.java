package com.lw.modularmachine.presentation;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Formats stored machine diagnostic identifiers as client-localized text. */
public final class MachineDiagnosticText {
  private MachineDiagnosticText() {
  }

  public static Component localized(String value) {
    MutableComponent result = Component.empty();
    String[] alternatives = value.split("; ");
    for (int index = 0; index < alternatives.length; index++) {
      if (index > 0) {
        result.append(Component.literal(", "));
      }
      result.append(localizedAlternative(alternatives[index]));
    }

    return result;
  }

  private static Component localizedAlternative(String value) {
    if (value.startsWith("port:")) {
      return Component.literal(value);
    }
    int bracket = value.indexOf('[');
    String key = bracket < 0 ? value : value.substring(0, bracket);
    String suffix = bracket < 0 ? "" : value.substring(bracket);
    if (!key.contains(".")) {
      key = "block." + key.replace(':', '.');
    }

    return Component.translatable(key).append(Component.literal(suffix));
  }
}
