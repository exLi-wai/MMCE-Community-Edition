package com.lw.modularmachine.integration.neovitae;

import com.breakinblocks.neovitae.common.entity.projectile.EntityMeteor;
import com.breakinblocks.neovitae.common.recipe.meteor.MeteorRecipeHelper;
import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.blockentity.MachineCasingBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.recipe.resource.world.MeteorPort;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MeteorHatchBlockEntity extends MachineCasingBlockEntity implements TickingRecipeHatchBlock.ServerTicking, MeteorPort {
  private static final String LAUNCH = "meteor_launch";
  private Launch launch;
  private boolean reserved;
  private boolean spawning;
  private int loadedTicks;

  public MeteorHatchBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.METEOR_ENTITY.get(), position, state);
  }

  @Override
  public boolean available(ResourceLocation catalyst) {
    var server = liveLevel();
    return server != null
        && !reserved
        && launch == null
        && target(server, worldPosition) != null
        && hasRecipe(server, catalyst);
  }

  @Override
  public RecipeResourceParticipant acquire(ResourceLocation catalyst) {
    if (!available(catalyst) || RecipeLifecyclePublisher.isDispatching()) {
      throw new IllegalStateException("Meteor hatch is not ready for the reserved catalyst");
    }
    var pending = new Launch(catalyst, UUID.randomUUID(), false);
    reserved = true;
    return commit -> {
      reserved = false;
      if (commit) {
        launch = pending;
      }
    };
  }

  @Override
  public void publishLaunch() {
    if (liveLevel() != null) {
      setChanged();
    } else if (level instanceof ServerLevel server) {
      transferLaunch(server);
    }
  }

  void transferLaunch(ServerLevel server) {
    if (!spawning && launch != null && !launch.spawned()) {
      MeteorLaunchQueue.enqueue(server, worldPosition, launch.catalyst(), launch.id());
      launch = null;
    }
  }

  @Override
  public void tickHatch() {
    var server = liveLevel();
    if (server == null || RecipeLifecyclePublisher.isDispatching()) {
      return;
    }
    loadedTicks = Math.min(loadedTicks + 1, 40);
    if (loadedTicks < 40 || launch == null || Math.floorMod(server.getGameTime() + worldPosition.asLong(), 10) != 0) {
      return;
    }
    if (server.getEntity(launch.id()) != null) {
      if (!launch.spawned()) {
        launch = launch.spawnedCopy();
        setChanged();
      }
      return;
    }
    if (launch.spawned()) {
      launch = null;
      setChanged();
      return;
    }
    var pending = launch;
    spawning = true;
    try {
      if (trySpawn(server, worldPosition, pending.catalyst(), pending.id())) {
        launch = pending.spawnedCopy();
        setChanged();
      }
    } finally {
      spawning = false;
      if (liveLevel() == null) {
        transferLaunch(server);
      }
    }
  }

  static boolean trySpawn(ServerLevel server, BlockPos origin, ResourceLocation catalyst, UUID id) {
    var target = target(server, origin);
    var stack = catalystStack(catalyst);
    if (target == null || stack.isEmpty() || MeteorRecipeHelper.findRecipe(server, stack) == null) {
      return false;
    }
    var meteor = new EntityMeteor(server, target.getX() + 0.5, server.getMaxBuildHeight() - 1, target.getZ() + 0.5);
    meteor.setUUID(id);
    meteor.setContainedStack(stack);
    meteor.setTargetY(target.getY());
    return server.addFreshEntity(meteor);
  }

  private static BlockPos target(ServerLevel server, BlockPos origin) {
    if (!server.dimensionType().hasSkyLight() || origin.getY() >= server.getMaxBuildHeight() - 2) {
      return null;
    }
    var target = origin;
    var top = server
        .getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, origin.getX(), origin.getZ());
    for (int height = origin.getY() + 1; height < top; height++) {
      var position = origin.atY(height);
      if (!server.getBlockState(position).isAir()) {
        if (target != origin || height >= server.getMaxBuildHeight() - 2) {
          return null;
        }
        target = position;
      }
    }
    return target;
  }

  private ServerLevel liveLevel() {
    if (!(level instanceof ServerLevel server)) {
      return null;
    }
    ServerThreadGuard.require(server, "Meteor hatch access");
    return !isRemoved()
        && server.hasChunkAt(worldPosition)
        && server.getBlockEntity(worldPosition) == this ? server : null;
  }

  private static boolean hasRecipe(ServerLevel server, ResourceLocation catalyst) {
    var stack = catalystStack(catalyst);
    return !stack.isEmpty() && MeteorRecipeHelper.findRecipe(server, stack) != null;
  }

  private static ItemStack catalystStack(ResourceLocation catalyst) {
    return BuiltInRegistries.ITEM.getOptional(catalyst).map(ItemStack::new).orElse(ItemStack.EMPTY);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (launch != null) {
      var value = new CompoundTag();
      value.putString("catalyst", launch.catalyst().toString());
      value.putUUID("id", launch.id());
      value.putBoolean("spawned", launch.spawned());
      tag.put(LAUNCH, value);
    }
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    var value = tag.getCompound(LAUNCH);
    var catalyst = ResourceLocation.tryParse(value.getString("catalyst"));
    launch = catalyst != null
        && value.hasUUID("id") ? new Launch(catalyst, value.getUUID("id"), value.getBoolean("spawned")) : null;
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = new CompoundTag();
    MachineColorable.save(this, tag, machineColor());
    return tag;
  }

  private record Launch(ResourceLocation catalyst, UUID id, boolean spawned) {
    private Launch spawnedCopy() {
      return new Launch(catalyst, id, true);
    }
  }
}
