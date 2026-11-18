package com.lw.modularmachine.integration.neovitae;

import com.breakinblocks.neovitae.api.soul.AnimaTicket;
import com.breakinblocks.neovitae.api.soul.IAnima;
import com.breakinblocks.neovitae.common.item.BloodOrbItem;
import com.breakinblocks.neovitae.util.helper.AnimaHelper;
import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.blockentity.BufferedRecipeHatchBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.menu.LegacyHatchMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.storage.LongEnergyBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class LifeEssenceHatchBlockEntity extends BufferedRecipeHatchBlockEntity implements TickingRecipeHatchBlock.ServerTicking {
  private static final String CARRIED_ESSENCE = "mmcece:carried_life_essence";
  private final boolean input;
  private final SimpleContainer inventory = new SimpleContainer(1);

  public LifeEssenceHatchBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.LIFE_ESSENCE_ENTITY.get(), position, state, Integer.MAX_VALUE, "life_essence",
        state.is(MachineRegistries.LIFE_ESSENCE_INPUT.get()));
    input = state.is(MachineRegistries.LIFE_ESSENCE_INPUT.get());
    inventory.addListener(ignored -> setChanged());
  }

  @Override
  protected long savedAmountLimit() {
    return capacity();
  }

  @Override
  protected long storageLimit() {
    var orb = inventory.getItem(0);
    int capacity = orbCapacity(orb);
    return carriedEssence(orb) > 0 ? Math.min(capacity, stored()) : capacity;
  }

  private int orbCapacity(ItemStack orb) {
    if (!(orb.getItem() instanceof BloodOrbItem item) || item.getBinding(orb).isEmpty()) {
      return 0;
    }
    int capacity = item.getAnimaCapacity(orb);
    return input ? capacity / 10 : capacity;
  }

  void openMenu(Player player) {
    var kind = LegacyHatchMenu.Kind.LIFE_ESSENCE;
    LegacyHatchMenu.open(player, kind, this, index -> new Slot(inventory, index, kind.slotX(index), kind.slotY()) {
      @Override
      public boolean mayPlace(ItemStack stack) {
        return live() && stored() == 0 && orbCapacity(stack) > 0;
      }

      @Override
      public boolean mayPickup(Player viewer) {
        return returnCachedEssence();
      }

      @Override
      public int getMaxStackSize() {
        return 1;
      }
    }, () -> (int) stored(), () -> (int) storageLimit());
  }

  ItemStack removeForDrop() {
    requireMutation();
    returnCachedEssence();
    int remaining = (int) stored();
    var orb = inventory.removeItem(0, 1);
    if (remaining > 0 && !orb.isEmpty()) {
      setCarriedEssence(orb, carriedEssence(orb) + remaining);
    }
    return orb;
  }

  private static long carriedEssence(ItemStack orb) {
    var data = orb.get(DataComponents.CUSTOM_DATA);
    if (data == null || !data.contains(CARRIED_ESSENCE)) {
      return 0;
    }
    var tag = data.copyTag();
    return tag.contains(CARRIED_ESSENCE, Tag.TAG_LONG) ? Math.max(0, tag.getLong(CARRIED_ESSENCE)) : 0;
  }

  private static void setCarriedEssence(ItemStack orb, long amount) {
    var tag = orb.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    if (amount == 0) {
      tag.remove(CARRIED_ESSENCE);
    } else {
      tag.putLong(CARRIED_ESSENCE, amount);
    }
    if (tag.isEmpty()) {
      orb.remove(DataComponents.CUSTOM_DATA);
    } else {
      orb.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }
  }

  private void restoreCarriedEssence(ItemStack orb, long carried) {
    long restore = Math.min(carried, Math.max(0, orbCapacity(orb) - stored()));
    if (restore == 0) {
      return;
    }
    try (var lease = acquireRecipeBuffer()) {
      if (input ? lease.receiveExternalExact(restore) : lease.insertExact(restore)) {
        setCarriedEssence(orb, carried - restore);
        lease.resolve(true);
        setChanged();
      }
    }
  }

  private boolean returnCachedEssence() {
    requireMutation();
    if (!live()) {
      return false;
    }
    int balance = (int) stored();
    if (balance == 0) {
      return true;
    }
    var orb = inventory.getItem(0);
    if (!(orb.getItem() instanceof BloodOrbItem item)) {
      return false;
    }
    var binding = item.getBinding(orb);
    var anima = binding.isEmpty() ? null : AnimaHelper.getAnima(binding);
    if (anima == null) {
      return false;
    }
    int amount = (int) Math.min(balance, available());
    return transferEssence(anima, amount, item.getAnimaCapacity(orb), false) == balance;
  }

  @Override
  public void tickHatch() {
    var orb = inventory.getItem(0);
    if (!live()
        || RecipeLifecyclePublisher.isDispatching()
        || !(orb.getItem() instanceof BloodOrbItem item)
        || Math.floorMod(level.getGameTime() + worldPosition.asLong(), 10) != 0) {
      return;
    }
    var binding = item.getBinding(orb);
    long carried = carriedEssence(orb);
    if (!binding.isEmpty() && carried > 0) {
      restoreCarriedEssence(orb, carried);
    }
    int amount = (int) Math.min(Math.max(0, item.getFillRate(orb)), input ? space() : available());
    if (binding.isEmpty() || amount == 0 || input && carried > 0) {
      return;
    }
    var anima = AnimaHelper.getAnima(binding);
    if (anima == null) {
      return;
    }
    transferEssence(anima, amount, item.getAnimaCapacity(orb), input);
  }

  private int transferEssence(IAnima anima, int amount, int networkCapacity, boolean drawFromNetwork) {
    if (amount == 0) {
      return 0;
    }
    try (var lease = acquireRecipeBuffer()) {
      int moved = drawFromNetwork ? pullFromNetwork(
          anima,
          amount,
          lease) : pushToNetwork(anima, amount, networkCapacity, input, lease);
      lease.resolve(true);
      if (moved > 0) {
        setChanged();
      }
      return moved;
    }
  }

  static int pullFromNetwork(IAnima anima, int amount, LongEnergyBuffer.Lease lease) {
    int request = Math.min(amount, anima.getCurrentEV());
    if (request <= 0) {
      return 0;
    }
    int moved = anima.syphon(AnimaTicket.create(request));
    if (moved < 0 || moved > request || (moved > 0 && !lease.receiveExternalExact(moved))) {
      throw new IllegalStateException("Neo Vitae reported an invalid life essence withdrawal");
    }
    return moved;
  }

  static int pushToNetwork(IAnima anima, int amount, int networkCapacity, boolean inputHatch, LongEnergyBuffer.Lease lease) {
    int moved = anima.add(AnimaTicket.create(amount), networkCapacity);
    if (moved < 0
        || moved > amount
        || (moved > 0 && !(inputHatch ? lease.extractExact(moved) : lease.extractExternalExact(moved)))) {
      throw new IllegalStateException("Neo Vitae reported an invalid life essence deposit");
    }
    return moved;
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    var orb = inventory.getItem(0);
    if (!orb.isEmpty()) {
      tag.put("blood_orb", orb.save(registries));
    }
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    inventory.setItem(0, ItemStack.parseOptional(registries, tag.getCompound("blood_orb")));
  }
}
