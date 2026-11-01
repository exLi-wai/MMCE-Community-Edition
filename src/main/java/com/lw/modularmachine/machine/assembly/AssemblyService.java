package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEvents;
import com.lw.modularmachine.machine.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.machine.smart.SmartInterfaceEvents;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.network.ProjectionPayloads;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    var jobs = build ? JOBS : PROJECTIONS;
    var previous = jobs.get(player.getUUID());
    if (previous != null && previous.owner == owner && previous.pattern.equals(pattern)) {
      return;
    }
    jobs.put(player.getUUID(), new Job(owner, pattern, player.serverLevel(), hand, build));
    if (!build) {
      ProjectionPayloads.send(player, pattern, request = Math.incrementExact(request));
    }
    player.displayClientMessage(
        Component.translatable(
            build ? "message.mmcece.assembly.started" : "message.mmcece.assembly.projected",
            pattern.machine().toString()),
        true);
  }

  public static boolean canEdit(ServerPlayer player) {
    ServerThreadGuard.require(player.serverLevel(), "Blueprint interaction");
    return !placing
        && validPlayer(player)
        && !FactoryUpgradeEvents.isDispatchingAny()
        && !RecipeLifecycleEvents.isDispatching()
        && !SmartInterfaceEvents.isDispatching();
  }

  public static void cancelProjection(ServerPlayer player) {
    if (!canEdit(player)) {
      return;
    }
    PROJECTIONS.remove(player.getUUID());
    ProjectionPayloads.clear(player, request = Math.incrementExact(request));
    player.displayClientMessage(Component.translatable("message.mmcece.projection.cancelled"), true);
  }

  private static void cancelConstruction(ServerPlayer player) {
    JOBS.remove(player.getUUID());
    player.displayClientMessage(Component.translatable("message.mmcece.assembly.cancelled"), true);
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
        || !player.canInteractWithBlock(job.pattern.origin(), 0)) {
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
    var players = new ArrayList<>(identities);
    if (players.isEmpty()) {
      return;
    }
    int start = Math.floorMod(playerCursor, players.size());
    int count = Math.min(players.size(), 8);
    for (int index = 0; index < count; index++) {
      var id = players.get((start + index) % players.size());
      var player = event.getServer().getPlayerList().getPlayer(id);
      if (player == null) {
        JOBS.remove(id);
        PROJECTIONS.remove(id);
      } else {
        var projection = PROJECTIONS.get(id);
        if (projection != null && !current(player, projection)) {
          cancelProjection(player);
        }
        advance(player);
      }
    }
    playerCursor = (start + count) % players.size();
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
  }
  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    JOBS.clear();
    PROJECTIONS.clear();
    placing = false;
    budgetTick = -1;
    budget = 0;
    playerCursor = 0;
  }
}
