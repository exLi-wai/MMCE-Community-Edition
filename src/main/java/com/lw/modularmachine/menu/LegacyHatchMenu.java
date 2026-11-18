package com.lw.modularmachine.menu;

import com.lw.modularmachine.registry.MachineRegistries;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class LegacyHatchMenu extends AbstractContainerMenu {
  public enum Kind {
    LIFE_ESSENCE(1),
    COMPRESSED_AIR(4),
    STARLIGHT(0);

    private final int machineSlots;

    Kind(int machineSlots) {
      this.machineSlots = machineSlots;
    }

    MenuType<LegacyHatchMenu> type() {
      return switch (this) {
        case LIFE_ESSENCE -> MachineRegistries.LIFE_ESSENCE_MENU.get();
        case COMPRESSED_AIR -> MachineRegistries.COMPRESSED_AIR_MENU.get();
        case STARLIGHT -> MachineRegistries.STARLIGHT_MENU.get();
      };
    }

    public LegacyHatchMenu clientMenu(int id, Inventory inventory) {
      var slots = new SimpleContainer(machineSlots);
      return new LegacyHatchMenu(id, inventory, this, null, index -> new Slot(slots, index, slotX(index), slotY()));
    }

    public int slotX(int index) {
      return this == LIFE_ESSENCE ? 81 : 48 + 18 * index;
    }

    public int slotY() {
      return this == LIFE_ESSENCE ? 30 : 29;
    }
  }

  private final Kind kind;
  private final BlockEntity hatch;
  private final int[] values = new int[4];

  private LegacyHatchMenu(int id, Inventory inventory, Kind kind, BlockEntity hatch, IntFunction<Slot> slots, IntSupplier... data) {
    super(kind.type(), id);
    this.kind = kind;
    this.hatch = hatch;
    for (int index = 0; index < kind.machineSlots; index++) {
      addSlot(slots.apply(index));
    }
    PlayerInventorySlots.add(inventory, this::addSlot, 8, 84, 142);
    addDataSlots(new ContainerData() {
      @Override
      public int get(int index) {
        int value = hatch != null && index / 2 < data.length ? data[index / 2].getAsInt() : values[index / 2];
        return (value >>> ((index % 2) * 16)) & 0xFFFF;
      }

      @Override
      public void set(int index, int value) {
        int shift = (index % 2) * 16;
        values[index / 2] = (values[index / 2] & ~(0xFFFF << shift)) | ((value & 0xFFFF) << shift);
      }

      @Override
      public int getCount() {
        return values.length * 2;
      }
    });
  }

  public static void open(Player player, Kind kind, BlockEntity hatch, IntFunction<Slot> slots, IntSupplier... data) {
    if (player.level().isClientSide
        || player.isSpectator()
        || !player.mayBuild()
        || !player.level().mayInteract(player, hatch.getBlockPos())
        || !PlayerInventorySlots.withinReach(player, hatch)) {
      return;
    }
    player.openMenu(
        new SimpleMenuProvider(
            (id, inventory, viewer) -> new LegacyHatchMenu(id, inventory, kind, hatch, slots, data),
            hatch.getBlockState().getBlock().getName()));
  }

  public Kind kind() {
    return kind;
  }

  public int value(int index) {
    return values[index];
  }

  @Override
  public boolean stillValid(Player player) {
    return hatch == null
        || !hatch.isRemoved()
            && player.mayBuild()
            && player.level().mayInteract(player, hatch.getBlockPos())
            && PlayerInventorySlots.withinReach(player, hatch);
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (!stillValid(player) || index < 0 || index >= slots.size()) {
      return ItemStack.EMPTY;
    }
    int start = kind.machineSlots;
    var slot = slots.get(index);
    if (!slot.hasItem() || !slot.mayPickup(player)) {
      return ItemStack.EMPTY;
    }
    var stack = slot.getItem();
    var original = stack.copy();
    if (index < start) {
      if (!moveItemStackTo(stack, start, slots.size(), true)) {
        return ItemStack.EMPTY;
      }
    } else if (!moveItemStackTo(stack, 0, start, false)) {
      int hotbar = start + 27;
      if (!(index < hotbar ? moveItemStackTo(stack, hotbar, slots.size(), false) : moveItemStackTo(
          stack,
          start,
          hotbar,
          false))) {
        return ItemStack.EMPTY;
      }
    }
    if (stack.isEmpty()) {
      slot.setByPlayer(ItemStack.EMPTY);
    } else {
      slot.setChanged();
    }
    slot.onTake(player, stack);
    return original;
  }
}
