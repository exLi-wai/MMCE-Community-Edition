package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.storage.LongEnergyBuffer;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * Owns one local buffer. Adapter units, direction and capacities are supplied
 * by the hatch role.
 */
@ApiStatus.Internal
public abstract class BufferedRecipeHatchBlockEntity extends MachineCasingBlockEntity {
  private final LongEnergyBuffer buffer;
  private final String nbtKey;

  protected BufferedRecipeHatchBlockEntity(BlockEntityType<?> type, BlockPos position, BlockState state, long capacity, String nbtKey) {
    this(type, position, state, capacity, nbtKey, true);
  }

  protected BufferedRecipeHatchBlockEntity(BlockEntityType<?> type, BlockPos position, BlockState state, long capacity, String nbtKey, boolean input) {
    super(type, position, state);
    this.nbtKey = nbtKey;
    buffer = new LongEnergyBuffer(
        capacity,
        capacity,
        input,
        () -> level == null ? 0L : level.getGameTime(),
        this::requireMutation,
        this::setChanged);
  }

  public final long receive(long amount, boolean simulate) {
    return live() && !RecipeLifecyclePublisher.isDispatching() ? buffer.receiveEnergyLong(amount, simulate) : 0;
  }

  public final long available() {
    return live() && storageLimit() > 0 ? buffer.extractableNow() : 0;
  }

  public final long space() {
    return live() ? Math.min(Math.max(0, storageLimit() - buffer.stored()), buffer.receivableNow()) : 0;
  }

  protected long storageLimit() {
    return buffer.capacity();
  }

  protected final long extract(long amount) {
    return live() && !RecipeLifecyclePublisher.isDispatching() ? buffer.extractEnergyLong(amount, false) : 0;
  }

  public final long stored() {
    return live() ? buffer.stored() : 0;
  }

  public final long capacity() {
    return buffer.capacity();
  }

  public final LongEnergyBuffer.Lease acquireRecipeBuffer() {
    if (!live()) {
      throw new IllegalStateException("Buffered hatch is not live");
    }
    return buffer.acquire();
  }

  protected final boolean live() {
    if (!(level instanceof ServerLevel server)) {
      return false;
    }
    ServerThreadGuard.require(server, "Buffered input hatch resource access");
    return !isRemoved() && server.hasChunkAt(worldPosition) && server.getBlockEntity(worldPosition) == this;
  }

  protected final void requireMutation() {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Buffered input hatch resource mutation");
      if (RecipeLifecyclePublisher.isDispatching()) {
        throw new IllegalStateException("Buffered input hatch mutation during recipe callbacks");
      }
    }
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    tag.putLong(nbtKey, buffer.stored());
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    requireMutation();
    buffer.loadPreservingExcess(Math.min(tag.getLong(nbtKey), savedAmountLimit()));
    super.loadAdditional(tag, registries);
  }

  protected long savedAmountLimit() {
    return Long.MAX_VALUE;
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = new CompoundTag();
    MachineColorable.save(this, tag, machineColor());
    return tag;
  }
}
