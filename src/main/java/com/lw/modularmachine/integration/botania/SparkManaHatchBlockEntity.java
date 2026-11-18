package com.lw.modularmachine.integration.botania;

import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.blockentity.BufferedRecipeHatchBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import vazkii.botania.api.block.Bound;
import vazkii.botania.api.block.WandBindable;
import vazkii.botania.api.mana.ManaCollector;
import vazkii.botania.api.mana.ManaReceiver;
import vazkii.botania.api.mana.spark.ManaSparkAttachable;

public final class SparkManaHatchBlockEntity extends BufferedRecipeHatchBlockEntity implements ManaReceiver, ManaSparkAttachable, WandBindable, Bound, TickingRecipeHatchBlock.ServerTicking {
  static final int CAPACITY = 1_000_000;
  static final String MANA = "spark_mana";
  private static final String BINDING = "collector_position";
  private final boolean input;
  private BlockPos binding = Bound.UNBOUND_POS;
  private int displayedMana;
  private int lastSyncedMana = -1;

  public SparkManaHatchBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.SPARK_MANA_ENTITY.get(), position, state, CAPACITY, MANA,
        state.is(MachineRegistries.SPARK_MANA_INPUT.get()));
    input = state.is(MachineRegistries.SPARK_MANA_INPUT.get());
  }

  @Override
  public void tickHatch() {
    if (!live() || RecipeLifecyclePublisher.isDispatching()) {
      return;
    }
    if (!input && available() > 0) {
      transferToCollector();
    }
    if (level.getGameTime() % 10 == 0 && lastSyncedMana != getCurrentMana()) {
      syncHud();
    }
  }

  private void transferToCollector() {
    if (binding.equals(Bound.UNBOUND_POS) && level.getGameTime() % 20 == 0) {
      for (var direction : Direction.values()) {
        var adjacent = worldPosition.relative(direction);
        if (collectorAt(adjacent) != null) {
          binding = adjacent;
          syncHud();
          break;
        }
      }
    }
    var target = collectorAt(binding);
    if (target == null || target.isFull()) {
      return;
    }
    int before = target.getCurrentMana();
    int offer = (int) Math.min(available(), Math.max(0, target.getMaxMana() - before));
    if (offer > 0) {
      try {
        target.receiveMana(offer);
      } finally {
        extract(Math.min(offer, Math.max(0, target.getCurrentMana() - before)));
      }
    }
  }

  private ManaCollector collectorAt(BlockPos position) {
    if (position.equals(Bound.UNBOUND_POS) || !level.hasChunkAt(position)) {
      return null;
    }
    return ManaReceiver.LOOKUP.find(level, position, null) instanceof ManaCollector target ? target : null;
  }

  @Override
  public boolean bindTo(Player player, ItemStack wand, BlockPos target, Direction side) {
    if (input
        || !live()
        || RecipeLifecyclePublisher.isDispatching()
        || player.level() != level
        || !player.mayInteract(level, worldPosition)
        || !player.mayInteract(level, target)
        || collectorAt(target) == null) {
      return false;
    }
    binding = target.immutable();
    syncHud();
    return true;
  }

  @Override
  public boolean canSelect(Player player, ItemStack wand, Direction side) {
    return !input;
  }

  @Override
  public BlockPos getBinding() {
    return binding;
  }

  @Override
  public boolean canAttachSpark(ItemStack stack) {
    return true;
  }

  @Override
  public int getAvailableSpaceForMana() {
    return input ? (int) space() : 0;
  }

  @Override
  public boolean areIncomingTransfersDone() {
    return !input;
  }

  @Override
  public boolean isFull() {
    return !input || getCurrentMana() >= CAPACITY;
  }

  @Override
  public int getCurrentMana() {
    return level != null && level.isClientSide() ? displayedMana : (int) stored();
  }

  @Override
  public void receiveMana(int amount) {
    if (input) {
      receive(amount, false);
    }
  }

  @Override
  public boolean canReceiveManaFromBursts() {
    return input;
  }

  @Override
  public Level getManaReceiverLevel() {
    return level;
  }

  @Override
  public BlockPos getManaReceiverPos() {
    return worldPosition;
  }

  public boolean showHud() {
    if (level != null && !level.isClientSide() && live() && !RecipeLifecyclePublisher.isDispatching()) {
      syncHud();
    }
    return true;
  }

  private void syncHud() {
    lastSyncedMana = getCurrentMana();
    setChanged();
    var state = getBlockState();
    level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (!binding.equals(Bound.UNBOUND_POS)) {
      tag.putLong(BINDING, binding.asLong());
    }
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    binding = tag.contains(BINDING) ? BlockPos.of(tag.getLong(BINDING)) : Bound.UNBOUND_POS;
    displayedMana = (int) Math.clamp(tag.getLong(MANA), 0, CAPACITY);
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = super.getUpdateTag(registries);
    tag.putLong(MANA, getCurrentMana());
    if (!binding.equals(Bound.UNBOUND_POS)) {
      tag.putLong(BINDING, binding.asLong());
    }
    return tag;
  }

  @Override
  public ClientboundBlockEntityDataPacket getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);
  }
}
