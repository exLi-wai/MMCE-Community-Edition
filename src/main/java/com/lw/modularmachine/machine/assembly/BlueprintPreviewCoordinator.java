package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.BlueprintPreviewPayloads;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import java.util.Set;

/** Held-blueprint preview sessions and bounded per-player length selections. */
@ApiStatus.Internal
final class BlueprintPreviewCoordinator {
  interface Host {
    void lengthSelected(ServerPlayer player, MachineDefinition definition, Map<String, Integer> lengths);
  }
  private final Host host;
  private final Map<UUID, PendingBlueprint> pendingBlueprints = new HashMap<>();
  private final Map<UUID, LinkedHashMap<ResourceLocation, LengthSelection>> lengthSelections = new HashMap<>();

  BlueprintPreviewCoordinator(Host host) {
    this.host = host;
  }

  Integer selectedSize(ServerPlayer player, MachineDefinition definition) {
    var selected = lengthSelection(player, definition);
    return selected == null ? null : selected.size();
  }

  Set<UUID> players() {
    return pendingBlueprints.keySet();
  }

  void cancelMatching(UUID player, MachineDefinition definition) {
    var pending = pendingBlueprints.get(player);
    if (pending != null && pending.definition == definition) {
      remove(player);
    }
  }

  void clearSelection(UUID player) {
    lengthSelections.remove(player);
  }

  void clear() {
    pendingBlueprints.values().forEach(pending -> pending.preparation.cancel());
    pendingBlueprints.clear();
    lengthSelections.clear();
  }

  private record LengthSelection(MachineDefinition definition, long epoch, int size) {
  }

  private static final class PendingBlueprint {
    final BlueprintPreviewPayloads.Request request;
    final MachineDefinition definition;
    final ServerLevel level;
    final MachineOrientation orientation;
    final long epoch;
    final int size, minimum, maximum;
    final ProjectionPreparation preparation;
    ProjectionPreparation.Ready ready;
    boolean selectionCommitted;
    int sent;

    PendingBlueprint(ServerPlayer player, BlueprintPreviewPayloads.Request request, MachineDefinition definition, MachineOrientation orientation, int size) {
      this.request = request;
      this.definition = definition;
      this.orientation = orientation;
      this.size = size;
      level = player.serverLevel();
      epoch = StructureService.definitionEpoch();
      minimum = definition.dynamicPatterns().stream().mapToInt(pattern -> pattern.minimum()).min().orElse(0);
      maximum = definition.dynamicPatterns().stream().mapToInt(pattern -> pattern.maximum()).max().orElse(0);
      preparation = new ProjectionPreparation(
          definition,
          epoch,
          BlockPos.ZERO,
          orientation,
          definition.defaultDynamicLengths(size));
    }
  }

  private LengthSelection lengthSelection(ServerPlayer player, MachineDefinition definition) {
    var selections = lengthSelections.get(player.getUUID());
    var selected = selections == null ? null : selections.get(definition.id());
    if (selected != null
        && (selected.definition() != definition || selected.epoch() != StructureService.definitionEpoch())) {
      selections.remove(definition.id());
      return null;
    }
    return selected;
  }

  /**
   * Read-only preview plus a bounded, session-local construction length choice.
   */
  void request(ServerPlayer player, BlueprintPreviewPayloads.Request request) {
    ServerThreadGuard.require(player.serverLevel(), "Blueprint preview request");
    var held = MachineBlueprintItem.binding(player.getItemInHand(request.hand()));
    var definition = StructureService.definition(request.machine());
    if (!player.isAlive()
        || UpgradeLifecyclePublisher.isDispatchingAny()
        || RecipeLifecyclePublisher.isDispatching()
        || SmartInterfaceEventPublisher.isDispatching()
        || held == null
        || !held.machine().equals(request.machine())
        || definition == null) {
      BlueprintPreviewPayloads.invalidate(player, request);
      return;
    }
    int maximum = definition.dynamicPatterns().stream().mapToInt(pattern -> pattern.maximum()).max().orElse(0);
    if (request.size() > maximum) {
      BlueprintPreviewPayloads.invalidate(player, request);
      return;
    }
    var previous = lengthSelection(player, definition);
    int size = request.size();
    if (size < 0 && previous != null) {
      size = previous.size();
    }
    var orientation = previewOrientation(definition, held.orientation());
    remove(player.getUUID());
    pendingBlueprints.put(player.getUUID(), new PendingBlueprint(player, request, definition, orientation, size));
  }

  private static MachineOrientation previewOrientation(MachineDefinition definition, MachineOrientation requested) {
    return definition.allows(requested) ? requested : MachineOrientation.all().stream().filter(definition::allows)
        .findFirst().orElseThrow();
  }

  private void commitLengthSelection(ServerPlayer player, PendingBlueprint pending) {
    pending.selectionCommitted = true;
    if (pending.request.size() < 0 || !pending.definition.hasDynamicPatterns()) {
      return;
    }
    var definition = pending.definition;
    var selected = pending.ready.pattern().dynamicLengths();
    commitLengthSelection(player, definition, pending.size, selected);
  }

  private void commitLengthSelection(ServerPlayer player, MachineDefinition definition, int size, Map<String, Integer> selected) {
    var choices = lengthSelections.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>(16, .75F, true));
    choices.put(definition.id(), new LengthSelection(definition, StructureService.definitionEpoch(), size));
    while (choices.size() > 16) {
      choices.remove(choices.keySet().iterator().next());
    }
    host.lengthSelected(player, definition, selected);
  }

  private static boolean current(ServerPlayer player, PendingBlueprint pending) {
    var held = MachineBlueprintItem.binding(player.getItemInHand(pending.request.hand()));
    return player.level() == pending.level
        && player.isAlive()
        && pending.epoch == StructureService.definitionEpoch()
        && StructureService.definition(pending.definition.id()) == pending.definition
        && held != null
        && held.machine().equals(pending.definition.id())
        && previewOrientation(pending.definition, held.orientation()) == pending.orientation;
  }

  int advance(ServerPlayer player, long deadline, int packetBudget) {
    var pending = pendingBlueprints.get(player.getUUID());
    if (pending == null) {
      return 0;
    }
    if (!current(player, pending)) {
      remove(player.getUUID());
      BlueprintPreviewPayloads.invalidate(player, pending.request);
      return 0;
    }
    if (System.nanoTime() >= deadline || packetBudget <= 0) {
      return 0;
    }
    try {
      if (pending.ready == null) {
        pending.ready = pending.preparation.advance(deadline);
      }
      if (pending.ready == null || !current(player, pending) || pendingBlueprints.get(player.getUUID()) != pending) {
        return 0;
      }
      if (!pending.selectionCommitted) {
        commitLengthSelection(player, pending);
      }
      int packets = 0;
      do {
        pending.sent = BlueprintPreviewPayloads.sendChunk(
            player,
            pending.request,
            pending.epoch,
            pending.definition.hasDynamicPatterns(),
            pending.size,
            pending.minimum,
            pending.maximum,
            pending.definition.color(),
            pending.orientation,
            pending.ready.entries(),
            pending.sent);
        packets++;
        if (pending.sent == pending.ready.entries().size()) {
          pendingBlueprints.remove(player.getUUID(), pending);
          break;
        }
      } while (packets < packetBudget && System.nanoTime() < deadline);
      return packets;
    } catch (RuntimeException failure) {
      if (pendingBlueprints.remove(player.getUUID(), pending)) {
        pending.preparation.cancel();
      }
      BlueprintPreviewPayloads.invalidate(
          player,
          pending.request,
          pending.definition.hasDynamicPatterns(),
          pending.size,
          pending.minimum,
          pending.maximum,
          pending.definition.color(),
          pending.orientation);
      ModularMachine.LOGGER.warn("Blueprint preview preparation failed for {}", pending.definition.id(), failure);
      return 0;
    }
  }

  void remove(UUID player) {
    var pending = pendingBlueprints.remove(player);
    if (pending != null) {
      pending.preparation.cancel();
    }
  }

}
