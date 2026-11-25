package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public final class MachinePortMenu extends AbstractContainerMenu {
  public static final int OPEN_VERSION = 1;
  public static final int GRID_COLUMNS = 8;
  public static final int VISIBLE_ROWS = 4;
  public static final int VISIBLE_SLOTS = GRID_COLUMNS * VISIBLE_ROWS;
  private static final int SCROLL_BUTTON_BASE = 1000;
  private final MachinePortBlockEntity port;
  private final IItemHandler items;
  private final int portSlots;
  private final int writableSlots;
  private final boolean clientSide;
  private final boolean legacyLayout;
  private int scrollRow;

  private record OpenData(MachinePortBlockEntity port, int slots, int writableSlots) {
  }

  public MachinePortMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, readOpenData(inventory, data));
  }

  private static OpenData readOpenData(Inventory inventory, FriendlyByteBuf data) {
    if (data.readVarInt() != OPEN_VERSION) {
      throw new IllegalStateException("Unsupported machine port menu version");
    }
    if (inventory.player.level().getBlockEntity(data.readBlockPos()) instanceof MachinePortBlockEntity port) {
      int slots = data.readVarInt();
      int writable = data.readVarInt();
      if (slots <= 0
          || slots > MachineConfig.MAX_RETAINED_ITEM_BUS_SLOTS
          || writable <= 0
          || writable > MachineConfig.MAX_ITEM_BUS_SLOTS
          || writable > slots) {
        throw new IllegalStateException("Invalid machine port menu slot count");
      }
      return new OpenData(port, slots, writable);
    }
    throw new IllegalStateException("Missing machine port while opening menu");
  }

  private MachinePortMenu(int id, Inventory inventory, OpenData data) {
    this(id, inventory, data.port(), clientInventory(data.slots(), data.writableSlots()), data.writableSlots());
  }

  public MachinePortMenu(int id, Inventory inventory, MachinePortBlockEntity port) {
    this(id, inventory, port, port.inventory(), port.writableItemSlots());
  }

  private MachinePortMenu(int id, Inventory inventory, MachinePortBlockEntity port, IItemHandler items, int writableSlots) {
    super(MachineRegistries.PORT_MENU.get(), id);
    this.port = port;
    this.items = items;
    this.writableSlots = writableSlots;
    clientSide = inventory.player.level().isClientSide;
    portSlots = items.getSlots();
    legacyLayout = portSlots == writableSlots && portSlots == port.tier().capacity();
    for (int i = 0; i < portSlots; i++) {
      addSlot(portSlot(i));
    }
    PlayerInventorySlots.add(inventory, this::addSlot, 8, 84, 142);
  }

  private static ItemStackHandler clientInventory(int slots, int writableSlots) {
    return new ItemStackHandler(slots) {
      @Override
      public boolean isItemValid(int slot, ItemStack stack) {
        return slot < writableSlots && super.isItemValid(slot, stack);
      }

      @Override
      public int getSlotLimit(int slot) {
        return slot < writableSlots ? 64 : getStackInSlot(slot).getCount();
      }
    };
  }

  public int rows() {
    return legacyLayout ? (portSlots + 8) / 9 : (portSlots + GRID_COLUMNS - 1) / GRID_COLUMNS;
  }

  public MachinePortBlockEntity port() {
    return port;
  }

  @Override
  public boolean clickMenuButton(Player player, int button) {
    if (!stillValid(player)) {
      return false;
    }
    if (button >= SCROLL_BUTTON_BASE) {
      int requested = button - SCROLL_BUTTON_BASE;
      if (requested > maximumScrollRow()) {
        return false;
      }
      scrollTo(requested);
      return true;
    }
    return GroupInputMenu.open(player, port, button);
  }

  public int portSlots() {
    return portSlots;
  }

  public int writableSlots() {
    return writableSlots;
  }

  public boolean legacyLayout() {
    return legacyLayout;
  }

  public int scrollRow() {
    return scrollRow;
  }

  public int maximumScrollRow() {
    return legacyLayout ? 0 : Math.max(0, rows() - VISIBLE_ROWS);
  }

  public int scrollButton(int row) {
    if (row < 0 || row > maximumScrollRow()) {
      throw new IllegalArgumentException("Invalid machine port scroll row");
    }

    return SCROLL_BUTTON_BASE + row;
  }

  /**
   * Moves only the viewport; each menu and handler slot keeps its physical index.
   */
  public boolean scrollTo(int row) {
    if (row < 0 || row > maximumScrollRow() || row == scrollRow) {
      return false;
    }
    int previousFirst = scrollRow * GRID_COLUMNS;
    scrollRow = row;
    resetQuickCraft();
    if (clientSide) {
      // Slot coordinates are final. Replace at most the old and new visible windows,
      // keeping the full menu order and vanilla sync arrays unchanged.
      replaceWindow(previousFirst);
      replaceWindow(scrollRow * GRID_COLUMNS);
    }

    return true;
  }

  private void replaceWindow(int first) {
    for (int index = first; index < Math.min(portSlots, first + VISIBLE_SLOTS); index++) {
      Slot replacement = portSlot(index);
      replacement.index = index;
      slots.set(index, replacement);
    }
  }

  private Slot portSlot(int index) {
    int[] position;
    if (legacyLayout) {
      position = itemPosition(port.tier().name(), index);
    } else if (visiblePortSlot(index)) {
      position = new int[]{17 + index % GRID_COLUMNS * 18, 8 + (index / GRID_COLUMNS - scrollRow) * 18};
    } else {
      position = new int[]{-10_000, -10_000};
    }
    return new SlotItemHandler(items, index, position[0], position[1]) {
      @Override
      public boolean isActive() {
        return visiblePortSlot(index);
      }

      @Override
      public boolean mayPickup(Player player) {
        return isActive() && super.mayPickup(player);
      }
    };
  }

  private boolean visiblePortSlot(int index) {
    int first = scrollRow * GRID_COLUMNS;

    return legacyLayout || index >= first && index < first + VISIBLE_SLOTS;
  }

  @Override
  public void clicked(int slotId, int button, ClickType clickType, Player player) {
    if (!stillValid(player) || slotId >= 0 && slotId < portSlots && !visiblePortSlot(slotId)) {
      resetQuickCraft();
      return;
    }
    super.clicked(slotId, button, clickType, player);
  }

  @Override
  public boolean canDragTo(Slot slot) {
    return slot.isActive() && super.canDragTo(slot);
  }

  @Override
  public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
    return slot.isActive() && super.canTakeItemForPickAll(stack, slot);
  }

  public String tierName() {
    return port.tier().name();
  }

  private static int[] itemPosition(String tier, int index) {
    return switch (tier) {
      case "tiny" -> new int[]{81, 30};
      case "small" -> new int[]{70 + index % 2 * 18, 18 + index / 2 * 18};
      case "normal" -> new int[]{61 + index % 3 * 18, 18 + index / 3 * 18};
      case "reinforced" -> new int[]{61 + index % 3 * 18, 13 + index / 3 * 18};
      case "big" -> new int[]{52 + index % 4 * 18, 18 + index / 4 * 18};
      case "huge" -> new int[]{53 + index % 4 * 18, 8 + index / 4 * 18};
      case "ludicrous" -> new int[]{17 + index % 8 * 18, 8 + index / 8 * 18};
      default -> new int[]{8 + index % 9 * 18, 18 + index / 9 * 18};
    };
  }

  @Override
  public boolean stillValid(Player player) {
    return !port.isRemoved() && port.schemaSupported() && PlayerInventorySlots.withinReach(player, port);
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (!stillValid(player) || index < 0 || index >= slots.size()) {
      return ItemStack.EMPTY;
    }
    Slot slot = slots.get(index);
    if (!slot.isActive() || !slot.hasItem()) {
      return ItemStack.EMPTY;
    }
    ItemStack stack = slot.getItem();
    ItemStack original = stack.copy();
    if (index < portSlots) {
      if (!moveItemStackTo(stack, portSlots, slots.size(), true)) {
        return ItemStack.EMPTY;
      }
    } else {
      if (!moveItemStackTo(stack, 0, portSlots, false)) {
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
