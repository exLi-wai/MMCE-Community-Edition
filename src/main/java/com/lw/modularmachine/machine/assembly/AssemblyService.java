package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;

/**
 * One server task per player, 16 inspections/2 placements per tick; no
 * persistent prepaid work.
 */
public final class AssemblyService {
  private static final Map<UUID, Job> JOBS = new HashMap<>();
  private static final Map<UUID, Job> PROJECTIONS = new HashMap<>();
  private static final Map<UUID, PendingProjection> PENDING_PROJECTIONS = new HashMap<>();
  private static final int BULK_FLAGS = Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
  private static long request;
  private static boolean placing;
  private static int playerCursor;
  private static long budgetTick = -1;
  private static int budget;

  private static final class Job {
    final StructureService.StructureSubject owner;
    final AssemblyPattern pattern;
    final ServerLevel level;
    final InteractionHand hand;
    final boolean build;
    int cursor, matched, placed;
    long nextPass;
    long projectionRequest = -1;
    List<ProjectionPayloads.Entry> projectionEntries = List.of();
    int sent;
    Job(StructureService.StructureSubject owner, AssemblyPattern pattern, ServerLevel level, InteractionHand hand, boolean build) {
      this.owner = owner;
      this.pattern = pattern;
      this.level = level;
      this.hand = hand;
      this.build = build;
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
    final long epoch, request;
    final InteractionHand hand;
    final ProjectionPreparation preparation;
    PendingProjection(StructureService.StructureSubject owner, ServerLevel level, MachineDefinition definition, MachineOrientation orientation, long request, InteractionHand hand) {
      this.owner = owner;
      this.level = level;
      this.definition = definition;
      this.orientation = orientation;
      this.request = request;
      this.hand = hand;
      origin = owner.structurePosition().immutable();
      epoch = StructureService.definitionEpoch();
      preparation = new ProjectionPreparation(definition, epoch, origin, orientation);
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
    if (!build) {
      var id = player.getUUID();
      var pending = PENDING_PROJECTIONS.get(id);
      if (pending != null
          && pending.owner == owner
          && pending.definition == definition
          && pending.orientation == orientation
          && pending.epoch == StructureService.definitionEpoch()) {
        return;
      }
      var previous = PROJECTIONS.get(id);
      if (previous != null
          && previous.owner == owner
          && previous.pattern.machine().equals(machine)
          && previous.pattern.orientation() == orientation
          && previous.pattern.epoch() == StructureService.definitionEpoch()) {
        return;
      }
      PROJECTIONS.remove(id);
      ProjectionPayloads.clear(player, request = Math.incrementExact(request));
      long selectedRequest = request = Math.incrementExact(request);
      PENDING_PROJECTIONS
          .put(id, new PendingProjection(owner, player.serverLevel(), definition, orientation, selectedRequest, hand));
      // Header lets the client cancel by distance even during preparation.
      ProjectionPayloads.begin(
          player,
          selectedRequest,
          StructureService.definitionEpoch(),
          machine,
          owner.structurePosition(),
          definition.parts().size());
      return;
    }
    var pattern = AssemblyPattern
        .compile(definition, StructureService.definitionEpoch(), owner.structurePosition(), orientation);
    if (build && player.isCreative()) {
      try {
        var result = creativeBuild(player, pattern);
        player.displayClientMessage(
            Component.translatable(
                "message.mmcece.assembly.complete",
                result.matched(),
                pattern.parts().size(),
                result.placed()),
            true);
      } catch (IllegalStateException failure) {
        player
            .displayClientMessage(Component.translatable("message.mmcece.assembly.failed", failure.getMessage()), true);
      }
      return;
    }
    var previous = JOBS.get(player.getUUID());
    if (previous != null && previous.owner == owner && previous.pattern.equals(pattern)) {
      return;
    }
    var job = new Job(owner, pattern, player.serverLevel(), hand, build);
    JOBS.put(player.getUUID(), job);
    player.displayClientMessage(
        Component.translatable("message.mmcece.assembly.started", pattern.machine().toString()),
        true);
  }

  public static boolean canEdit(ServerPlayer player) {
    ServerThreadGuard.require(player.serverLevel(), "Blueprint interaction");
    return !placing
        && validPlayer(player)
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && !RecipeLifecyclePublisher.isDispatching()
        && !SmartInterfaceEventPublisher.isDispatching();
  }

  public static void cancelProjection(ServerPlayer player) {
    if (!canEdit(player)) {
      return;
    }
    PROJECTIONS.remove(player.getUUID());
    PENDING_PROJECTIONS.remove(player.getUUID());
    ProjectionPayloads.clear(player, request = Math.incrementExact(request));
    player.displayClientMessage(Component.translatable("message.mmcece.projection.cancelled"), true);
  }

  private static void cancelConstruction(ServerPlayer player) {
    JOBS.remove(player.getUUID());
    player.displayClientMessage(Component.translatable("message.mmcece.assembly.cancelled"), true);
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
      PENDING_PROJECTIONS.remove(player.getUUID());
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

  /**
   * GTM-style creative assembly: validate the whole structure, then commit it as
   * one batch. Rebuilding over an existing structure is a no-op rather than a
   * failure, and only the controller position needs interaction range (checked by
   * the caller).
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
        throw new IllegalStateException("位置不可用（区块未加载/超出边界/无权限）：" + position.toShortString());
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
            throw new IllegalStateException("写入失败：" + placement.position().toShortString());
          }
          placed++;
          if (!level.getBlockState(placement.position()).equals(placement.after())) {
            throw new IllegalStateException("状态校验失败：" + placement.position().toShortString());
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
        || !job.level.hasChunkAt(job.pattern.origin())
        || !job.level.mayInteract(player, job.pattern.origin())
        || job.build && !player.canInteractWithBlock(job.pattern.origin(), 0)) {
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
    var players = new ArrayList<>(identities);
    if (players.isEmpty()) {
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
        JOBS.remove(id);
        PROJECTIONS.remove(id);
        PENDING_PROJECTIONS.remove(id);
      } else {
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
        advance(player);
      }
    }
    playerCursor = (start + 1) % players.size();
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
        player.displayClientMessage(
            Component.translatable("message.mmcece.assembly.projected", pending.definition.id().toString()),
            true);
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
        && !pending.owner.structureRemoved()
        && pending.owner.structureSchemaSupported()
        && pending.level.hasChunkAt(pending.origin)
        && pending.level.getBlockEntity(pending.origin) == pending.owner
        && pending.level.mayInteract(player, pending.origin)
        && pending.definition.id().equals(selectedMachine(pending.owner, false))
        && pending.orientation == pending.owner.structureOrientation(pending.level);
  }

  private static void advance(ServerPlayer player) {
    var job = JOBS.get(player.getUUID());
    if (job == null) {
      return;
    }
    if (!current(player, job)) {
      cancelConstruction(player);
      return;
    }
    if (job.level.getGameTime() < job.nextPass) {
      return;
    }
    long serverTick = player.server.getTickCount();
    if (serverTick != budgetTick) {
      budgetTick = serverTick;
      budget = 128;
    }
    if (budget <= 0) {
      return;
    }
    int writes = 0;
    for (int work = 0; work < 16 && writes < 2 && budget-- > 0; work++) {
      if (!current(player, job)) {
        cancelConstruction(player);
        return;
      }
      var part = job.pattern.parts().get(job.cursor);
      var pos = part.position();
      if (!job.level.isOutsideBuildHeight(pos)
          && job.level.hasChunkAt(pos)
          && job.level.getWorldBorder().isWithinBounds(pos)) {
        if (part.matches(job.level.getBlockState(pos))) {
          job.matched++;
        } else if (player.canInteractWithBlock(pos, 0)
            && job.level.mayInteract(player, pos)
            && job.level.getBlockState(pos).isAir()) {
          placing = true;
          try {
            if (place(player, job, part)) {
              job.matched++;
              job.placed++;
              writes++;
            }
          } finally {
            placing = false;
          }
        }
      }
      if (JOBS.get(player.getUUID()) != job) {
        return;
      }
      if (++job.cursor == job.pattern.parts().size()) {
        boolean complete = job.matched == job.pattern.parts().size();
        player.displayClientMessage(
            Component.translatable(
                complete ? "message.mmcece.assembly.complete" : "message.mmcece.assembly.waiting",
                job.matched,
                job.pattern.parts().size(),
                job.placed),
            true);
        if (complete) {
          JOBS.remove(player.getUUID(), job);
          return;
        }
        job.cursor = 0;
        job.matched = 0;
        job.nextPass = job.level.getGameTime() + 20;
        return;
      }
    }
  }

  private static boolean place(ServerPlayer player, Job job, AssemblyPattern.Part part) {
    var inventory = player.getInventory();
    for (int slot = 0; slot < inventory.items.size(); slot++) {
      var material = inventory.getItem(slot);
      // Existing saved inventories/run data and custom placement implementations need
      // dedicated adapters.
      if (job.level.captureBlockSnapshots
          || material.isEmpty()
          || material.getItem().getClass() != BlockItem.class
          || !material.getComponentsPatch().isEmpty()) {
        continue;
      }
      var blockItem = (BlockItem) material.getItem();
      if (blockItem.getBlock() instanceof BedBlock
          || blockItem.getBlock() instanceof DoorBlock
          || blockItem.getBlock() instanceof DoublePlantBlock) {
        continue;
      }
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
            properties = stateProperty(properties, key, candidate);
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
      if (!context.getClickedPos().equals(part.position())
          || desired == null
          || !part.matches(desired)
          || !desired.canSurvive(job.level, part.position())
          || !job.level.isUnobstructed(desired, part.position(), CollisionContext.of(player))) {
        continue;
      }
      if (!current(player, job)
          || inventory.getItem(slot).isEmpty()
          || !ItemStack.matches(inventory.getItem(slot), expected)
          || !job.level.getBlockState(part.position()).isAir()) {
        return false;
      }
      ItemStack taken = player.isCreative() ? ItemStack.EMPTY : inventory.removeItem(slot, 1);
      if (!player.isCreative() && taken.isEmpty()) {
        return false;
      }
      boolean rollback = false;
      boolean failed = false;
      try {
        // ItemStack.useOn owns NeoForge snapshots, place events, cancelled placement
        // rollback and consumption.
        var result = reserved.useOn(context);
        return result.consumesAction() && part.matches(job.level.getBlockState(part.position()));
      } catch (RuntimeException failure) {
        failed = true;
        JOBS.remove(player.getUUID());
        ModularMachine.LOGGER.error("Assembly placement failed at {}", part.position(), failure);
        return false;
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
        // to inventory or drop it.
        if (!taken.isEmpty()
            && (rollback || !reserved.isEmpty() && (!failed || job.level.getBlockState(part.position()).isAir()))) {
          if (!inventory.add(taken)) {
            player.drop(taken, false);
          }
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
      }
    }
    return false;
  }
  private static <T extends Comparable<T>> BlockItemStateProperties stateProperty(BlockItemStateProperties properties, Property<T> property, BlockState state) {
    return properties.with(property, state);
  }
  @SubscribeEvent
  public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
    JOBS.remove(event.getEntity().getUUID());
    PROJECTIONS.remove(event.getEntity().getUUID());
    PENDING_PROJECTIONS.remove(event.getEntity().getUUID());
  }
  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    JOBS.clear();
    PROJECTIONS.clear();
    PENDING_PROJECTIONS.clear();
    ProjectionPreparation.clearCache();
    placing = false;
    budgetTick = -1;
    budget = 0;
    playerCursor = 0;
  }
}
