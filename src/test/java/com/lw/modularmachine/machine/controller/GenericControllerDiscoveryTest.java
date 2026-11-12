package com.lw.modularmachine.machine.controller;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters.RegistrySnapshot;
import com.lw.modularmachine.machine.structure.DynamicStructureMatcher;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericControllerDiscoveryTest {
  private static MachineDefinition definition() {
    var source = JsonParser.parseString("""
        {"parts":[{"offset":[1,0,0],"block":"energy_input"}],
         "dynamic_patterns":[{"id":"line","minimum":1,"maximum":2,
           "start_offset":[0,0,1],"step":[0,0,1],
           "parts":[{"offset":[0,0,0],"block":"item_input"}]}]}
        """).getAsJsonObject();

    return MachineDefinition
        .parse(ResourceLocation.parse("mmcece:discovery_test"), source, new RegistrySnapshot(Map.of()));
  }

  @Test
  void changingWorldDoesNotDiscardPendingPreparationAndSchedulesOneFreshPass() {
    var discovery = new GenericControllerDiscovery();
    var definition = definition();
    var views = new AtomicInteger();
    Supplier<List<MachineDefinition>> definitions = () -> {
      views.incrementAndGet();
      return List.of(definition);
    };
    var attempts = new AtomicInteger();
    var pending = new CompletableFuture<DynamicStructureMatcher>();
    Supplier<CompletableFuture<DynamicStructureMatcher>> preparation = () -> {
      attempts.incrementAndGet();
      return pending;
    };
    discovery.refresh(1, 0, MachineOrientation.IDENTITY, null, null, false, definitions);
    assertFalse(discovery.prepareDynamic(preparation));
    for (int revision = 1; revision <= 20; revision++) {
      discovery.refresh(1, revision, MachineOrientation.IDENTITY, null, null, false, definitions);
      assertFalse(discovery.prepareDynamic(preparation));
    }
    assertEquals(1, attempts.get(), "Unrelated changes must not keep replacing the in-flight future");
    pending.complete(new DynamicStructureMatcher(definition, BlockPos.ZERO, MachineOrientation.IDENTITY));
    assertTrue(discovery.prepareDynamic(preparation));
    assertEquals(1, views.get(), "Candidate metadata is rebuilt only when its definition context changes");

    discovery.next();
    assertTrue(discovery.refresh(1, 20, MachineOrientation.IDENTITY, null, null, false, definitions));
    var fresh = new CompletableFuture<DynamicStructureMatcher>();
    assertFalse(discovery.prepareDynamic(() -> fresh));
    fresh.complete(new DynamicStructureMatcher(definition, BlockPos.ZERO, MachineOrientation.IDENTITY));
    assertTrue(discovery.prepareDynamic(() -> fresh));
    assertEquals(1, views.get());
    discovery.next();
    assertFalse(
        discovery.refresh(1, 20, MachineOrientation.IDENTITY, null, null, false, definitions),
        "A quiet world must not restart completed negative discovery every tick");
  }

  @Test
  void definitionChangeRevokesOldPreparationImmediately() {
    var discovery = new GenericControllerDiscovery();
    var definition = definition();
    var old = new CompletableFuture<DynamicStructureMatcher>();
    discovery.refresh(1, 0, MachineOrientation.IDENTITY, null, null, false, () -> List.of(definition));
    assertFalse(discovery.prepareDynamic(() -> old));
    discovery.refresh(2, 1, MachineOrientation.IDENTITY, null, null, false, () -> List.of(definition));
    var current = new CompletableFuture<DynamicStructureMatcher>();
    assertFalse(discovery.prepareDynamic(() -> current));
    old.complete(new DynamicStructureMatcher(definition, BlockPos.ZERO, MachineOrientation.IDENTITY));
    assertFalse(
        discovery.prepareDynamic(() -> current),
        "An obsolete definition result must not replace the new round");
    current.complete(new DynamicStructureMatcher(definition, BlockPos.ZERO, MachineOrientation.IDENTITY));
    assertTrue(discovery.prepareDynamic(() -> current));
  }
}
