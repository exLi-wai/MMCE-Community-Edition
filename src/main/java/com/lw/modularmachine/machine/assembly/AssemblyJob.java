package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials;
import com.lw.modularmachine.util.minecraft.placement.PlacementOutcome;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.ApiStatus;

/** Mutable survival task; owns demand, planning, retry and report lifetimes. */
@ApiStatus.Internal
final class AssemblyJob {
  final StructureService.StructureSubject owner;
  final AssemblyPattern pattern;
  final ServerLevel level;
  final InteractionHand hand;
  final AssemblyReport report = new AssemblyReport();
  final Map<PlacementMaterials.Choices, Integer> materialDemand = new LinkedHashMap<>();
  final BitSet demandPositions = new BitSet();
  final BitSet fluidOnlyDemand = new BitSet();
  AssemblyMaterialPlan materialPlanning;
  AssemblyMaterialPlan.Result materials;
  int materialCursor;
  MaterialAttempt materialAttempt;
  int cursor, matched, placed, passPlaced;
  long nextPass;
  long waitedTick = -1;
  boolean refreshMaterials;
  AssemblyJob(StructureService.StructureSubject owner, AssemblyPattern pattern, ServerLevel level, InteractionHand hand) {
    this.owner = owner;
    this.pattern = pattern;
    this.level = level;
    this.hand = hand;
  }

  boolean current(ServerPlayer player) {
    if (!AssemblyAccess.validPlayer(player)
        || player.level() != level
        || pattern.epoch() != StructureService.definitionEpoch()
        || pattern.sourceDefinition() != StructureService.definition(pattern.machine())
        || !level.hasChunkAt(pattern.origin())
        || !level.mayInteract(player, pattern.origin())
        || player.hasInfiniteMaterials()) {
      return false;
    }
    return !owner.structureRemoved()
        && owner.structureSchemaSupported()
        && level.getBlockEntity(pattern.origin()) == owner
        && pattern.machine().equals(AssemblyAccess.selectedMachine(owner, true))
        && pattern.orientation() == owner.structureOrientation(level);
  }

  void cancel() {
    if (materialAttempt != null) {
      materialAttempt.cancel();
    }
    if (materialPlanning != null) {
      materialPlanning.cancel();
    }
    report.cancel();
  }

  static final class MaterialAttempt {
    final PlacementMaterials.Choices choices;
    final Set<AssemblyMaterialPlan.Resource> rejected = new HashSet<>();
    AssemblyMaterialPlan.Resource resource;
    CompletableFuture<AssemblyMaterialPlan.Replacement> retry;
    PlacementOutcome failure;

    MaterialAttempt(PlacementMaterials.Choices choices, AssemblyMaterialPlan.Resource resource) {
      this.choices = choices;
      this.resource = resource;
    }

    void cancel() {
      if (retry != null) {
        retry.cancel(false);
      }
    }
  }
}
