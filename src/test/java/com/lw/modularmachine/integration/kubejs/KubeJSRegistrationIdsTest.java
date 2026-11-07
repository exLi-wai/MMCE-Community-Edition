package com.lw.modularmachine.integration.kubejs;

import java.util.HashSet;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KubeJSRegistrationIdsTest {
  @Test
  void acceptsOnlyCanonicalBoundedKubeJsIds() {
    assertEquals(ResourceLocation.parse("kubejs:recipes/metal"), KubeJSRegistrationIds.parse("kubejs:recipes/metal"));
    assertNull(KubeJSRegistrationIds.parse(null));
    assertNull(KubeJSRegistrationIds.parse("other:recipes/metal"));
    assertNull(KubeJSRegistrationIds.parse("kubejs:"));
    assertNull(KubeJSRegistrationIds.parse("kubejs:UpperCase"));
    assertNull(KubeJSRegistrationIds.parse("kubejs:" + "a".repeat(253)));
  }

  @Test
  void claimsIdentifiersOnceAndWithinTheHandlerLimit() {
    var identifiers = new HashSet<ResourceLocation>();
    var first = ResourceLocation.parse("kubejs:first");
    var second = ResourceLocation.parse("kubejs:second");

    assertTrue(KubeJSRegistrationIds.claim(identifiers, first, 2));
    assertFalse(KubeJSRegistrationIds.claim(identifiers, first, 2));
    assertTrue(KubeJSRegistrationIds.claim(identifiers, second, 2));
    assertFalse(KubeJSRegistrationIds.claim(identifiers, ResourceLocation.parse("kubejs:third"), 2));
    assertFalse(KubeJSRegistrationIds.claim(identifiers, ResourceLocation.parse("kubejs:empty-limit"), 0));
  }
}
