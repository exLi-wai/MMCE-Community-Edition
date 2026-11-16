package com.lw.modularmachine.integration.kubejs;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Stable script function IDs derived from recipe and role, independent of
 * capture state.
 */
@ApiStatus.Internal
final class KubeJSCallbackIds {
  private KubeJSCallbackIds() {
  }

  public static ResourceLocation identity(ResourceLocation recipe, String role) {
    return ResourceLocation.fromNamespaceAndPath(
        "kubejs",
        "mmcece_item_" + UUID.nameUUIDFromBytes((recipe + "/" + role).getBytes(StandardCharsets.UTF_8)).toString()
            .replace("-", ""));
  }

}
