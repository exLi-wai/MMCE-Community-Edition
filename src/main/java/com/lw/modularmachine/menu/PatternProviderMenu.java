package com.lw.modularmachine.menu;

import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.network.PatternProviderPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

/** Internal authoritative physical slots and bounded per-pattern cache view. */
public final class PatternProviderMenu extends AbstractContainerMenu {
  private static final int PROVIDER_SLOTS = 38;
  private final PatternProviderBlockEntity provider;
  private final Player viewer;
  private final UUID session = UUID.randomUUID();
  private long sequence;
  private int selectedSlot;
  private int firstRow;
  private PatternProviderPayloads.Status status;
  private PatternProviderPayloads.Status lastSent;

  public PatternProviderMenu(int id, Inventory inventory, FriendlyByteBuf data) {
    this(id, inventory, requireProvider(inventory, data));
  }

  public static PatternProviderMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf data) {
    return new PatternProviderMenu(id, inventory, data);
  }

  private static PatternProviderBlockEntity requireProvider(Inventory inventory, FriendlyByteBuf data) {
    if (inventory.player.level().getBlockEntity(data.readBlockPos()) instanceof PatternProviderBlockEntity provider) {
      return provider;
    }
    throw new IllegalStateException("Missing pattern provider while opening menu");
  }

  public PatternProviderMenu(int id, Inventory inventory, PatternProviderBlockEntity provider) {
    super(MachineRegistries.PATTERN_PROVIDER_MENU.get(), id);
    this.provider = provider;
    viewer = inventory.player;
    for (int slot = 0; slot < 36; slot++) {
      addSlot(physicalSlot(provider.patternInventory(), slot, 8 + slot % 9 * 18, 28 + slot / 9 * 18));
    }
    addSlot(physicalSlot(provider.independentItems(), 0, 181, 172));
    addSlot(physicalSlot(provider.independentItems(), 1, 199, 172));
    for (int row = 0; row < 3; row++) {
      for (int col = 0; col < 9; col++) {
        addSlot(new Slot(inventory, 9 + row * 9 + col, 8 + col * 18, 114 + row * 18));
      }
    }
    for (int col = 0; col < 9; col++) {
      addSlot(new Slot(inventory, col, 8 + col * 18, 172));
    }
  }

  private Slot physicalSlot(IItemHandler handler, int slot, int x, int y) {
    return new SlotItemHandler(handler, slot, x, y) {
      @Override
      public boolean mayPlace(ItemStack stack) {
        return (viewer.level().isClientSide || canEdit(viewer)) && super.mayPlace(stack);
      }

      @Override
      public boolean mayPickup(Player player) {
        return (player.level().isClientSide || canEdit(player)) && super.mayPickup(player);
      }

      @Override
      public void setChanged() {
        super.setChanged();
        if (!viewer.level().isClientSide && provider.canMutate()) {
          provider.publishResourceChange();
        }
      }
    };
  }

  public PatternProviderPayloads.Status status() {
    return status;
  }

  public PatternProviderBlockEntity provider() {
    return provider;
  }

  public void acceptStatus(PatternProviderPayloads.Status value) {
    if (value.containerId() == containerId
        && value.position().equals(provider.getBlockPos())
        && (status == null || status.session().equals(value.session()) && value.sequence() > status.sequence())) {
      status = value;
    }
  }

  public void request(PatternProviderPayloads.Operation operation, int slot, int row) {
    if (status != null
        && slot >= 0
        && slot < 36
        && row >= 0
        && (status.editable()
            || operation == PatternProviderPayloads.Operation.SELECT_SLOT
            || operation == PatternProviderPayloads.Operation.SCROLL)) {
      PacketDistributor.sendToServer(
          new PatternProviderPayloads.Action(
              containerId,
              provider.getBlockPos(),
              status.session(),
              status.snapshot().revision(),
              slot,
              status.snapshot().generations().get(slot),
              operation,
              row));
    }
  }

  public void apply(ServerPlayer player, PatternProviderPayloads.Action action) {
    if (player.containerMenu == this
        && action.containerId() == containerId
        && action.position().equals(provider.getBlockPos())
        && action.session().equals(session)
        && stillValid(player)
        && !provider.state().inTransaction()
        && action.revision() == provider.resourceRevision()
        && action.generation() == provider.state().generation(action.slot())) {
      switch (action.operation()) {
        case SELECT_SLOT -> {
          selectedSlot = action.slot();
          firstRow = 0;
        }
        case SCROLL -> {
          if (selectedSlot == action.slot()) {
            firstRow = action.firstRow();
          }
        }
        case RETURN_ALL -> {
          if (canEdit(player)) {
            provider.requestReturnAll();
          }
        }
        case FLUID_CONTAINER -> {
          if (canEdit(player)) {
            provider.interactIndependentFluid(player);
          }
        }
      }
    }
    lastSent = null;
    broadcastChanges();
  }

  private boolean canEdit(Player player) {
    return stillValid(player)
        && !player.isSpectator()
        && player.mayBuild()
        && player.level().mayInteract(player, provider.getBlockPos())
        && provider.canMutate();
  }

  @Override
  public boolean stillValid(Player player) {
    return !provider.isRemoved()
        && provider.getLevel() == player.level()
        && player.level().getBlockEntity(provider.getBlockPos()) == provider
        && player.distanceToSqr(provider.getBlockPos().getCenter()) <= 64;
  }

  @Override
  public void clicked(int slotId, int button, ClickType type, Player player) {
    if (player.level().isClientSide || canEdit(player)) {
      super.clicked(slotId, button, type, player);
    }
  }

  @Override
  public void broadcastChanges() {
    if (provider.state().inTransaction()
        || provider.independentItems().isInTransaction()
        || provider.independentFluid().isInTransaction()) {
      return;
    }
    super.broadcastChanges();
    if (viewer instanceof ServerPlayer player && player.containerMenu == this && stillValid(player)) {
      boolean editable = canEdit(player);
      if (lastSent != null
          && lastSent.snapshot().revision() == provider.resourceRevision()
          && lastSent.editable() == editable
          && lastSent.snapshot().selectedSlot() == selectedSlot
          && lastSent.snapshot().firstRow() == firstRow) {
        return;
      }
      var snapshot = capture();
      if (lastSent == null || !lastSent.snapshot().equals(snapshot) || lastSent.editable() != editable) {
        lastSent = new PatternProviderPayloads.Status(
            containerId,
            provider.getBlockPos(),
            session,
            ++sequence,
            editable,
            snapshot);
        PacketDistributor.sendToPlayer(player, lastSent);
      }
    }
  }

  private PatternProviderPayloads.Snapshot capture() {
    var generations = new ArrayList<Long>(36);
    for (int slot = 0; slot < 36; slot++) {
      generations.add(provider.state().generation(slot));
    }
    var containers = provider.state().containers().stream().filter(value -> value.address().slot() == selectedSlot)
        .toList();
    long count = containers.stream().mapToLong(value -> value.resources().physical().size()).sum();
    int totalRows = (int) Math.min(Integer.MAX_VALUE, (count + 2) / 3);
    firstRow = Math.min(firstRow, Math.max(0, totalRows - 7));
    long skip = (long) firstRow * 3;
    var entries = new ArrayList<PatternProviderPayloads.Entry>(PatternProviderPayloads.PAGE_SIZE);
    outer : for (var container : containers) {
      for (var entry : container.resources().physical().entrySet()) {
        if (skip > 0) {
          skip--;
          continue;
        }
        entries.add(
            new PatternProviderPayloads.Entry(
                entry.getKey().resource(viewer.level().registryAccess()),
                entry.getValue(),
                container.address().generation(),
                container.retired()));
        if (entries.size() == PatternProviderPayloads.PAGE_SIZE) {
          break outer;
        }
      }
    }
    var fluid = provider.independentFluid().getFluid();

    return new PatternProviderPayloads.Snapshot(
        provider.resourceRevision(),
        generations,
        selectedSlot,
        firstRow,
        totalRows,
        entries,
        fluid.isEmpty() ? null : new NetworkPortResource.Fluid(fluid),
        fluid.getAmount(),
        provider.independentFluid().getCapacity());
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (!canEdit(player) || index < 0 || index >= slots.size() || !slots.get(index).hasItem()) {
      return ItemStack.EMPTY;
    }
    var slot = slots.get(index);
    var original = slot.getItem().copy();
    // Work on a copy: handlers may publish changes and must not see mutable cached
    // stacks.
    var remaining = original.copy();
    if (index < PROVIDER_SLOTS ? !moveItemStackTo(remaining, PROVIDER_SLOTS, slots.size(), true) : !moveItemStackTo(
        remaining,
        0,
        PROVIDER_SLOTS,
        false)) {
      return ItemStack.EMPTY;
    }
    slot.setByPlayer(remaining);
    slot.onTake(player, remaining);

    return original;
  }
}
