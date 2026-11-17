package com.lw.modularmachine.machine.factory;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactoryThreadAdmissionTest {
  private static final ResourceLocation MACHINE = ResourceLocation.parse("mmcece:thread_admission");

  @Test
  void zeroExtraQuotaRetainsCoreStartsDespiteEarlierOrOccupiedExtraThreads() {
    var preset = new FactoryThreadPreset(List.of(core("z_core")), 0);
    var configured = List.of(thread("a_retained_extra"), thread("z_core"));

    assertFalse(preset.startAllowed("a_retained_extra", configured, Set.of(), 1));
    assertTrue(preset.startAllowed("z_core", configured, Set.of(), 1));
    assertTrue(preset.startAllowed("z_core", configured, Set.of("a_retained_extra"), 1));
  }

  @Test
  void extraStartsUseOnlyExtraSlotsAndRetainedOccupiedExtrasStillConsumeTheirQuota() {
    var preset = new FactoryThreadPreset(List.of(core("y_core"), core("z_core")), 1);
    var configured = List.of(thread("a_extra"), thread("b_retained_extra"), thread("y_core"), thread("z_core"));

    assertTrue(preset.startAllowed("a_extra", configured, Set.of("y_core", "z_core"), 3));
    assertFalse(preset.startAllowed("b_retained_extra", configured, Set.of(), 3));
    assertFalse(preset.startAllowed("a_extra", configured, Set.of("b_retained_extra"), 3));
    assertTrue(preset.startAllowed("z_core", configured, Set.of("a_extra", "y_core"), 3));
  }

  @Test
  void nativeTotalQuotaKeepsCoreAndExtraStartsInTheSamePool() {
    var preset = new FactoryThreadPreset(List.of(core("z_core")));
    var configured = List.of(thread("a_extra"), thread("z_core"));

    assertTrue(preset.startAllowed("a_extra", configured, Set.of(), 1));
    assertFalse(preset.startAllowed("z_core", configured, Set.of(), 1));
    assertFalse(preset.startAllowed("a_extra", configured, Set.of("z_core"), 1));
  }

  private static FactoryThreadPreset.Thread core(String id) {
    return new FactoryThreadPreset.Thread(id, null, true, 0, 1, Set.of(), true);
  }

  private static FactoryThreadState thread(String id) {
    return new FactoryThreadPreset.Thread(id, null, true, 0, 1).instantiate(MACHINE);
  }
}
