package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.item.MachineConstructionTool;
import com.lw.modularmachine.api.assembly.AssemblyPlan;
import com.lw.modularmachine.api.assembly.AssemblyPage;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.BlueprintPreviewPayloads;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server entry points and lifecycle ordering for construction, world projection
 * and blueprint sessions.
 */
@ApiStatus.Internal
public final class AssemblyService {
  private static final AssemblyProjectionCoordinator PROJECTIONS = new AssemblyProjectionCoordinator();
  private static final BlueprintPreviewCoordinator BLUEPRINTS = new BlueprintPreviewCoordinator(
      AssemblyService::lengthSelected);
  private static final AssemblyConstructionCoordinator CONSTRUCTIONS = new AssemblyConstructionCoordinator(
      (player, owner, definition) -> PROJECTIONS.lengths(player, owner, definition));
  private AssemblyService() {
  }

  private static int playerCursor;

  public static void request(ServerPlayer player, StructureService.StructureSubject owner, boolean build, InteractionHand hand) {
    ServerThreadGuard.require(player.serverLevel(), "Assembly request");
    if (!canEdit(player)
        || !player.serverLevel().mayInteract(player, owner.structurePosition())
        || !AssemblyAccess.validPlayer(player)
        || owner.structureLevel() != player.level()
        || owner.structureRemoved()
        || !owner.structureSchemaSupported()
        || !player.serverLevel().hasChunkAt(owner.structurePosition())
        || player.serverLevel().getBlockEntity(owner.structurePosition()) != owner
        || build && (hand != InteractionHand.MAIN_HAND || !AssemblyInteractions.matches(player.getMainHandItem()))
        || !build && !(player.getItemInHand(hand).getItem() instanceof MachineConstructionTool)
        || !player.canInteractWithBlock(owner.structurePosition(), 0)) {
      return;
    }
    var machine = AssemblyAccess.selectedMachine(owner, build);
    var definition = machine == null ? null : StructureService.definition(machine);
    if (definition == null) {
      player.displayClientMessage(Component.translatable("message.mmcece.assembly.machine_missing"), true);
      return;
    }
    var orientation = owner.structureOrientation(player.serverLevel());
    if (!definition.allows(orientation)) {
      return;
    }
    Map<String, Integer> lengths;
    int layers = -1;
    if (build) {
      lengths = selectedLengths(player, owner, definition);
    } else {
      layers = MachineConstructionTool.projectionLayers(player.getItemInHand(hand));
      lengths = definition.defaultDynamicLengths(layers);
    }
    if (!build) {
      player.displayClientMessage(Component.translatable("message.mmcece.projection.length", layers), true);
      var id = player.getUUID();
      BLUEPRINTS.cancelMatching(id, definition);
      CONSTRUCTIONS.invalidateLengths(id, owner, definition, lengths);
      PROJECTIONS.request(player, owner, definition, orientation, lengths, layers);
      return;
    }
    CONSTRUCTIONS.request(player, owner, definition, orientation, lengths, hand);
  }

  public static boolean canEdit(ServerPlayer player) {
    ServerThreadGuard.require(player.serverLevel(), "Blueprint interaction");
    return !AssemblyPlacement.active()
        && AssemblyAccess.validPlayer(player)
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && !RecipeLifecyclePublisher.isDispatching()
        && !SmartInterfaceEventPublisher.isDispatching();
  }

  private static Map<String, Integer> selectedLengths(ServerPlayer player, StructureService.StructureSubject owner, MachineDefinition definition) {
    var projected = PROJECTIONS.lengths(player, owner, definition);
    if (projected != null) {
      return projected;
    }
    var selected = BLUEPRINTS.selectedSize(player, definition);
    if (selected != null) {
      return definition.defaultDynamicLengths(selected);
    }
    var registration = owner.structureRegistration();
    if (registration != null
        && registration.pattern.definition.sourceDefinition() == definition
        && registration.canCommit(player.serverLevel())) {
      return registration.dynamicLengths();
    }
    return definition.defaultDynamicLengths(-1);
  }

  /** Only the held tool changes; world projection lengths are frozen on use. */
  public static void adjustProjectionLength(ServerPlayer player, InteractionHand hand, int delta) {
    ServerThreadGuard.require(player.serverLevel(), "Projection length adjustment");
    var stack = player.getItemInHand(hand);
    if ((delta != -1 && delta != 1) || !canEdit(player) || !(stack.getItem() instanceof MachineConstructionTool)) {
      return;
    }
    int layers = (int) Math.clamp((long) MachineConstructionTool.projectionLayers(stack) + delta, 0, Integer.MAX_VALUE);
    MachineConstructionTool.setProjectionLayers(stack, layers);
    player.getInventory().setChanged();
    player.inventoryMenu.broadcastChanges();
    if (player.containerMenu != player.inventoryMenu) {
      player.containerMenu.broadcastChanges();
    }
    player.displayClientMessage(Component.translatable("message.mmcece.projection.length", layers), true);
  }

  @ApiStatus.Internal
  public static Optional<AssemblyPlan> inspectProjection(ServerPlayer player) {
    return PROJECTIONS.inspect(player);
  }

  @ApiStatus.Internal
  public static boolean isCurrentProjection(ServerPlayer player, long expectedRequest) {
    return PROJECTIONS.isCurrent(player, expectedRequest);
  }

  @ApiStatus.Internal
  public static Optional<AssemblyPage> readProjectionPage(ServerPlayer player, long expectedRequest, int start, int limit) {
    return PROJECTIONS.readPage(player, expectedRequest, start, limit);
  }

  public static void cancelProjection(ServerPlayer player) {
    PROJECTIONS.cancel(player);
  }

  public static void stopProjection(ServerPlayer player, long expectedRequest) {
    PROJECTIONS.stop(player, expectedRequest);
  }

  public static void requestBlueprintPreview(ServerPlayer player, BlueprintPreviewPayloads.Request request) {
    BLUEPRINTS.request(player, request);
  }

  private static void lengthSelected(ServerPlayer player, MachineDefinition definition, Map<String, Integer> selected) {
    CONSTRUCTIONS.invalidateSelectedLengths(player.getUUID(), definition, selected);
    PROJECTIONS.invalidateLengths(player, definition, selected);
  }

  @SubscribeEvent
  public static void tick(Post event) {
    var identities = new LinkedHashSet<>(CONSTRUCTIONS.players());
    identities.addAll(PROJECTIONS.players());
    identities.addAll(PROJECTIONS.pendingPlayers());
    identities.addAll(CONSTRUCTIONS.pendingPlayers());
    identities.addAll(BLUEPRINTS.players());
    var players = new ArrayList<>(identities);
    if (players.isEmpty()) {
      CONSTRUCTIONS.advance(event.getServer());
      return;
    }
    int start = Math.floorMod(playerCursor, players.size());
    int count = Math.min(players.size(), 8);
    long projectionDeadline = System.nanoTime() + 2_000_000L;
    int packets = 0;
    for (int index = 0; index < count; index++) {
      var id = players.get((start + index) % players.size());
      var player = event.getServer().getPlayerList().getPlayer(id);
      if (player == null) {
        CONSTRUCTIONS.removeJob(id);
        PROJECTIONS.remove(id);
        PROJECTIONS.removePending(id);
        CONSTRUCTIONS.removePending(id);
        BLUEPRINTS.remove(id);
        CONSTRUCTIONS.removeWarnings(id);
        BLUEPRINTS.clearSelection(id);
      } else {
        CONSTRUCTIONS.prepare(player, projectionDeadline);
        PROJECTIONS.prepare(player, projectionDeadline);
        packets += PROJECTIONS.send(player, projectionDeadline, Math.min(32, 64 - packets));
        packets += BLUEPRINTS.advance(player, projectionDeadline, Math.min(32, 64 - packets));
      }
    }
    playerCursor = (start + 1) % players.size();
    CONSTRUCTIONS.advance(event.getServer());
  }

  @SubscribeEvent
  public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
    CONSTRUCTIONS.removeJob(event.getEntity().getUUID());
    CONSTRUCTIONS.removeWarnings(event.getEntity().getUUID());
    PROJECTIONS.remove(event.getEntity().getUUID());
    PROJECTIONS.removePending(event.getEntity().getUUID());
    CONSTRUCTIONS.removePending(event.getEntity().getUUID());
    BLUEPRINTS.remove(event.getEntity().getUUID());
    BLUEPRINTS.clearSelection(event.getEntity().getUUID());
  }
  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    CONSTRUCTIONS.clearJobsAndWarnings();
    PROJECTIONS.clear();
    CONSTRUCTIONS.clearPending();
    BLUEPRINTS.clear();
    ProjectionPreparation.clearCache();
    AssemblyPlacement.end();
    CONSTRUCTIONS.resetCursor();
    playerCursor = 0;
  }
}
