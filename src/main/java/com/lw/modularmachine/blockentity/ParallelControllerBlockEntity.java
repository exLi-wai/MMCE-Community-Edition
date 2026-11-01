package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.machine.ParallelControllerTier;
import com.lw.modularmachine.machine.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.machine.smart.SmartInterfaceEvents;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.menu.ParallelControllerMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Editable additive parallel contribution; tier capacity is always server
 * configuration.
 */
public final class ParallelControllerBlockEntity extends BlockEntity implements MenuProvider {
  private final ParallelControllerTier tier;
  private UUID instance = UUID.randomUUID();
  private Integer configured;
  private long revision;
  private Tag opaque;

  public ParallelControllerBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.PARALLEL_CONTROLLER_ENTITY.get(), position, state);
    tier = ((ParallelControllerBlock) state.getBlock()).tier();
  }

  public ParallelControllerTier tier() {
    return tier;
  }

  public UUID instance() {
    return instance;
  }

  public long revision() {
    return revision;
  }

  public boolean schemaSupported() {
    return opaque == null;
  }

  public int maximum() {
    return tier.maximum();
  }

  public int parallelism() {
    return configured == null ? maximum() : Math.min(configured, maximum());
  }

  public boolean update(Player player, long expectedRevision, int value) {
    if (!(level instanceof ServerLevel server)) {
      return false;
    }
    ServerThreadGuard.require(server, "Parallel controller update");
    if (!schemaSupported()
        || isRemoved()
        || SmartInterfaceEvents.isDispatching()
        || RecipeLifecycleEvents.isDispatching()
        || revision == Long.MAX_VALUE
        || revision != expectedRevision
        || value < 0
        || value > maximum()
        || player.level() != server
        || player.isSpectator()
        || !player.mayBuild()
        || player.distanceToSqr(worldPosition.getCenter()) > 64
        || !server.hasChunkAt(worldPosition)
        || server.getBlockEntity(worldPosition) != this
        || !server.mayInteract(player, worldPosition)) {
      return false;
    }
    if (parallelism() == value) {
      return true;
    }
    configured = value;
    revision++;
    setChanged();

    return true;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    notifyStructure();
  }

  @Override
  public void setRemoved() {
    notifyStructure();
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    notifyStructure();
    super.onChunkUnloaded();
  }

  private void notifyStructure() {
    if (level instanceof ServerLevel server) {
      StructureService.blockChanged(server, worldPosition);
    }
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (opaque != null) {
      tag.put("parallel_controller", opaque.copy());
      return;
    }
    var data = new CompoundTag();
    data.putInt("schema", 1);
    data.putUUID("instance", instance);
    data.putLong("revision", revision);
    if (configured != null) {
      data.putInt("value", configured);
    }
    tag.put("parallel_controller", data);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Parallel controller load");
      if (SmartInterfaceEvents.isDispatching() || RecipeLifecycleEvents.isDispatching()) {
        throw new IllegalStateException("Cannot reload parallel controls during a read-only notification");
      }
    }
    super.loadAdditional(tag, registries);
    configured = null;
    opaque = null;
    revision = 0;
    if (!tag.contains("parallel_controller")) {
      return;
    }
    var data = tag.getCompound("parallel_controller");
    if (!tag.contains("parallel_controller", Tag.TAG_COMPOUND)
        || !Set.of("schema", "instance", "revision", "value").containsAll(data.getAllKeys())
        || !data.contains("schema", Tag.TAG_INT)
        || data.getInt("schema") != 1
        || !data.hasUUID("instance")
        || !data.contains("revision", Tag.TAG_LONG)
        || data.getLong("revision") < 0
        || data.contains("value") && (!data.contains("value", Tag.TAG_INT) || data.getInt("value") < 0)) {
      opaque = tag.get("parallel_controller").copy();
      notifyStructure();
      return;
    }
    instance = data.getUUID("instance");
    revision = data.getLong("revision");
    configured = data.contains("value") ? data.getInt("value") : null;
    notifyStructure();
  }

  @Override
  public Component getDisplayName() {
    return getBlockState().getBlock().getName();
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    return schemaSupported() ? new ParallelControllerMenu(id, inventory, this) : null;
  }
}
