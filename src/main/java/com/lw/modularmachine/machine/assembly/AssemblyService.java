package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.item.MachineBlueprintItem;
import com.lw.modularmachine.item.MachineConstructionTool;
import com.lw.modularmachine.api.assembly.AssemblyPlan;
import com.lw.modularmachine.api.assembly.AssemblyPage;
import com.lw.modularmachine.api.assembly.AssemblyPart;
import com.lw.modularmachine.api.assembly.AssemblyCandidate;
import com.lw.modularmachine.api.assembly.MachineAssemblyApi;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.network.BlueprintPreviewPayloads;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials;
import com.lw.modularmachine.util.minecraft.placement.PlacementOutcome;
import com.lw.modularmachine.util.minecraft.inventory.PlayerInventoryItems;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import org.jetbrains.annotations.ApiStatus;

/**
 * One server task per player, shared time/work slicing and no persistent
 * prepaid work.
 */
public final class AssemblyService {
  private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();
  private static final Map<UUID, Warnings> WARNINGS = new LinkedHashMap<>();
  private static final Map<UUID, Job> PROJECTIONS = new HashMap<>();
  private static final Map<UUID, PendingProjection> PENDING_PROJECTIONS = new HashMap<>();
  private static final Map<UUID, PendingConstruction> PENDING_CONSTRUCTIONS = new HashMap<>();
  private static final Map<UUID, PendingBlueprint> PENDING_BLUEPRINTS = new HashMap<>();
  private static final Map<UUID, LinkedHashMap<ResourceLocation, LengthSelection>> LENGTH_SELECTIONS = new HashMap<>();
  private static final int BULK_FLAGS = Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
  private static long request;
  private static boolean placing;
  private static int playerCursor;
  private static int assemblyCursor;
  private static final long BUILD_SLICE_NANOS = 250_000L;

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

  private static final class Job {
    final StructureService.StructureSubject owner;
    final AssemblyPattern pattern;
    final ServerLevel level;
    final InteractionHand hand;
    final boolean build;
    final long projectionRevision;
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
    long projectionRequest = -1;
    List<ProjectionPayloads.Entry> projectionEntries = List.of();
    int sent;
    Job(StructureService.StructureSubject owner, AssemblyPattern pattern, ServerLevel level, InteractionHand hand, boolean build) {
      this.owner = owner;
      this.pattern = pattern;
      this.level = level;
      this.hand = hand;
      this.build = build;
      projectionRevision = build ? -1 : StructureService.controllerProjectionRevision(owner);
    }
  }

  private static final class Warnings {
    final AssemblyReport.Delivery delivery;
    long lastTick = -1;

    Warnings(AssemblyReport.Delivery delivery) {
      this.delivery = delivery;
    }
  }

  private static final class MaterialAttempt {
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
  private AssemblyService() {
  }

  private static final class PendingProjection {
    final StructureService.StructureSubject owner;
    final ServerLevel level;
    final MachineDefinition definition;
    final MachineOrientation orientation;
    final BlockPos origin;
    final long epoch, request, controllerRevision;
    final InteractionHand hand;
    final ProjectionPreparation preparation;
    final Map<String, Integer> lengths;
    final int layers;
    PendingProjection(StructureService.StructureSubject owner, ServerLevel level, MachineDefinition definition, MachineOrientation orientation, Map<String, Integer> lengths, long request, InteractionHand hand, int layers) {
      this.owner = owner;
      this.level = level;
      this.definition = definition;
      this.orientation = orientation;
      this.request = request;
      this.hand = hand;
      this.layers = layers;
      this.lengths = Map.copyOf(lengths);
      origin = owner.structurePosition().immutable();
      epoch = StructureService.definitionEpoch();
      controllerRevision = StructureService.controllerProjectionRevision(owner);
      preparation = new ProjectionPreparation(definition, epoch, origin, orientation, lengths);
    }
  }

  private static final class PendingConstruction {
    final StructureService.StructureSubject owner;
    final ServerLevel level;
    final MachineDefinition definition;
    final MachineOrientation orientation;
    final BlockPos origin;
    final long epoch;
    final InteractionHand hand;
    final boolean creative;
    final Map<String, Integer> lengths;
    final AssemblyPreparation preparation;

    PendingConstruction(ServerPlayer player, StructureService.StructureSubject owner, MachineDefinition definition, MachineOrientation orientation, Map<String, Integer> lengths, InteractionHand hand) {
      this.owner = owner;
      this.definition = definition;
      this.orientation = orientation;
      this.hand = hand;
      this.lengths = Map.copyOf(lengths);
      level = player.serverLevel();
      origin = owner.structurePosition().immutable();
      epoch = StructureService.definitionEpoch();
      creative = player.hasInfiniteMaterials();
      preparation = new AssemblyPreparation(definition, epoch, origin, orientation, lengths);
    }
  }

  public static void request(ServerPlayer player, StructureService.StructureSubject owner, boolean build, InteractionHand hand) {
    ServerThreadGuard.require(player.serverLevel(), "Assembly request");
    if (!canEdit(player)
        || !player.serverLevel().mayInteract(player, owner.structurePosition())
        || !validPlayer(player)
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
    var machine = selectedMachine(owner, build);
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
      var blueprint = PENDING_BLUEPRINTS.get(id);
      if (blueprint != null && blueprint.definition == definition) {
        removePendingBlueprint(id);
      }
      var building = PENDING_CONSTRUCTIONS.get(id);
      if (building != null
          && building.owner == owner
          && building.definition == definition
          && !building.lengths.equals(lengths)) {
        removePendingConstruction(id);
      }
      var construction = JOBS.get(id);
      if (construction != null
          && construction.owner == owner
          && construction.pattern.sourceDefinition() == definition
          && !construction.pattern.dynamicLengths().equals(lengths)) {
        removeJob(id);
      }
      var pending = PENDING_PROJECTIONS.get(id);
      if (pending != null
          && pending.owner == owner
          && pending.definition == definition
          && pending.lengths.equals(lengths)
          && pending.layers == layers
          && pending.orientation == orientation
          && pending.epoch == StructureService.definitionEpoch()) {
        return;
      }
      var previous = PROJECTIONS.get(id);
      if (previous != null
          && previous.owner == owner
          && previous.pattern.machine().equals(machine)
          && previous.pattern.dynamicLengths().equals(lengths)
          && previous.pattern.orientation() == orientation
          && previous.pattern.epoch() == StructureService.definitionEpoch()) {
        return;
      }
      PROJECTIONS.remove(id);
      removePendingProjection(id);
      ProjectionPayloads.clear(player, request = Math.incrementExact(request));
      long selectedRequest = request = Math.incrementExact(request);
      PENDING_PROJECTIONS.put(
          id,
          new PendingProjection(
              owner,
              player.serverLevel(),
              definition,
              orientation,
              lengths,
              selectedRequest,
              hand,
              layers));
      // Header lets the client cancel by distance even during preparation.
      ProjectionPayloads.begin(
          player,
          selectedRequest,
          StructureService.definitionEpoch(),
          machine,
          owner.structurePosition(),
          Math.max(1, definition.parts().size()));
      return;
    }
    var id = player.getUUID();
    var pending = PENDING_CONSTRUCTIONS.get(id);
    boolean creative = player.hasInfiniteMaterials();
    if (pending != null
        && pending.owner == owner
        && pending.level == player.level()
        && pending.definition == definition
        && pending.epoch == StructureService.definitionEpoch()
        && pending.orientation == orientation
        && pending.lengths.equals(lengths)
        && pending.creative == creative) {
      return;
    }
    var previous = JOBS.get(id);
    if (!creative
        && previous != null
        && previous.owner == owner
        && previous.pattern.sourceDefinition() == definition
        && previous.pattern.epoch() == StructureService.definitionEpoch()
        && previous.pattern.orientation() == orientation
        && previous.pattern.dynamicLengths().equals(lengths)) {
      return;
    }
    removePendingConstruction(id);
    removeJob(id);
    PENDING_CONSTRUCTIONS.put(id, new PendingConstruction(player, owner, definition, orientation, lengths, hand));
  }

  public static boolean canEdit(ServerPlayer player) {
    ServerThreadGuard.require(player.serverLevel(), "Blueprint interaction");
    return !placing
        && validPlayer(player)
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && !RecipeLifecyclePublisher.isDispatching()
        && !SmartInterfaceEventPublisher.isDispatching();
  }

  private static LengthSelection lengthSelection(ServerPlayer player, MachineDefinition definition) {
    var selections = LENGTH_SELECTIONS.get(player.getUUID());
    var selected = selections == null ? null : selections.get(definition.id());
    if (selected != null
        && (selected.definition() != definition || selected.epoch() != StructureService.definitionEpoch())) {
      selections.remove(definition.id());
      return null;
    }
    return selected;
  }

  private static Map<String, Integer> selectedLengths(ServerPlayer player, StructureService.StructureSubject owner, MachineDefinition definition) {
    var projected = projectedLengths(player, owner, definition);
    if (projected != null) {
      return projected;
    }
    var selected = lengthSelection(player, definition);
    if (selected != null) {
      return definition.defaultDynamicLengths(selected.size());
    }
    var registration = owner.structureRegistration();
    if (registration != null
        && registration.pattern.definition.sourceDefinition() == definition
        && registration.canCommit(player.serverLevel())) {
      return registration.dynamicLengths();
    }
    return definition.defaultDynamicLengths(-1);
  }

  /**
   * Only the same current owner, definition and orientation may supply lengths.
   */
  private static Map<String, Integer> projectedLengths(ServerPlayer player, StructureService.StructureSubject owner, MachineDefinition definition) {
    var pending = PENDING_PROJECTIONS.get(player.getUUID());
    if (pending != null && pending.owner == owner && pending.definition == definition && current(player, pending)) {
      return pending.lengths;
    }
    var projection = PROJECTIONS.get(player.getUUID());
    if (projection != null
        && projection.owner == owner
        && projection.pattern.sourceDefinition() == definition
        && current(player, projection)) {
      return projection.pattern.dynamicLengths();
    }
    return null;
  }

  @ApiStatus.Internal
  public static Optional<AssemblyPlan> inspectProjection(ServerPlayer player) {
    ServerThreadGuard.require(player.serverLevel(), "Projection plan inspected");
    var projection = PROJECTIONS.get(player.getUUID());
    if (projection == null || !current(player, projection)) {
      return Optional.empty();
    }
    var pattern = projection.pattern;
    return Optional.of(
        new AssemblyPlan(
            projection.projectionRequest,
            projection.level.dimension().location(),
            pattern.origin(),
            pattern.machine(),
            pattern.epoch(),
            pattern.orientation(),
            pattern.dynamicLengths(),
            pattern.selectedModifierGroups(),
            pattern.parts().size()));
  }

  @ApiStatus.Internal
  public static boolean isCurrentProjection(ServerPlayer player, long expectedRequest) {
    ServerThreadGuard.require(player.serverLevel(), "Projection plan checked");
    var projection = PROJECTIONS.get(player.getUUID());
    return expectedRequest >= 0
        && projection != null
        && projection.projectionRequest == expectedRequest
        && current(player, projection);
  }

  @ApiStatus.Internal
  public static Optional<AssemblyPage> readProjectionPage(ServerPlayer player, long expectedRequest, int start, int limit) {
    ServerThreadGuard.require(player.serverLevel(), "Projection plan page read");
    if (expectedRequest < 0 || start < 0 || limit < 1 || limit > MachineAssemblyApi.MAX_PAGE_PARTS) {
      throw new IllegalArgumentException("Invalid projection plan page request");
    }
    var projection = PROJECTIONS.get(player.getUUID());
    if (projection == null
        || projection.projectionRequest != expectedRequest
        || !current(player, projection)
        || start > projection.pattern.parts().size()) {
      return Optional.empty();
    }
    int total = projection.pattern.parts().size();
    int end = (int) Math.min((long) total, (long) start + limit);
    var parts = new ArrayList<AssemblyPart>(end - start);
    for (int index = start; index < end; index++) {
      var source = projection.pattern.parts().get(index);
      var alternatives = source.rule().alternatives();
      var candidates = new ArrayList<AssemblyCandidate>(source.states().size());
      for (int candidate = 0; candidate < source.states().size(); candidate++) {
        List<String> properties;
        if (alternatives.isEmpty()) {
          properties = List.of();
        } else {
          properties = alternatives.get(candidate).properties().keySet().stream().map(property -> property.getName())
              .sorted().toList();
        }
        candidates.add(new AssemblyCandidate(source.states().get(candidate), properties));
      }
      parts.add(new AssemblyPart(source.position(), candidates));
    }
    return Optional.of(new AssemblyPage(expectedRequest, start, total, parts));
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

  /**
   * Read-only preview plus a bounded, session-local construction length choice.
   */
  public static void requestBlueprintPreview(ServerPlayer player, BlueprintPreviewPayloads.Request request) {
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
    removePendingBlueprint(player.getUUID());
    PENDING_BLUEPRINTS.put(player.getUUID(), new PendingBlueprint(player, request, definition, orientation, size));
  }

  private static MachineOrientation previewOrientation(MachineDefinition definition, MachineOrientation requested) {
    return definition.allows(requested) ? requested : MachineOrientation.all().stream().filter(definition::allows)
        .findFirst().orElseThrow();
  }

  private static void commitLengthSelection(ServerPlayer player, PendingBlueprint pending) {
    pending.selectionCommitted = true;
    if (pending.request.size() < 0 || !pending.definition.hasDynamicPatterns()) {
      return;
    }
    var definition = pending.definition;
    var selected = pending.ready.pattern().dynamicLengths();
    commitLengthSelection(player, definition, pending.size, selected);
  }

  private static void commitLengthSelection(ServerPlayer player, MachineDefinition definition, int size, Map<String, Integer> selected) {
    var choices = LENGTH_SELECTIONS.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashMap<>(16, .75F, true));
    choices.put(definition.id(), new LengthSelection(definition, StructureService.definitionEpoch(), size));
    while (choices.size() > 16) {
      choices.remove(choices.keySet().iterator().next());
    }
    var job = JOBS.get(player.getUUID());
    if (job != null
        && job.pattern.machine().equals(definition.id())
        && !job.pattern.dynamicLengths().equals(selected)) {
      removeJob(player.getUUID());
    }
    var building = PENDING_CONSTRUCTIONS.get(player.getUUID());
    if (building != null && building.definition == definition && !building.lengths.equals(selected)) {
      removePendingConstruction(player.getUUID());
    }
    var projection = PROJECTIONS.get(player.getUUID());
    var preparing = PENDING_PROJECTIONS.get(player.getUUID());
    if (projection != null
        && projection.pattern.machine().equals(definition.id())
        && !projection.pattern.dynamicLengths().equals(selected)
        || preparing != null && preparing.definition == definition && !preparing.lengths.equals(selected)) {
      PROJECTIONS.remove(player.getUUID());
      removePendingProjection(player.getUUID());
      ProjectionPayloads.clear(player, request = Math.incrementExact(request));
    }
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

  private static int advanceBlueprint(ServerPlayer player, long deadline, int packetBudget) {
    var pending = PENDING_BLUEPRINTS.get(player.getUUID());
    if (pending == null) {
      return 0;
    }
    if (!current(player, pending)) {
      removePendingBlueprint(player.getUUID());
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
      if (pending.ready == null || !current(player, pending) || PENDING_BLUEPRINTS.get(player.getUUID()) != pending) {
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
          PENDING_BLUEPRINTS.remove(player.getUUID(), pending);
          break;
        }
      } while (packets < packetBudget && System.nanoTime() < deadline);
      return packets;
    } catch (RuntimeException failure) {
      if (PENDING_BLUEPRINTS.remove(player.getUUID(), pending)) {
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

  public static void cancelProjection(ServerPlayer player) {
    if (!canEdit(player)) {
      return;
    }
    PROJECTIONS.remove(player.getUUID());
    removePendingProjection(player.getUUID());
    ProjectionPayloads.clear(player, request = Math.incrementExact(request));
    player.displayClientMessage(Component.translatable("message.mmcece.projection.cancelled"), true);
  }

  private static void cancelConstruction(ServerPlayer player) {
    removeJob(player.getUUID());
    removePendingConstruction(player.getUUID());
  }

  private static void finishConstruction(ServerPlayer player, Job job) {
    if (JOBS.get(player.getUUID()) != job) {
      return;
    }
    var delivery = job.report.takeDelivery(player, job.materialDemand);
    removeJob(player.getUUID(), job);
    if (!delivery.done()) {
      removeWarnings(player.getUUID());
      WARNINGS.put(player.getUUID(), new Warnings(delivery));
    }
  }

  private static void removeWarnings(UUID player) {
    var warnings = WARNINGS.remove(player);
    if (warnings != null) {
      warnings.delivery.cancel();
    }
  }

  private static void removeJob(UUID player) {
    var job = JOBS.remove(player);
    if (job != null) {
      disposeJob(job);
    }
  }

  private static void removeJob(UUID player, Job job) {
    if (JOBS.remove(player, job)) {
      disposeJob(job);
    }
  }

  private static void disposeJob(Job job) {
    if (job.materialAttempt != null) {
      job.materialAttempt.cancel();
    }
    if (job.materialPlanning != null) {
      job.materialPlanning.cancel();
    }
    job.report.cancel();
  }

  /**
   * Client may release only its own exact projection; no world or build writes.
   */
  public static void stopProjection(ServerPlayer player, long expectedRequest) {
    ServerThreadGuard.require(player.serverLevel(), "Projection cancelled");
    var job = PROJECTIONS.get(player.getUUID());
    if (job != null && job.projectionRequest == expectedRequest) {
      PROJECTIONS.remove(player.getUUID());
    }
    var pending = PENDING_PROJECTIONS.get(player.getUUID());
    if (pending != null && pending.request == expectedRequest) {
      removePendingProjection(player.getUUID());
    }
  }

  private static void removePendingConstruction(UUID player) {
    var pending = PENDING_CONSTRUCTIONS.remove(player);
    if (pending != null) {
      pending.preparation.cancel();
    }
  }

  private static void removePendingProjection(UUID player) {
    var pending = PENDING_PROJECTIONS.remove(player);
    if (pending != null) {
      pending.preparation.cancel();
    }
  }

  private static void removePendingBlueprint(UUID player) {
    var pending = PENDING_BLUEPRINTS.remove(player);
    if (pending != null) {
      pending.preparation.cancel();
    }
  }

  /** Legacy assembly selects the slotted blueprint, then the dedicated parent. */
  private static ResourceLocation selectedMachine(StructureService.StructureSubject owner, boolean build) {
    ResourceLocation blueprint = null;
    if (owner instanceof MachineControllerBlockEntity controller) {
      blueprint = controller.blueprintMachineId();
    } else if (owner instanceof FactoryControllerBlockEntity factory) {
      blueprint = factory.blueprintInventory().machine();
    }
    if (blueprint != null) {
      return blueprint;
    }
    var block = owner.structureLevel().getBlockState(owner.structurePosition()).getBlock();
    if (block instanceof MachineControllerBlock controller && controller.dedicated() != null) {
      return controller.dedicated().machine();
    }
    if (block instanceof FactoryControllerBlock factory && factory.dedicated() != null) {
      return factory.dedicated().machine();
    }
    return build ? null : owner.structureMachineId();
  }

  private static boolean validPlayer(ServerPlayer player) {
    return player.isAlive() && !player.isSpectator() && player.getAbilities().mayBuild;
  }

  private record CreativePlacement(BlockPos position, BlockState before, BlockState after) {
  }

  /** {@code matched} counts parts already correct plus parts written now. */
  private record CreativeResult(int matched, int placed) {
  }

  /** Internal failure retaining its translatable reason for the client. */
  private static final class CreativeBuildFailure extends IllegalStateException {
    private final Component reason;

    private CreativeBuildFailure(String translationKey, BlockPos position) {
      super(translationKey + ": " + position.toShortString());
      reason = Component.translatable(translationKey, position.toShortString());
    }
  }

  /**
   * validate the whole structure, then commit it as one batch. Rebuilding over an
   * existing structure is a no-op rather than a failure, and only the controller
   * position needs interaction range (checked by the caller).
   */
  private static CreativeResult creativeBuild(ServerPlayer player, AssemblyPattern pattern) {
    var level = player.serverLevel();
    var placements = new ArrayList<CreativePlacement>();
    int matched = 0;
    for (var part : pattern.parts()) {
      var position = part.position();
      if (level.isOutsideBuildHeight(position)
          || !level.hasChunkAt(position)
          || !level.getWorldBorder().isWithinBounds(position)
          || !level.mayInteract(player, position)
          || part.states().isEmpty()) {
        throw new CreativeBuildFailure("message.mmcece.assembly.creative.position_unavailable", position);
      }
      var before = level.getBlockState(position);
      var after = part.states().get(0);
      if (before.equals(after)) {
        matched++;
        continue;
      }
      placements.add(new CreativePlacement(position, before, after));
    }

    if (placements.isEmpty()) {
      return new CreativeResult(matched, 0);
    }

    int placed = 0;
    placing = true;
    try {
      try {
        for (var placement : placements) {
          boolean written = level.setBlock(placement.position(), placement.after(), BULK_FLAGS);
          if (!written && !level.getBlockState(placement.position()).equals(placement.after())) {
            throw new CreativeBuildFailure("message.mmcece.assembly.creative.write_failed", placement.position());
          }
          placed++;
          if (!level.getBlockState(placement.position()).equals(placement.after())) {
            throw new CreativeBuildFailure("message.mmcece.assembly.creative.state_mismatch", placement.position());
          }
        }
      } catch (RuntimeException failure) {
        for (int index = placed - 1; index >= 0; index--) {
          var placement = placements.get(index);
          level.setBlock(placement.position(), placement.before(), BULK_FLAGS);
        }
        throw failure;
      }
      for (var placement : placements) {
        level.sendBlockUpdated(placement.position(), placement.before(), placement.after(), Block.UPDATE_CLIENTS);
        level.updateNeighborsAt(placement.position(), placement.after().getBlock());
      }
    } finally {
      placing = false;
    }
    return new CreativeResult(matched + placed, placed);
  }

  private static boolean current(ServerPlayer player, Job job) {
    if (!validPlayer(player)
        || player.level() != job.level
        || job.pattern.epoch() != StructureService.definitionEpoch()
        || job.pattern.sourceDefinition() != StructureService.definition(job.pattern.machine())
        || !job.level.hasChunkAt(job.pattern.origin())
        || !job.level.mayInteract(player, job.pattern.origin())
        || job.build && player.hasInfiniteMaterials()
        || !job.build && job.projectionRevision != StructureService.controllerProjectionRevision(job.owner)) {
      return false;
    }
    return !job.owner.structureRemoved()
        && job.owner.structureSchemaSupported()
        && job.level.getBlockEntity(job.pattern.origin()) == job.owner
        && job.pattern.machine().equals(selectedMachine(job.owner, job.build))
        && job.pattern.orientation() == job.owner.structureOrientation(job.level);
  }

  @SubscribeEvent
  public static void tick(Post event) {
    var identities = new LinkedHashSet<>(JOBS.keySet());
    identities.addAll(PROJECTIONS.keySet());
    identities.addAll(PENDING_PROJECTIONS.keySet());
    identities.addAll(PENDING_CONSTRUCTIONS.keySet());
    identities.addAll(PENDING_BLUEPRINTS.keySet());
    var players = new ArrayList<>(identities);
    if (players.isEmpty()) {
      advanceConstructions(event.getServer());
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
        removeJob(id);
        PROJECTIONS.remove(id);
        removePendingProjection(id);
        removePendingConstruction(id);
        removePendingBlueprint(id);
        removeWarnings(id);
        LENGTH_SELECTIONS.remove(id);
      } else {
        prepareConstruction(player, projectionDeadline);
        prepareProjection(player, projectionDeadline);
        var projection = PROJECTIONS.get(id);
        if (projection != null && !current(player, projection)) {
          cancelProjection(player);
        } else if (projection != null) {
          for (int sent = 0; sent < 32
              && packets < 64
              && projection.sent < projection.projectionEntries.size()
              && System.nanoTime() < projectionDeadline; sent++, packets++) {
            projection.sent = ProjectionPayloads.sendChunk(
                player,
                projection.pattern,
                projection.projectionRequest,
                projection.projectionEntries,
                projection.sent);
          }
        }
        packets += advanceBlueprint(player, projectionDeadline, Math.min(32, 64 - packets));
      }
    }
    playerCursor = (start + 1) % players.size();
    advanceConstructions(event.getServer());
  }

  private static void advanceConstructions(MinecraftServer server) {
    var identities = new LinkedHashSet<>(JOBS.keySet());
    identities.addAll(WARNINGS.keySet());
    var players = new ArrayList<>(identities);
    if (players.isEmpty()) {
      return;
    }
    int start = Math.floorMod(assemblyCursor, players.size());
    int visits = 0, idle = 0;
    int remaining = MachineConfig.assemblyWorkPerTick();
    long deadline = System.nanoTime() + MachineConfig.assemblyTimeBudgetNanos();
    while (remaining > 0 && System.nanoTime() < deadline) {
      var id = players.get((start + visits++) % players.size());
      var player = server.getPlayerList().getPlayer(id);
      int used;
      if (!JOBS.containsKey(id) && !WARNINGS.containsKey(id)) {
        used = 0;
      } else if (player == null) {
        removeJob(id);
        removeWarnings(id);
        used = 1;
      } else {
        long sliceDeadline = Math.min(deadline, System.nanoTime() + BUILD_SLICE_NANOS);
        int workBudget = Math.min(remaining, MachineConfig.assemblySliceWork());
        used = sendWarnings(player, workBudget, sliceDeadline);
        if (used < workBudget && System.nanoTime() < sliceDeadline) {
          used += advance(player, workBudget - used, sliceDeadline);
        }
      }
      remaining -= used;
      idle = used == 0 ? idle + 1 : 0;
      if (idle >= players.size()) {
        break;
      }
    }
    assemblyCursor = (start + visits) % players.size();
  }

  private static int sendWarnings(ServerPlayer player, int workBudget, long deadline) {
    var warnings = WARNINGS.get(player.getUUID());
    long tick = player.server.getTickCount();
    if (warnings == null || warnings.lastTick == tick) {
      return 0;
    }
    warnings.lastTick = tick;
    var sent = warnings.delivery.send(player, 4, deadline, workBudget);
    if (sent.done()) {
      if (WARNINGS.get(player.getUUID()) == warnings) {
        removeWarnings(player.getUUID());
      }
    }

    return sent.work();
  }

  private static void prepareConstruction(ServerPlayer player, long deadline) {
    var id = player.getUUID();
    var pending = PENDING_CONSTRUCTIONS.get(id);
    if (pending == null) {
      return;
    }
    if (!current(player, pending)) {
      cancelConstruction(player);
      return;
    }
    if (System.nanoTime() >= deadline) {
      return;
    }
    try {
      var pattern = pending.preparation.advance(deadline);
      if (PENDING_CONSTRUCTIONS.get(id) != pending) {
        return;
      }
      if (!current(player, pending)) {
        cancelConstruction(player);
        return;
      }
      if (pattern == null) {
        return;
      }
      PENDING_CONSTRUCTIONS.remove(id, pending);
      if (pending.creative) {
        // The world mutation retains the existing creative all-or-nothing batch.
        creativeBuild(player, pattern);
      } else {
        JOBS.put(id, new Job(pending.owner, pattern, pending.level, pending.hand, true));
      }
    } catch (RuntimeException failure) {
      if (PENDING_CONSTRUCTIONS.get(id) == pending) {
        removePendingConstruction(id);
      }
      ModularMachine.LOGGER.warn("Construction preparation failed at {}", pending.origin, failure);
      Component reason;
      if (failure instanceof CreativeBuildFailure creativeFailure) {
        reason = creativeFailure.reason;
      } else {
        reason = Component.translatable("message.mmcece.assembly.reason.unexpected");
      }
      player.sendSystemMessage(Component.translatable("message.mmcece.assembly.failed", reason));
    }
  }

  private static boolean current(ServerPlayer player, PendingConstruction pending) {
    if (!validPlayer(player)
        || player.level() != pending.level
        || pending.owner.structureLevel() != pending.level
        || player.hasInfiniteMaterials() != pending.creative
        || pending.epoch != StructureService.definitionEpoch()
        || pending.definition != StructureService.definition(pending.definition.id())
        || pending.owner.structureRemoved()
        || !pending.owner.structureSchemaSupported()
        || !pending.level.hasChunkAt(pending.origin)
        || pending.level.getBlockEntity(pending.origin) != pending.owner
        || !pending.level.mayInteract(player, pending.origin)
        || !pending.definition.id().equals(selectedMachine(pending.owner, true))
        || pending.orientation != pending.owner.structureOrientation(pending.level)) {
      return false;
    }
    var projected = projectedLengths(player, pending.owner, pending.definition);
    if (projected != null) {
      return projected.equals(pending.lengths);
    }
    return true;
  }

  private static void prepareProjection(ServerPlayer player, long deadline) {
    var pending = PENDING_PROJECTIONS.get(player.getUUID());
    if (pending == null) {
      return;
    }
    if (!current(player, pending)) {
      cancelProjection(player);
      return;
    }
    if (System.nanoTime() >= deadline) {
      return;
    }
    try {
      var ready = pending.preparation.advance(deadline);
      if (!current(player, pending) || PENDING_PROJECTIONS.get(player.getUUID()) != pending) {
        return;
      }
      if (ready != null) {
        var job = new Job(pending.owner, ready.pattern(), pending.level, pending.hand, false);
        job.projectionRequest = pending.request;
        job.projectionEntries = ready.entries();
        ModularMachine.LOGGER
            .debug("Projection {} ready: {} entries at {}", pending.request, ready.entries().size(), pending.origin);
        PENDING_PROJECTIONS.remove(player.getUUID());
        PROJECTIONS.put(player.getUUID(), job);
        player.displayClientMessage(Component.translatable("message.mmcece.projection.length", pending.layers), true);
      }
    } catch (RuntimeException failure) {
      ModularMachine.LOGGER.warn("Projection preparation failed at {}", pending.origin, failure);
      cancelProjection(player);
    }
  }

  private static boolean current(ServerPlayer player, PendingProjection pending) {
    return validPlayer(player)
        && player.level() == pending.level
        && pending.epoch == StructureService.definitionEpoch()
        && pending.definition == StructureService.definition(pending.definition.id())
        && pending.controllerRevision == StructureService.controllerProjectionRevision(pending.owner)
        && !pending.owner.structureRemoved()
        && pending.owner.structureSchemaSupported()
        && pending.level.hasChunkAt(pending.origin)
        && pending.level.getBlockEntity(pending.origin) == pending.owner
        && pending.level.mayInteract(player, pending.origin)
        && pending.definition.id().equals(selectedMachine(pending.owner, false))
        && pending.orientation == pending.owner.structureOrientation(pending.level);
  }

  private static int advance(ServerPlayer player, int workBudget, long deadline) {
    var job = JOBS.get(player.getUUID());
    if (job == null || workBudget <= 0 || System.nanoTime() >= deadline) {
      return 0;
    }
    long serverTick = player.server.getTickCount();
    if (!current(player, job)) {
      cancelConstruction(player);
      return 1;
    }
    if (job.level.getGameTime() < job.nextPass || job.waitedTick == serverTick) {
      return 0;
    }
    int used = 0;
    if (job.materials == null) {
      used += prepareMaterials(player, job, workBudget, deadline);
      if (job.materials == null || JOBS.get(player.getUUID()) != job) {
        if (used == 0) {
          job.waitedTick = serverTick;
        }
        return used;
      }
    }
    if (job.materialAttempt == null && !job.materials.hasAvailableMaterials()) {
      finishConstruction(player, job);
      return Math.max(1, used);
    }
    while (used < workBudget && System.nanoTime() < deadline) {
      if (!current(player, job)) {
        cancelConstruction(player);
        return Math.max(1, used);
      }
      used++;
      var part = job.pattern.parts().get(job.cursor);
      var pos = part.position();
      boolean missing = false;
      if (job.level.isOutsideBuildHeight(pos) || !job.level.getWorldBorder().isWithinBounds(pos)) {
        job.report.problem(pos, PlacementOutcome.OUTSIDE_WORLD);
      } else if (!job.level.hasChunkAt(pos)) {
        job.report.problem(pos, PlacementOutcome.UNLOADED);
      } else {
        var actual = job.level.getBlockState(pos);
        if (part.matches(actual)) {
          job.matched++;
        } else if (!PlacementMaterials.canReplace(part.states(), actual)) {
          job.report.occupied(pos, actual);
        } else if (!job.level.mayInteract(player, pos)) {
          job.report.problem(pos, PlacementOutcome.NO_PERMISSION);
        } else {
          placing = true;
          try {
            var result = place(player, job, part);
            if (JOBS.get(player.getUUID()) != job) {
              return used;
            }
            if (result == null) {
              // A detached reassignment is pending; retain this exact position.
              job.waitedTick = serverTick;
              return used;
            }
            // Observe only after the placement's rollback/refund scope has resolved.
            if (!job.level.hasChunkAt(pos)) {
              job.report.problem(pos, PlacementOutcome.UNLOADED);
            } else {
              var after = job.level.getBlockState(pos);
              if (part.matches(after)) {
                job.matched++;
                job.passPlaced++;
                if (result == PlacementOutcome.PLACED) {
                  job.placed++;
                }
              } else if (!PlacementMaterials.canReplace(part.states(), after)) {
                job.report.occupied(pos, after);
              } else if (result == PlacementOutcome.MISSING) {
                missing = true;
              } else {
                job.report.problem(pos, result == PlacementOutcome.PLACED ? PlacementOutcome.REJECTED : result);
              }
            }
          } finally {
            placing = false;
          }
        }
      }
      if (JOBS.get(player.getUUID()) != job) {
        return used;
      }
      if (job.materialAttempt != null) {
        job.materialAttempt.cancel();
        job.materialAttempt = null;
      }
      if (!missing) {
        releaseDemand(job, job.cursor);
      }
      if (job.refreshMaterials) {
        job.refreshMaterials = false;
        job.materials = null;
        if (job.materialPlanning != null) {
          job.materialPlanning.cancel();
        }
        job.materialPlanning = null;
        job.cursor = 0;
        job.matched = 0;
        job.passPlaced = 0;
        return used;
      }
      if (!job.materials.hasAvailableMaterials()) {
        finishConstruction(player, job);
        return used;
      }
      if (++job.cursor == job.pattern.parts().size()) {
        boolean complete = job.matched == job.pattern.parts().size();
        if (complete || job.passPlaced == 0) {
          finishConstruction(player, job);
          return used;
        }
        job.cursor = 0;
        job.matched = 0;
        job.passPlaced = 0;
        job.report.nextPass();
        job.materials = null;
        job.materialPlanning = null;
        job.materialCursor = 0;
        job.materialDemand.clear();
        job.demandPositions.clear();
        job.fluidOnlyDemand.clear();
        job.nextPass = job.level.getGameTime() + 20;
        return used;
      }
    }

    return used;
  }

  private static void releaseDemand(Job job, int index) {
    if (!job.demandPositions.get(index)) {
      return;
    }
    var choices = job.report.choices(job.pattern.parts().get(index));
    if (job.fluidOnlyDemand.get(index)) {
      choices = new PlacementMaterials.Choices(List.of(), choices.fluids());
    }
    job.materialDemand.computeIfPresent(choices, (key, count) -> count <= 1 ? null : count - 1);
    job.demandPositions.clear(index);
    job.fluidOnlyDemand.clear(index);
  }

  private static int prepareMaterials(ServerPlayer player, Job job, int workBudget, long deadline) {
    int used = 0;
    if (job.materialCursor < job.pattern.parts().size()) {
      while (used < workBudget && System.nanoTime() < deadline && job.materialCursor < job.pattern.parts().size()) {
        if (!current(player, job)) {
          cancelConstruction(player);
          return Math.max(1, used);
        }
        used++;
        int index = job.materialCursor++;
        var part = job.pattern.parts().get(index);
        var pos = part.position();
        if (job.level.isOutsideBuildHeight(pos) || !job.level.getWorldBorder().isWithinBounds(pos)) {
          job.report.problem(pos, PlacementOutcome.OUTSIDE_WORLD);
        } else if (!job.level.hasChunkAt(pos)) {
          job.report.problem(pos, PlacementOutcome.UNLOADED);
        } else {
          var actual = job.level.getBlockState(pos);
          if (part.matches(actual)) {
            continue;
          } else if (!PlacementMaterials.canReplace(part.states(), actual)) {
            job.report.occupied(pos, actual);
          } else if (!job.level.mayInteract(player, pos)) {
            job.report.problem(pos, PlacementOutcome.NO_PERMISSION);
          } else {
            var choices = job.report.choices(part);
            if (!actual.isAir()) {
              choices = new PlacementMaterials.Choices(List.of(), choices.fluids());
            }
            if (choices.items().isEmpty() && choices.fluids().isEmpty()) {
              job.report.problem(pos, PlacementOutcome.MANUAL);
            } else {
              job.materialDemand.merge(choices, 1, Integer::sum);
              job.demandPositions.set(index);
              job.fluidOnlyDemand.set(index, !actual.isAir());
            }
          }
        }
      }
      if (job.materialCursor < job.pattern.parts().size()) {
        return used;
      }
    }
    if (used >= workBudget || System.nanoTime() >= deadline) {
      return used;
    }
    if (job.materialPlanning == null || !job.materialPlanning.inventoryMatches(player)) {
      if (job.materialPlanning != null) {
        job.materialPlanning.cancel();
      }
      job.materialPlanning = new AssemblyMaterialPlan(job.materialDemand, player);
      used++;
    }
    try {
      if (used < workBudget && System.nanoTime() < deadline) {
        var advanced = job.materialPlanning.advance(deadline, workBudget - used);
        used += advanced.work();
        if (advanced.result() != null) {
          job.materials = advanced.result();
          used = Math.min(workBudget, Math.max(1, used));
        } else if (job.materialPlanning.waitingForAllocation()) {
          job.waitedTick = player.server.getTickCount();
        }
      }
    } catch (RuntimeException failure) {
      cancelConstruction(player);
      ModularMachine.LOGGER.warn("Assembly material planning failed", failure);
      player.sendSystemMessage(Component.translatable("message.mmcece.assembly.material_planning_failed"));
      used = Math.max(1, used);
    }

    return used;
  }

  private static PlacementOutcome place(ServerPlayer player, Job job, AssemblyPattern.Part part) {
    var choices = job.report.choices(part);
    if (!job.level.getBlockState(part.position()).isAir()) {
      choices = new PlacementMaterials.Choices(List.of(), choices.fluids());
    }
    if (job.materialAttempt == null || !job.materialAttempt.choices.equals(choices)) {
      if (job.materialAttempt != null) {
        job.materialAttempt.cancel();
      }
      job.materialAttempt = new MaterialAttempt(choices, job.materials.next(choices));
    }
    var attempt = job.materialAttempt;
    if (attempt.failure != null) {
      try {
        if (attempt.retry == null) {
          attempt.retry = job.materials.retry(choices, attempt.rejected);
        }
        if (attempt.retry == null || !attempt.retry.isDone()) {
          return null;
        }
        var replacement = attempt.retry.join();
        attempt.retry = null;
        if (replacement == null) {
          return attempt.failure;
        }
        job.materials.install(replacement);
        attempt.resource = replacement.resource();
        attempt.failure = null;
      } catch (RuntimeException failure) {
        cancelConstruction(player);
        ModularMachine.LOGGER.warn("Assembly material reassignment failed", failure);
        player.sendSystemMessage(Component.translatable("message.mmcece.assembly.material_planning_failed"));
        return PlacementOutcome.ERROR;
      }
    }
    var resource = attempt.resource;
    if (resource == null) {
      return PlacementOutcome.MISSING;
    }
    var result = placeResource(player, job, part, choices, resource);
    if (result == PlacementOutcome.MISSING) {
      job.materials.unavailable(resource);
      if (job.materialPlanning != null && !job.materialPlanning.inventoryMatches(player)) {
        // Another actor changed the backpack since planning. Re-capture its
        // remaining partial supply instead of requiring a full group replacement.
        job.refreshMaterials = true;
        return result;
      }
    }
    boolean retryable = switch (result) {
      case MISSING, INVALID_STATE, SUPPORT, COLLISION, FLUID_VAPORIZES, FLUID_CONTAINER -> true;
      default -> false;
    };
    if (retryable && JOBS.get(player.getUUID()) == job) {
      attempt.rejected.add(resource);
      attempt.failure = result;
      return null;
    }
    return result;
  }

  private static PlacementOutcome placeResource(ServerPlayer player, Job job, AssemblyPattern.Part part, PlacementMaterials.Choices choices, AssemblyMaterialPlan.Resource resource) {
    var inventory = player.getInventory();
    if (job.level.captureBlockSnapshots) {
      return PlacementOutcome.BUSY;
    }
    if (resource.item() == null) {
      var result = placeFluid(player, job, part, resource.fluid());
      if (result == PlacementOutcome.PLACED) {
        job.materials.used(choices, resource);
      }
      return result;
    }
    var outcome = PlacementOutcome.MISSING;
    for (int slot = 0; slot < inventory.items.size(); slot++) {
      var material = inventory.getItem(slot);
      // Existing saved inventories/run data and custom placement implementations need
      // dedicated adapters.
      if (job.level.captureBlockSnapshots
          || material.isEmpty()
          || material.getItem() != resource.item()
          || !PlacementMaterials.automaticItem(material.getItem())
          || !material.getComponentsPatch().isEmpty()) {
        continue;
      }
      var blockItem = (BlockItem) material.getItem();
      var candidate = part.states().stream().filter(state -> state.getBlock() == blockItem.getBlock()).findFirst()
          .orElse(null);
      if (candidate == null) {
        continue;
      }
      ItemStack expected = material.copy();
      ItemStack reserved = material.copyWithCount(1);
      var properties = BlockItemStateProperties.EMPTY;
      for (var property : part.rule().alternatives()) {
        if (property.block() == candidate.getBlock()) {
          for (var key : property.properties().keySet()) {
            properties = properties.with(key, candidate);
          }
          break;
        }
      }
      if (!properties.isEmpty()) {
        reserved.set(DataComponents.BLOCK_STATE, properties);
      }
      var hit = new BlockHitResult(Vec3.atCenterOf(part.position()), Direction.UP, part.position(), false);
      var context = new BlockPlaceContext(job.level, player, job.hand, reserved, hit);
      var initial = blockItem.getBlock().getStateForPlacement(context);
      var desired = initial == null ? null : properties.apply(initial);
      if (!context.getClickedPos().equals(part.position()) || desired == null || !part.matches(desired)) {
        outcome = PlacementOutcome.INVALID_STATE;
        continue;
      }
      if (!desired.canSurvive(job.level, part.position())) {
        outcome = PlacementOutcome.SUPPORT;
        continue;
      }
      if (!job.level.isUnobstructed(desired, part.position(), CollisionContext.of(player))) {
        outcome = PlacementOutcome.COLLISION;
        continue;
      }
      if (!current(player, job)
          || inventory.getItem(slot).isEmpty()
          || !ItemStack.matches(inventory.getItem(slot), expected)
          || !job.level.getBlockState(part.position()).isAir()) {
        return PlacementOutcome.CHANGED;
      }
      ItemStack taken = inventory.removeItem(slot, 1);
      if (taken.isEmpty()) {
        return PlacementOutcome.CHANGED;
      }
      boolean rollback = false;
      boolean failed = false;
      boolean actionCommitted = false;
      try {
        // ItemStack.useOn owns NeoForge snapshots, place events, cancelled placement
        // rollback and consumption.
        var result = reserved.useOn(context);
        actionCommitted = result.consumesAction();
        if (actionCommitted) {
          job.materials.used(choices, resource);
        }
        return actionCommitted
            && part.matches(
                job.level.getBlockState(part.position())) ? PlacementOutcome.PLACED : PlacementOutcome.REJECTED;
      } catch (RuntimeException failure) {
        failed = true;
        removeJob(player.getUUID());
        ModularMachine.LOGGER.error("Assembly placement failed at {}", part.position(), failure);
        player.sendSystemMessage(AssemblyReport.problemMessage(part.position(), PlacementOutcome.ERROR));
        return PlacementOutcome.ERROR;
      } finally {
        if (job.level.captureBlockSnapshots) {
          job.level.captureBlockSnapshots = false;
          var snapshots = new ArrayList<>(job.level.capturedBlockSnapshots);
          job.level.capturedBlockSnapshots.clear();
          job.level.restoringBlockSnapshots = true;

          try {
            for (int index = snapshots.size() - 1; index >= 0; index--) {
              snapshots.get(index).restore();
            }
            rollback = job.level.getBlockState(part.position()).isAir();
          } finally {
            job.level.restoringBlockSnapshots = false;
          }
        }
        // Never overwrite a callback-edited slot. Return the actual unused reservation
        // to inventory or drop it. A successful action stays paid if a callback enabled
        // infinite materials before the native stack consumption.
        if (!taken.isEmpty()
            && (rollback
                || !actionCommitted
                    && !reserved.isEmpty()
                    && (!failed || job.level.getBlockState(part.position()).isAir()))) {
          PlayerInventoryItems.returnOrDrop(player, slot, taken);
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
      }
    }
    return outcome;
  }

  private static PlacementOutcome placeFluid(ServerPlayer player, Job job, AssemblyPattern.Part part, FluidStack fluid) {
    try {
      return AssemblyFluidPlacement
          .place(player, part, fluid, job.materials.reservedBlocks, () -> current(player, job));
    } catch (RuntimeException failure) {
      removeJob(player.getUUID(), job);
      ModularMachine.LOGGER.error("Fluid assembly failed at {}", part.position(), failure);
      player.sendSystemMessage(AssemblyReport.problemMessage(part.position(), PlacementOutcome.ERROR));
      return PlacementOutcome.ERROR;
    }
  }
  @SubscribeEvent
  public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
    removeJob(event.getEntity().getUUID());
    removeWarnings(event.getEntity().getUUID());
    PROJECTIONS.remove(event.getEntity().getUUID());
    removePendingProjection(event.getEntity().getUUID());
    removePendingConstruction(event.getEntity().getUUID());
    removePendingBlueprint(event.getEntity().getUUID());
    LENGTH_SELECTIONS.remove(event.getEntity().getUUID());
  }
  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    JOBS.values().forEach(AssemblyService::disposeJob);
    JOBS.clear();
    WARNINGS.values().forEach(warnings -> warnings.delivery.cancel());
    WARNINGS.clear();
    PROJECTIONS.clear();
    PENDING_PROJECTIONS.values().forEach(pending -> pending.preparation.cancel());
    PENDING_PROJECTIONS.clear();
    PENDING_CONSTRUCTIONS.values().forEach(pending -> pending.preparation.cancel());
    PENDING_CONSTRUCTIONS.clear();
    PENDING_BLUEPRINTS.values().forEach(pending -> pending.preparation.cancel());
    PENDING_BLUEPRINTS.clear();
    LENGTH_SELECTIONS.clear();
    ProjectionPreparation.clearCache();
    placing = false;
    assemblyCursor = 0;
    playerCursor = 0;
  }
}
