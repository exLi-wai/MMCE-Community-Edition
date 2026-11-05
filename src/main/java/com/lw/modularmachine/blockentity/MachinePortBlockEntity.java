package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.InputGroup;
import com.lw.modularmachine.machine.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.menu.MachinePortMenu;
import com.lw.modularmachine.menu.MachineResourceMenu;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.storage.LongEnergyBuffer;
import com.lw.modularmachine.storage.TransactionalFluidTank;
import com.lw.modularmachine.storage.TransactionalItemStackHandler;
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
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MachinePortBlockEntity extends BlockEntity implements MenuProvider, MachineColorable {
  private final PortKind kind;
  private final HatchTier tier;
  private final TransactionalItemStackHandler items;
  private final TransactionalFluidTank tank;
  private final LongEnergyBuffer energy;
  private final IItemHandler itemCapability;
  private final IFluidHandler fluidCapability;
  private final IEnergyStorage energyCapability;
  private long resourceRevision;
  private int inputGroup = InputGroup.SHARED;
  private long groupRevision;
  private int preferredInputGroup = 1;
  private CompoundTag unsupportedData;
  private int machineColor = 0xFFFFFF;
  private boolean machineColorAssigned;

  public MachinePortBlockEntity(BlockPos pos, BlockState state) {
    super(MachineRegistries.PORT_ENTITY.get(), pos, state);
    MachinePortBlock block = (MachinePortBlock) state.getBlock();
    kind = block.kind();
    tier = block.tier();
    items = new TransactionalItemStackHandler(
        kind.resource() == PortKind.Resource.ITEM ? (int) tier.capacity() : 0,
        this::assertMutationThread,
        this::beforeItemWrite,
        this::changed);
    tank = new TransactionalFluidTank(
        kind.resource() == PortKind.Resource.FLUID ? (int) MachineConfig.fluidHatchCapacity(tier) : 0,
        this::assertMutationThread,
        this::beforeResourceWrite,
        this::changed);
    energy = new LongEnergyBuffer(
        kind.resource() == PortKind.Resource.ENERGY ? MachineConfig.energyHatchCapacity(tier) : 1,
        kind.resource() == PortKind.Resource.ENERGY ? MachineConfig.energyHatchTransfer(tier) : 1,
        kind.isInput(),
        () -> level == null ? 0 : level.getGameTime(),
        this::beforeResourceWrite,
        this::changed);
    energyCapability = new IEnergyStorage() {
      @Override
      public int receiveEnergy(int amount, boolean simulate) {
        return schemaSupported() ? energy.receiveEnergy(amount, simulate) : 0;
      }

      @Override
      public int extractEnergy(int amount, boolean simulate) {
        return schemaSupported() ? energy.extractEnergy(amount, simulate) : 0;
      }

      @Override
      public int getEnergyStored() {
        return energy.getEnergyStored();
      }

      @Override
      public int getMaxEnergyStored() {
        return energy.getMaxEnergyStored();
      }

      @Override
      public boolean canExtract() {
        return schemaSupported() && energy.canExtract();
      }

      @Override
      public boolean canReceive() {
        return schemaSupported() && energy.canReceive();
      }
    };
    itemCapability = new IItemHandler() {
      @Override
      public int getSlots() {
        return items.getSlots();
      }

      @Override
      public ItemStack getStackInSlot(int slot) {
        return items.getStackInSlot(slot);
      }

      @Override
      public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return unsupportedData == null && kind.isInput() ? items.insertItem(slot, stack, simulate) : stack;
      }

      @Override
      public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return unsupportedData == null && !kind.isInput() ? items.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
      }

      @Override
      public int getSlotLimit(int slot) {
        return items.getSlotLimit(slot);
      }

      @Override
      public boolean isItemValid(int slot, ItemStack stack) {
        return kind.isInput() && items.isItemValid(slot, stack);
      }
    };
    fluidCapability = new IFluidHandler() {
      @Override
      public int getTanks() {
        return tank.getTanks();
      }

      @Override
      public FluidStack getFluidInTank(int index) {
        return tank.getFluidInTank(index);
      }

      @Override
      public int getTankCapacity(int index) {
        return tank.getTankCapacity(index);
      }

      @Override
      public boolean isFluidValid(int index, FluidStack stack) {
        return kind.isInput() && tank.isFluidValid(index, stack);
      }

      @Override
      public int fill(FluidStack stack, FluidAction action) {
        return unsupportedData == null && kind.isInput() ? tank.fill(stack, action) : 0;
      }

      @Override
      public FluidStack drain(FluidStack stack, FluidAction action) {
        return unsupportedData == null && !kind.isInput() ? tank.drain(stack, action) : FluidStack.EMPTY;
      }

      @Override
      public FluidStack drain(int amount, FluidAction action) {
        return unsupportedData == null && !kind.isInput() ? tank.drain(amount, action) : FluidStack.EMPTY;
      }
    };
  }

  private void changed() {
    if (level instanceof ServerLevel server) {
      if (!server.getServer().isSameThread()) {
        throw new IllegalStateException("Port write outside server thread");
      }
      resourceRevision = Math.incrementExact(resourceRevision);
      setChanged();
      StructureService.resourceChanged(server, worldPosition);
    }
  }

  private void assertMutationThread() {
    if (level instanceof ServerLevel server && !server.getServer().isSameThread()) {
      throw new IllegalStateException("Port write outside server thread");
    }
  }

  private void beforeItemWrite() {
    beforeResourceWrite();
  }

  private void beforeResourceWrite() {
    assertMutationThread();
    if (level instanceof ServerLevel && RecipeLifecycleEvents.isDispatching()) {
      throw new IllegalStateException("Cannot mutate resources during a read-only recipe notification");
    }
    if (resourceRevision == Long.MAX_VALUE) {
      throw new IllegalStateException("Port resource revision exhausted");
    }
  }

  /**
   * Short main-thread lease. No inventory/world notifications until every
   * participant is resolved.
   */
  public ItemStartLease acquireItemStart(long expectedRevision) {
    assertMutationThread();
    if (!(level instanceof ServerLevel)
        || isRemoved()
        || !schemaSupported()
        || kind.resource() != PortKind.Resource.ITEM
        || resourceRevision != expectedRevision
        || resourceRevision == Long.MAX_VALUE) {
      throw new IllegalStateException("Stale or unavailable item bus");
    }
    return new ItemStartLease(items.acquire());
  }

  public EnergyStartLease acquireEnergyStart(long expectedRevision) {
    assertMutationThread();
    if (!(level instanceof ServerLevel)
        || isRemoved()
        || !schemaSupported()
        || kind != PortKind.ENERGY_INPUT
        || resourceRevision != expectedRevision
        || resourceRevision == Long.MAX_VALUE) {
      throw new IllegalStateException("Stale or unavailable energy input hatch");
    }

    return new EnergyStartLease(energy.acquire());
  }

  public FluidStartLease acquireFluidStart(long expectedRevision) {
    assertMutationThread();
    if (!(level instanceof ServerLevel)
        || isRemoved()
        || !schemaSupported()
        || kind != PortKind.FLUID_INPUT
        || resourceRevision != expectedRevision
        || resourceRevision == Long.MAX_VALUE) {
      throw new IllegalStateException("Stale or unavailable fluid input hatch");
    }

    return new FluidStartLease(tank.acquire());
  }

  public FluidStartLease acquireFluidOutputStart(long expectedRevision) {
    assertMutationThread();
    if (!(level instanceof ServerLevel)
        || isRemoved()
        || !schemaSupported()
        || kind != PortKind.FLUID_OUTPUT
        || resourceRevision != expectedRevision
        || resourceRevision == Long.MAX_VALUE) {
      throw new IllegalStateException("Stale or unavailable fluid output hatch");
    }
    return new FluidStartLease(tank.acquire());
  }

  public final class EnergyStartLease implements AutoCloseable {
    private final LongEnergyBuffer.Lease storage;
    private boolean resolved, modified, notify, published;

    private EnergyStartLease(LongEnergyBuffer.Lease storage) {
      this.storage = storage;
    }

    public boolean extractExact(long amount) {
      assertMutationThread();
      if (resolved) {
        throw new IllegalStateException("Resolved energy lease");
      }
      boolean extracted = storage.extractExact(amount);
      modified |= extracted;

      return extracted;
    }

    public void resolve(boolean commit) {
      assertMutationThread();
      if (resolved) {
        throw new IllegalStateException("Resolved energy transaction");
      }
      storage.resolve(commit);
      if (commit && modified) {
        resourceRevision++;
        notify = true;
      }
      resolved = true;
    }

    public void publish() {
      assertMutationThread();
      if (!resolved) {
        throw new IllegalStateException("Unresolved energy transaction");
      }
      if (notify && !published) {
        published = true;
        setChanged();
        StructureService.resourceChanged((ServerLevel) level, worldPosition);
      }
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }

  public final class FluidStartLease implements AutoCloseable {
    private final TransactionalFluidTank.Lease storage;
    private boolean resolved, modified, notify, published;

    private FluidStartLease(TransactionalFluidTank.Lease storage) {
      this.storage = storage;
    }

    public boolean drainExact(FluidStack required) {
      assertMutationThread();
      if (resolved) {
        throw new IllegalStateException("Resolved fluid lease");
      }
      boolean drained = storage.drainExact(required);
      modified |= drained;

      return drained;
    }

    public boolean fillExact(FluidStack required) {
      assertMutationThread();
      if (resolved) {
        throw new IllegalStateException("Resolved fluid lease");
      }
      boolean filled = storage.fillExact(required);
      modified |= filled;
      return filled;
    }

    public void resolve(boolean commit) {
      assertMutationThread();
      if (resolved) {
        throw new IllegalStateException("Resolved fluid transaction");
      }
      storage.resolve(commit);
      if (commit && modified) {
        resourceRevision++;
        notify = true;
      }
      resolved = true;
    }

    public void publish() {
      assertMutationThread();
      if (!resolved) {
        throw new IllegalStateException("Unresolved fluid transaction");
      }
      if (notify && !published) {
        published = true;
        setChanged();
        StructureService.resourceChanged((ServerLevel) level, worldPosition);
      }
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }

  public final class ItemStartLease implements AutoCloseable {
    private final TransactionalItemStackHandler.Lease storage;
    private boolean resolved, modified, notify, published;

    private ItemStartLease(TransactionalItemStackHandler.Lease storage) {
      this.storage = storage;
    }

    public void replacePrepared(NonNullList<ItemStack> contents) {
      assertMutationThread();
      if (resolved) {
        throw new IllegalStateException("Resolved item lease");
      }
      storage.replacePrepared(contents);
      modified = true;
    }

    public void resolve(boolean commit) {
      assertMutationThread();
      if (resolved) {
        throw new IllegalStateException("Resolved item lease");
      }
      storage.resolve(commit);
      if (commit && modified) {
        resourceRevision++;
        notify = true;
      }
      resolved = true;
    }

    public void publish() {
      assertMutationThread();
      if (!resolved) {
        throw new IllegalStateException("Unresolved item transaction");
      }
      if (notify && !published) {
        published = true;
        setChanged();
        StructureService.resourceChanged((ServerLevel) level, worldPosition);
      }
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel server) {
      StructureService.blockChanged(server, worldPosition);
    }
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel server) {
      StructureService.blockChanged(server, worldPosition);
    }
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    if (level instanceof ServerLevel server) {
      StructureService.blockChanged(server, worldPosition);
    }
    super.onChunkUnloaded();
  }

  public PortKind kind() {
    return kind;
  }

  public HatchTier tier() {
    return tier;
  }

  public long resourceRevision() {
    return resourceRevision;
  }

  public boolean canGroupInput() {
    return kind == PortKind.ITEM_INPUT || kind == PortKind.FLUID_INPUT;
  }

  public int inputGroup() {
    return inputGroup;
  }

  public long groupRevision() {
    return groupRevision;
  }

  public int preferredInputGroup() {
    return preferredInputGroup;
  }

  public boolean configureInputGroup(int group, long expectedRevision) {
    assertMutationThread();
    InputGroup.require(group);
    if (!(level instanceof ServerLevel)
        || !canGroupInput()
        || !schemaSupported()
        || isRemoved()
        || groupRevision != expectedRevision
        || groupRevision == Long.MAX_VALUE
        || items.isInTransaction()
        || tank.isInTransaction()) {
      return false;
    }
    if (inputGroup == group) {
      return true;
    }
    beforeResourceWrite();
    inputGroup = group;
    if (group > 0) {
      preferredInputGroup = group;
    }
    groupRevision++;
    changed();

    return true;
  }

  public ItemStackHandler inventory() {
    return items;
  }

  public TransactionalFluidTank fluidTank() {
    return tank;
  }

  public LongEnergyBuffer energy() {
    return energy;
  }

  public IEnergyStorage energyCapability() {
    return energyCapability;
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
    machineColorAssigned = true;
    if (machineColor != color) {
      machineColor = color;
      MachineColorable.update(this);
    }
  }

  public IItemHandler itemCapability() {
    return itemCapability;
  }

  public IFluidHandler fluidCapability() {
    return fluidCapability;
  }

  public boolean schemaSupported() {
    return unsupportedData == null;
  }

  public boolean itemStartInProgress() {
    return items.isInTransaction();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    MachineColorable.save(this, tag, machineColor);
    if (unsupportedData != null) {
      tag.put("mmcece", unsupportedData.copy());
      return;
    }
    CompoundTag data = new CompoundTag();
    data.putInt("schema", 1);
    data.putInt("input_group", inputGroup);
    data.putInt("preferred_input_group", preferredInputGroup);
    if (kind.resource() == PortKind.Resource.ITEM) {
      data.put("items", items.serializeNBT(registries));
    }
    if (kind.resource() == PortKind.Resource.FLUID) {
      data.put("fluid", tank.writeToNBT(registries, new CompoundTag()));
    }
    if (kind.resource() == PortKind.Resource.ENERGY) {
      data.putLong("energy", energy.stored());
    }
    tag.put("mmcece", data);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    assertMutationThread();
    if (level instanceof ServerLevel && RecipeLifecycleEvents.isDispatching()) {
      throw new IllegalStateException("Cannot reload resources during a read-only recipe notification");
    }
    if (items.isInTransaction() || tank.isInTransaction()) {
      throw new IllegalStateException("Cannot reload a leased input/output port");
    }
    super.loadAdditional(tag, registries);
    machineColorAssigned = tag.contains(MachineColorable.COLOR_TAG);
    machineColor = MachineColorable.load(tag);
    boolean previouslySupported = schemaSupported();
    CompoundTag data = tag.getCompound("mmcece");
    if (data.getInt("schema") > 1
        || data.getInt("input_group") > 0
            && data.contains("preferred_input_group")
            && data.getInt("input_group") != data.getInt("preferred_input_group")
        || data.contains("preferred_input_group")
            && (!data.contains("preferred_input_group", Tag.TAG_INT) || data.getInt("preferred_input_group") <= 0)
        || data.contains("input_group")
            && (!data.contains("input_group", Tag.TAG_INT)
                || data.getInt("input_group") != -1 && data.getInt("input_group") <= 0)
        || !canGroupInput() && data.contains("input_group") && data.getInt("input_group") != -1) {
      unsupportedData = data.copy();
      if (level instanceof ServerLevel server) {
        StructureService.blockChanged(server, worldPosition);
      }
      return;
    }
    unsupportedData = null;
    inputGroup = data.contains("input_group") ? data.getInt("input_group") : -1;
    preferredInputGroup = data.contains("preferred_input_group") ? data.getInt("preferred_input_group") : Math
        .max(1, inputGroup);
    if (groupRevision == Long.MAX_VALUE) {
      unsupportedData = data.copy();
      return;
    }
    groupRevision++;
    if (kind.resource() == PortKind.Resource.ITEM && data.contains("items")) {
      CompoundTag inventoryData = data.getCompound("items");
      if (inventoryData.getInt("Size") != items.getSlots()) {
        unsupportedData = data.copy();
        if (level instanceof ServerLevel server) {
          StructureService.blockChanged(server, worldPosition);
        }
        return;
      }
      items.deserializeNBT(registries, inventoryData);
    }
    if (kind.resource() == PortKind.Resource.FLUID && data.contains("fluid")) {
      tank.readFromNBT(registries, data.getCompound("fluid"));
    }
    if (kind.resource() == PortKind.Resource.ENERGY) {
      energy.load(data.getLong("energy"));
    }
    if (level instanceof ServerLevel server) {
      changed();
      if (!previouslySupported) {
        StructureService.blockChanged(server, worldPosition);
      }
    }
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    return saveWithoutMetadata(registries);
  }

  public Component statusMessage() {
    if (!schemaSupported()) {
      return Component.translatable("message.mmcece.unsupported_schema");
    }
    return switch (kind.resource()) {
      case ITEM -> Component.translatable("message.mmcece.item_port", items.getSlots());
      case FLUID -> Component.translatable("message.mmcece.fluid_port", tank.getFluidAmount(), tank.getCapacity());
      case ENERGY -> Component
          .translatable("message.mmcece.energy_port", energy.stored(), energy.capacity(), energy.transferLimit());
    };
  }

  @Override
  public Component getDisplayName() {
    return getBlockState().getBlock().getName();
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    if (!schemaSupported()) {
      return null;
    }
    if (kind.resource() != PortKind.Resource.ITEM && player instanceof ServerPlayer serverPlayer) {
      PacketDistributor.sendToPlayer(serverPlayer, MachinePayloads.resourceStatus(this));
    }
    return kind.resource() == PortKind.Resource.ITEM ? new MachinePortMenu(
        id,
        inventory,
        this) : new MachineResourceMenu(id, inventory, this);
  }
}
