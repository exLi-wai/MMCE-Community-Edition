package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.machine.network.pattern.PatternDefinition;
import com.lw.modularmachine.machine.network.pattern.PatternNetworkPlatform;
import com.lw.modularmachine.machine.network.pattern.PatternProviderState;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.menu.PatternProviderMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.storage.TransactionalFluidTank;
import com.lw.modularmachine.storage.TransactionalItemStackHandler;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal physical pattern generations, independent storage and optional AE
 * endpoint.
 */
@ApiStatus.Internal
public final class PatternProviderBlockEntity extends BlockEntity implements MenuProvider, MachineColorable {
  public static final String DATA_TAG = "pattern_provider";
  public static final String CONNECTION_TAG = "pattern_connection";
  private PatternProviderState state = new PatternProviderState(this::checkThread, this::beforeWrite);
  private final TransactionalItemStackHandler independentItems = new TransactionalItemStackHandler(
      2,
      this::checkThread,
      this::beforeWrite,
      this::publishResourceChange);
  private final TransactionalFluidTank independentFluid = new TransactionalFluidTank(
      Integer.MAX_VALUE,
      this::checkThread,
      this::beforeWrite,
      this::publishResourceChange);
  private final PatternNetworkPlatform.Connection connection = PatternNetworkPlatform.create(this);
  private final NonNullList<ItemStack> clientPatterns = NonNullList.withSize(36, ItemStack.EMPTY);
  private final IItemHandlerModifiable patterns = new PatternInventory();
  private int machineColor = MachineConfig.defaultCasingColor();
  private boolean machineColorAssigned;
  private boolean loading;
  private Tag opaque;
  private long resourceRevision;

  public PatternProviderBlockEntity(BlockPos position, BlockState blockState) {
    super(MachineRegistries.PATTERN_PROVIDER_ENTITY.get(), position, blockState);
  }

  public PatternProviderState state() {
    return state;
  }
  public UUID providerId() {
    return state.providerId();
  }
  public UUID instanceId() {
    return state.instanceId();
  }
  public IItemHandlerModifiable patternInventory() {
    return patterns;
  }
  public TransactionalItemStackHandler independentItems() {
    return independentItems;
  }
  public TransactionalFluidTank independentFluid() {
    return independentFluid;
  }
  public PatternNetworkPlatform.Connection connection() {
    return connection;
  }
  public long resourceRevision() {
    return resourceRevision;
  }
  public boolean schemaSupported() {
    return opaque == null && state.valid();
  }

  private void checkThread() {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Pattern provider storage");
    }
  }

  private void beforeWrite() {
    checkThread();
    if (loading || level == null || level.isClientSide) {
      return;
    }
    if (opaque != null
        || isRemoved()
        || !level.hasChunkAt(worldPosition)
        || level.getBlockEntity(worldPosition) != this
        || UpgradeLifecyclePublisher.isDispatchingAny()
        || RecipeLifecyclePublisher.isDispatching()
        || SmartInterfaceEventPublisher.isDispatching()
        || resourceRevision == Long.MAX_VALUE) {
      throw new IllegalStateException("Pattern provider is unavailable for mutation");
    }
  }

  public boolean canMutate() {
    checkThread();
    return level instanceof ServerLevel
        && !isRemoved()
        && level.hasChunkAt(worldPosition)
        && level.getBlockEntity(worldPosition) == this
        && !state.inTransaction()
        && !independentItems.isInTransaction()
        && !independentFluid.isInTransaction()
        && schemaSupported()
        && resourceRevision < Long.MAX_VALUE
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && !RecipeLifecyclePublisher.isDispatching()
        && !SmartInterfaceEventPublisher.isDispatching();
  }

  /**
   * Publish only after all storage leases participating in an operation resolve.
   */
  public void publishResourceChange() {
    checkThread();
    if (!loading) {
      if (resourceRevision < Long.MAX_VALUE) {
        resourceRevision++;
      }
      setChanged();
      if (level instanceof ServerLevel) {
        PatternProductionService.resourcesChanged(this);
      }
    }
  }

  public void requestReturnAll() {
    if (!canMutate()) {
      return;
    }
    var containers = state.containers();
    try (var lease = state.acquire(state.instanceId(), state.revision())) {
      for (var container : containers) {
        lease.requestReturn(container.address());
      }
      lease.resolve(true);
    }
    publishResourceChange();
  }

  public boolean interactIndependentFluid(ServerPlayer player) {
    if (!canMutate()
        || !(player.containerMenu instanceof PatternProviderMenu menu)
        || menu.provider() != this
        || !menu.stillValid(player)
        || player.isSpectator()
        || !player.mayBuild()
        || !level.mayInteract(player, worldPosition)) {
      return false;
    }
    var cursor = menu.getCarried();
    if (cursor.isEmpty() || cursor.getCount() != 1) {
      return false;
    }
    var staged = new FluidTank(independentFluid.getCapacity());
    staged.setFluid(independentFluid.getFluid().copy());
    var result = FluidUtil.tryEmptyContainer(cursor.copy(), staged, Integer.MAX_VALUE, null, true);
    if (!result.isSuccess()) {
      result = FluidUtil.tryFillContainer(cursor.copy(), staged, Integer.MAX_VALUE, null, true);
    }
    if (!result.isSuccess()) {
      return false;
    }
    try (var lease = independentFluid.acquire()) {
      lease.replacePrepared(staged.getFluid().copy());
      menu.setCarried(result.getResult().copy());
      lease.resolve(true);
    }
    publishResourceChange();
    menu.broadcastChanges();
    return true;
  }

  @Override
  public int machineColor() {
    return machineColor;
  }
  @Override
  public boolean machineColorAssigned() {
    return machineColorAssigned;
  }
  @Override
  public void setMachineColor(int color) {
    color &= 0xFFFFFF;
    boolean changed = !machineColorAssigned || machineColor != color;
    machineColorAssigned = true;
    machineColor = color;
    if (changed) {
      MachineColorable.update(this);
    }
  }

  @Override
  public void onLoad() {
    super.onLoad();
    connection.onLoad();
    notifyStructure();
  }
  @Override
  public void setRemoved() {
    connection.destroy();
    notifyStructure();
    super.setRemoved();
  }
  @Override
  public void onChunkUnloaded() {
    connection.onChunkUnloaded();
    notifyStructure();
    super.onChunkUnloaded();
  }
  private void notifyStructure() {
    if (level instanceof ServerLevel server) {
      StructureService.blockChanged(server, worldPosition);
      PatternProductionService.resourcesChanged(this);
    }
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    MachineColorable.save(this, tag, machineColor);
    if (opaque != null) {
      tag.put(DATA_TAG, opaque.copy());
    } else {
      var data = new CompoundTag();
      data.putInt("schema", 1);
      data.putLong("revision", resourceRevision);
      data.put("state", state.save());
      data.put("items", independentItems.serializeNBT(registries));
      data.put("fluid", independentFluid.writeToNBT(registries, new CompoundTag()));
      tag.put(DATA_TAG, data);
    }
    var network = new CompoundTag();
    connection.save(network, registries);
    tag.put(CONNECTION_TAG, network);
  }

  public static boolean completeData(CompoundTag root, HolderLookup.Provider registries) {
    if (!root.contains(DATA_TAG, Tag.TAG_COMPOUND)) {
      return false;
    }
    var data = root.getCompound(DATA_TAG);
    if (!data.contains("schema", Tag.TAG_INT)
        || data.getInt("schema") != 1
        || !data.contains("revision", Tag.TAG_LONG)
        || data.getLong("revision") < 0
        || !data.contains("state", Tag.TAG_COMPOUND)
        || !data.contains("items", Tag.TAG_COMPOUND)
        || !data.contains("fluid", Tag.TAG_COMPOUND)) {
      return false;
    }
    var items = data.getCompound("items");
    if (!items.contains("Size", Tag.TAG_INT) || items.getInt("Size") != 2 || !items.contains("Items", Tag.TAG_LIST)) {
      return false;
    }
    try {
      var inventory = new TransactionalItemStackHandler(2, () -> {
      }, () -> {
      }, () -> {
      });
      inventory.deserializeNBT(registries, items);
      if (!inventory.serializeNBT(registries).equals(items)) {
        return false;
      }
      for (int slot = 0; slot < 2; slot++) {
        var stack = inventory.getStackInSlot(slot);
        if (stack.getCount() > Math.min(64, stack.getMaxStackSize())) {
          return false;
        }
      }
      var fluid = new FluidTank(Integer.MAX_VALUE);
      fluid.readFromNBT(registries, data.getCompound("fluid"));
      return fluid.writeToNBT(registries, new CompoundTag()).equals(data.getCompound("fluid"))
          && PatternProviderState.load(data.get("state"), registries, () -> {
          }, () -> {
          }).valid();
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    checkThread();
    if (level instanceof ServerLevel
        && (UpgradeLifecyclePublisher.isDispatchingAny()
            || RecipeLifecyclePublisher.isDispatching()
            || SmartInterfaceEventPublisher.isDispatching())) {
      throw new IllegalStateException("Cannot reload provider during a read-only callback");
    }
    super.loadAdditional(tag, registries);
    machineColorAssigned = tag.contains(COLOR_TAG);
    machineColor = MachineColorable.load(tag);
    if (level != null && level.isClientSide) {
      return;
    }
    if (state.inTransaction() || independentItems.isInTransaction() || independentFluid.isInTransaction()) {
      throw new IllegalStateException("Cannot reload provider in a resource transaction");
    }
    loading = true;
    try {
      opaque = null;
      resourceRevision = 0;
      state = new PatternProviderState(this::checkThread, this::beforeWrite);
      for (int slot = 0; slot < 2; slot++) {
        independentItems.setStackInSlot(slot, ItemStack.EMPTY);
      }
      independentFluid.setFluid(FluidStack.EMPTY);
      if (tag.contains(DATA_TAG)) {
        if (!completeData(tag, registries)) {
          opaque = tag.get(DATA_TAG).copy();
        } else {
          var data = tag.getCompound(DATA_TAG);
          state = PatternProviderState.load(data.get("state"), registries, this::checkThread, this::beforeWrite);
          resourceRevision = data.getLong("revision");
          independentItems.deserializeNBT(registries, data.getCompound("items"));
          independentFluid.readFromNBT(registries, data.getCompound("fluid"));
        }
      }
      connection.load(tag.getCompound(CONNECTION_TAG), registries);
    } finally {
      loading = false;
    }
    notifyStructure();
  }

  /**
   * Resources survive a normal move, but network/CPU/run authority never travels.
   */
  public CompoundTag portableData(HolderLookup.Provider registries) {
    var tag = saveWithoutMetadata(registries);
    tag.put(CONNECTION_TAG, new CompoundTag());
    if (schemaSupported()) {
      tag.getCompound(DATA_TAG).put("state", state.portableSave());
    }
    return tag;
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = new CompoundTag();
    MachineColorable.save(this, tag, machineColor);
    return tag;
  }
  @Override
  public Component getDisplayName() {
    return getBlockState().getBlock().getName();
  }
  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    return schemaSupported() ? new PatternProviderMenu(id, inventory, this) : null;
  }

  private final class PatternInventory implements IItemHandlerModifiable {
    @Override
    public int getSlots() {
      return 36;
    }
    private void requireSlot(int slot) {
      if (slot < 0 || slot >= 36) {
        throw new IndexOutOfBoundsException(slot);
      }
    }
    @Override
    public ItemStack getStackInSlot(int slot) {
      requireSlot(slot);
      if (level == null || level.isClientSide) {
        return clientPatterns.get(slot).copy();
      }
      return state.current(slot).map(view -> view.pattern().stack(level.registryAccess())).orElse(ItemStack.EMPTY);
    }
    @Override
    public int getSlotLimit(int slot) {
      requireSlot(slot);
      return 1;
    }
    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
      requireSlot(slot);
      return !stack.isEmpty()
          && (level == null || level.isClientSide || connection.pattern(stack.copyWithCount(1)).isPresent());
    }
    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
      requireSlot(slot);
      if (level == null || level.isClientSide) {
        clientPatterns.set(slot, stack.copy());
        return;
      }
      if (!canMutate() || stack.getCount() > 1 || !stack.isEmpty() && !isItemValid(slot, stack)) {
        throw new IllegalStateException("Invalid pattern slot edit");
      }
      var replacement = stack.isEmpty() ? null : PatternDefinition.capture(stack, level.registryAccess());
      long generation = state.generation(slot);
      try (var lease = state.acquire(state.instanceId(), state.revision())) {
        if (!lease.setPattern(slot, generation, replacement)) {
          throw new IllegalStateException("Stale pattern slot");
        }
        lease.resolve(true);
      }
      publishResourceChange();
      connection.patternsChanged();
    }
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
      requireSlot(slot);
      if (stack.isEmpty() || !getStackInSlot(slot).isEmpty() || !isItemValid(slot, stack)) {
        return stack;
      }
      if (!simulate) {
        setStackInSlot(slot, stack.copyWithCount(1));
      }
      return stack.getCount() == 1 ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - 1);
    }
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
      var stack = getStackInSlot(slot);
      if (amount <= 0 || stack.isEmpty()) {
        return ItemStack.EMPTY;
      }
      if (!simulate) {
        setStackInSlot(slot, ItemStack.EMPTY);
      }
      return stack;
    }
  }
}
