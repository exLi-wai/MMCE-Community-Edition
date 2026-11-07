package com.lw.modularmachine.integration.kubejs;

import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Shared bounds and canonical namespace checks for KubeJS callback IDs. */
final class KubeJSRegistrationIds {
  static final int MAX_ID_LENGTH = 256;
  static final int MAX_HANDLERS = 256;

  private KubeJSRegistrationIds() {
  }

  static ResourceLocation parse(String value) {
    ResourceLocation id = value == null ? null : ResourceLocation.tryParse(value);
    if (id == null
        || !id.getNamespace().equals("kubejs")
        || id.getPath().isEmpty()
        || value.length() > MAX_ID_LENGTH
        || !id.toString().equals(value)) {
      return null;
    }

    return id;
  }

  static boolean claim(Set<ResourceLocation> identifiers, ResourceLocation id, int maximum) {
    return maximum > 0 && identifiers.size() < maximum && identifiers.add(id);
  }
}
