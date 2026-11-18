package com.lw.modularmachine.integration.pneumaticcraft;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.blockentity.MachineCasingBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.recipe.resource.pneumatic.CompressedAirPort;
import com.lw.modularmachine.menu.LegacyHatchMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.List;
import me.desht.pneumaticcraft.api.pressure.PressureTier;
import me.desht.pneumaticcraft.api.upgrade.PNCUpgrade;
import me.desht.pneumaticcraft.common.capabilities.MachineAirHandler;
import me.desht.pneumaticcraft.common.upgrades.ModUpgrades;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class CompressedAirHatchBlockEntity extends MachineCasingBlockEntity implements TickingRecipeHatchBlock.ServerTicking, CompressedAirPort {
  private static final String AIR = "pneumatic_air";
  private static final int BASE_VOLUME = 16_000;
  private static final int SLOT_LIMIT = 16;
  private final GuardedAirHandler handler = new GuardedAirHandler();
  private final SimpleContainer upgrades = new SimpleContainer(4);
  private final boolean input;
  private boolean reserved;

  public CompressedAirHatchBlockEntity(BlockPos position, BlockState state) {
    super(MachineRegistries.COMPRESSED_AIR_ENTITY.get(), position, state);
    input = state.is(MachineRegistries.COMPRESSED_AIR_INPUT.get());
    handler.setConnectableFaces(List.of(Direction.values()));
    upgrades.addListener(ignored -> updateUpgrades());
  }

  void openMenu(Player player) {
    var kind = LegacyHatchMenu.Kind.COMPRESSED_AIR;
    LegacyHatchMenu.open(player, kind, this, index -> new Slot(upgrades, index, kind.slotX(index), kind.slotY()) {
      @Override
      public boolean mayPlace(ItemStack stack) {
        return mutable() && acceptsUpgrade(stack);
      }

      @Override
      public boolean mayPickup(Player viewer) {
        return mutable()
            && handler.getAir() <= handler.getDangerPressure() * BASE_VOLUME
            && handler.getAir() >= -BASE_VOLUME;
      }

      @Override
      public int getMaxStackSize() {
        return SLOT_LIMIT;
      }
    },
        handler::getAir,
        handler::getVolume,
        () -> Float.floatToIntBits(handler.getDangerPressure()),
        () -> Float.floatToIntBits(handler.getCriticalPressure()));
  }

  private static boolean acceptsUpgrade(ItemStack stack) {
    return !stack.isEmpty() && PNCUpgrade.from(stack) == ModUpgrades.VOLUME.get();
  }

  private void updateUpgrades() {
    int volumeUpgrades = 0;
    for (int index = 0; index < upgrades.getContainerSize(); index++) {
      var upgrade = upgrades.getItem(index);
      if (acceptsUpgrade(upgrade)) {
        volumeUpgrades += upgrade.getCount();
      }
    }
    handler.updateVolume(volumeUpgrades);
    setChanged();
  }

  void dropUpgrades() {
    Containers.dropContents(level, worldPosition, upgrades);
  }

  MachineAirHandler handler() {
    return handler;
  }

  @Override
  public void tickHatch() {
    if (mutable()) {
      handler.tick(this);
    }
  }

  @Override
  public int air() {
    return mutable() ? handler.getAir() : 0;
  }

  @Override
  public int volume() {
    return handler.getVolume();
  }

  @Override
  public int outputSpace() {
    return !input && mutable() ? Math.max(0, maxSafeAir() - handler.getAir()) : 0;
  }

  private int maxSafeAir() {
    return (int) (handler.getDangerPressure() * handler.getVolume());
  }

  @Override
  public RecipeResourceParticipant acquireAirChange(int expected, int delta) {
    if (!mutable()
        || expected != handler.getAir()
        || (input ? delta >= 0 || (long) expected + delta < 0 : delta <= 0 || delta > outputSpace())) {
      throw new IllegalStateException("Compressed air stock or role changed before acquisition");
    }
    reserved = true;
    handler.changeReserved(delta);
    return commit -> {
      if (!reserved) {
        throw new IllegalStateException("Compressed air lease already resolved");
      }
      try {
        if (!commit) {
          handler.changeReserved(expected - handler.getAir());
        }
      } finally {
        reserved = false;
      }
    };
  }

  @Override
  public void publishAirChange() {
    setChanged();
  }

  private boolean mutable() {
    if (!(level instanceof ServerLevel server)) {
      return false;
    }
    ServerThreadGuard.require(server, "Compressed air hatch access");
    return !reserved
        && !RecipeLifecyclePublisher.isDispatching()
        && !isRemoved()
        && server.hasChunkAt(worldPosition)
        && server.getBlockEntity(worldPosition) == this;
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (reserved) {
      throw new IllegalStateException("Cannot save a reserved air handler");
    }
    tag.put(AIR, handler.serializeNBT());
    var items = new CompoundTag();
    for (int index = 0; index < upgrades.getContainerSize(); index++) {
      var upgrade = upgrades.getItem(index);
      if (!upgrade.isEmpty()) {
        items.put(Integer.toString(index), upgrade.save(registries));
      }
    }
    tag.put("pressure_upgrades", items);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Compressed air hatch load");
      if (reserved || RecipeLifecyclePublisher.isDispatching()) {
        throw new IllegalStateException("Compressed air load during recipe work");
      }
    }
    super.loadAdditional(tag, registries);
    var items = tag.getCompound("pressure_upgrades");
    for (int index = 0; index < upgrades.getContainerSize(); index++) {
      var upgrade = ItemStack.parseOptional(registries, items.getCompound(Integer.toString(index)));
      upgrades.setItem(
          index,
          acceptsUpgrade(upgrade) ? upgrade.copyWithCount(Math.min(SLOT_LIMIT, upgrade.getCount())) : ItemStack.EMPTY);
    }
    var data = tag.getCompound(AIR).copy();
    handler.restore(data);
  }

  private final class GuardedAirHandler extends MachineAirHandler {
    private GuardedAirHandler() {
      super(PressureTier.TIER_TWO, BASE_VOLUME);
    }

    @Override
    public void addAir(int amount) {
      if (mutable() && amount != 0) {
        int maximum = Math.max(getAir(), (int) (getCriticalPressure() * getVolume()));
        int target = (int) Math.clamp((long) getAir() + amount, -getVolume(), maximum);
        if (target != getAir()) {
          super.addAir(target - getAir());
          setChanged();
        }
      }
    }

    @Override
    public void addPendingAir(int air) {
      if (mutable()) {
        super.addPendingAir(air);
      }
    }

    @Override
    public void setPressure(float pressure) {
      if (mutable() && Float.isFinite(pressure)) {
        float bounded = Math.clamp(pressure, -1, getCriticalPressure());
        addAir(Math.round(bounded * getVolume()) - getAir());
      }
    }

    @Override
    public void setBaseVolume(int ignored) {
    }

    @Override
    public void setVolumeUpgrades(int ignored) {
    }

    private void updateVolume(int amount) {
      super.setVolumeUpgrades(amount);
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
      if (mutable()) {
        restore(tag.copy());
        setChanged();
      }
    }

    private void restore(CompoundTag tag) {
      tag.putInt("Air", Math.clamp(tag.getInt("Air"), -getVolume(), (int) (getCriticalPressure() * getVolume())));
      super.deserializeNBT(tag);
    }

    private void changeReserved(int amount) {
      super.addAir(amount);
    }
  }
}
