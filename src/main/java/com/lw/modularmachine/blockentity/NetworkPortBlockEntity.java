package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.chemical.ChemicalPlatform;
import com.lw.modularmachine.machine.network.MachineNetworkPlatform;
import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.NetworkPortService;
import com.lw.modularmachine.machine.network.NetworkPortSnapshot;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.menu.NetworkPortMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.storage.TransactionalChemicalTank;
import com.lw.modularmachine.storage.TransactionalFluidTank;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * Owned physical buffers with a narrow network adapter. A network transfer and
 * its local quantity change resolve in the same main-thread visit.
 */
public final class NetworkPortBlockEntity extends MachinePortBlockEntity {
  private final int rows;
  private final List<TransactionalFluidTank> fluids = new ArrayList<>();
  private final List<TransactionalChemicalTank> chemicals = new ArrayList<>();
  private final NetworkPortResource[] configured = new NetworkPortResource[9];
  private final int[] targets = new int[9];
  private MachineNetworkPlatform.Connection connection;
  private long configRevision;
  private int nextRow;
  private boolean externalCall;
  private CompoundTag unsupportedNetwork;

  public NetworkPortBlockEntity(BlockPos pos, BlockState state) {
    super(MachineRegistries.NETWORK_PORT_ENTITY.get(), pos, state);
    rows = kind().isInput() ? 9 : 36;
    fluids.add(super.fluidTank());
    chemicals.add(super.chemicalTank());
    for (int index = 1; index < rows; index++) {
      if (kind().resource() == PortKind.Resource.FLUID) {
        fluids.add(
            new TransactionalFluidTank(
                Integer.MAX_VALUE,
                this::assertMutationThread,
                this::beforeResourceWrite,
                this::changed));
      }
      if (kind().resource() == PortKind.Resource.CHEMICAL) {
        chemicals.add(
            new TransactionalChemicalTank(
                Integer.MAX_VALUE,
                this::assertMutationThread,
                this::beforeResourceWrite,
                this::changed));
      }
    }
  }

  public MachineNetworkPlatform.Connection connection() {
    if (connection == null) {
      connection = MachineNetworkPlatform.create(this);
    }
    return connection;
  }

  @Override
  public int fluidTankCount() {
    return fluids == null ? 1 : fluids.size();
  }

  @Override
  public int chemicalTankCount() {
    return chemicals == null ? 1 : chemicals.size();
  }

  @Override
  public TransactionalFluidTank fluidTank(int index) {
    return fluids.get(index);
  }

  @Override
  public TransactionalChemicalTank chemicalTank(int index) {
    return chemicals.get(index);
  }

  @Override
  public boolean bulkItems() {
    return true;
  }

  @Override
  public boolean schemaSupported() {
    return unsupportedNetwork == null && super.schemaSupported();
  }

  @Override
  protected void beforeResourceWrite() {
    super.beforeResourceWrite();
    if (externalCall || readOnlyCallback()) {
      throw new IllegalStateException("Cannot reenter a network port transfer");
    }
  }

  public boolean networkReadyToEdit() {
    assertMutationThread();
    return level instanceof ServerLevel
        && !isRemoved()
        && level.hasChunkAt(worldPosition)
        && level.getBlockEntity(worldPosition) == this
        && schemaSupported()
        && !externalCall
        && !resourceTransactionInProgress()
        && !readOnlyCallback()
        && resourceRevision() < Long.MAX_VALUE
        && configRevision < Long.MAX_VALUE;
  }

  public long configRevision() {
    return configRevision;
  }

  private static boolean readOnlyCallback() {
    return RecipeLifecyclePublisher.isDispatching()
        || SmartInterfaceEventPublisher.isDispatching()
        || UpgradeLifecyclePublisher.isDispatchingAny();
  }

  /**
   * Internal proof for the adapter: network writes require the owning buffer
   * lease.
   */
  public boolean networkTransferInProgress() {
    return externalCall && !readOnlyCallback() && resourceTransactionInProgress();
  }

  public Optional<NetworkPortResource> sampleConfiguration(ItemStack carried) {
    if (!networkReadyToEdit() || carried.isEmpty()) {
      return Optional.empty();
    }
    externalCall = true;
    try {
      var sample = carried.copyWithCount(1);
      return switch (kind().resource()) {
        case ITEM -> Optional.of(new NetworkPortResource.Item(sample));
        case FLUID -> FluidUtil.getFluidContained(sample).map(NetworkPortResource.Fluid::new);
        case CHEMICAL -> {
          var chemical = ChemicalPlatform.sampleContainer(sample);
          yield chemical.isEmpty() ? Optional.empty() : Optional
              .of(new NetworkPortResource.Chemical(chemical.chemical()));
        }
        case ENERGY -> Optional.empty();
      };
    } finally {
      externalCall = false;
    }
  }

  public boolean configure(int slot, NetworkPortResource resource, int amount, long expectedRevision) {
    if (!networkReadyToEdit()
        || !kind().isInput()
        || slot < 0
        || slot >= 9
        || expectedRevision != configRevision
        || resource != null && (resource.kind() != kind().resource() || amount < 1)
        || resource instanceof NetworkPortResource.Chemical chemical && !ChemicalPlatform.contains(chemical.id())
        || resource == null && amount != 0) {
      return false;
    }
    NetworkPortResource replacement;
    boolean same;
    externalCall = true;
    try {
      replacement = copyResource(resource);
      same = java.util.Objects.equals(configured[slot], replacement) && targets[slot] == amount;
    } finally {
      externalCall = false;
    }
    if (!networkReadyToEdit() || expectedRevision != configRevision) {
      return false;
    }
    if (same) {
      return true;
    }
    configured[slot] = replacement;
    targets[slot] = amount;
    configRevision++;
    setChanged();
    return true;
  }

  private NetworkPortSnapshot.Row row(int index) {
    return row(index, level.registryAccess());
  }

  private NetworkPortSnapshot.Row row(int index, HolderLookup.Provider registries) {
    NetworkPortResource resource = null;
    long amount = 0;
    switch (kind().resource()) {
      case ITEM -> {
        var stack = inventory().getStackInSlot(index);
        if (!stack.isEmpty()) {
          resource = new NetworkPortResource.Item(stack);
          amount = stack.getCount();
        }
      }
      case FLUID -> {
        var stack = fluidTank(index).getFluid();
        if (!stack.isEmpty()) {
          resource = new NetworkPortResource.Fluid(stack);
          amount = stack.getAmount();
        }
      }
      case CHEMICAL -> {
        var stack = chemicalTank(index).getStack();
        if (!stack.isEmpty()) {
          resource = new NetworkPortResource.Chemical(stack.chemical());
          amount = stack.amount();
        }
      }
      case ENERGY -> throw new IllegalStateException("Energy is not a network buffer");
    }
    return new NetworkPortSnapshot.Row(
        kind().isInput() ? copyResource(configured[index], registries) : null,
        kind().isInput() ? targets[index] : 0,
        copyResource(resource, registries),
        amount);
  }

  private NetworkPortResource copyResource(NetworkPortResource resource) {
    return copyResource(resource, level.registryAccess());
  }

  private static NetworkPortResource copyResource(NetworkPortResource resource, HolderLookup.Provider registries) {
    if (resource == null) {
      return null;
    }
    return NetworkPortResource.load(registries, resource.save(registries).copy());
  }

  public NetworkPortSnapshot snapshot() {
    assertMutationThread();
    if (externalCall || resourceTransactionInProgress()) {
      throw new IllegalStateException("Cannot snapshot a busy network port");
    }
    var result = new ArrayList<NetworkPortSnapshot.Row>();
    externalCall = true;
    try {
      for (int index = 0; index < rows; index++) {
        result.add(row(index));
      }
    } finally {
      externalCall = false;
    }
    return new NetworkPortSnapshot(
        kind().resource(),
        kind().isInput(),
        configRevision,
        level instanceof ServerLevel && connection().active(),
        schemaSupported(),
        result);
  }

  /** One bounded visit; an inactive network does not spin over every buffer. */
  public void networkStep() {
    if (!networkReadyToEdit() || !connection().active()) {
      return;
    }
    int index = nextRow;
    nextRow = (nextRow + 1) % rows;
    long expectedRevision = resourceRevision();
    long expectedConfig = configRevision;
    NetworkPortSnapshot.Row row;
    boolean same;
    externalCall = true;
    try {
      row = row(index);
      same = java.util.Objects.equals(row.buffered(), row.configured());
    } finally {
      externalCall = false;
    }
    if (!networkReadyToEdit() || resourceRevision() != expectedRevision || configRevision != expectedConfig) {
      return;
    }
    boolean extracting = kind().isInput() && (row.buffered() == null || same) && row.target() > row.stored();
    long requested = extracting ? row.target() - row.stored() : row.stored() - (same ? row.target() : 0);
    requested = Math.min(Integer.MAX_VALUE, requested);
    var resource = extracting ? row.configured() : row.buffered();
    if (resource == null || requested <= 0) {
      return;
    }
    switch (kind().resource()) {
      case ITEM -> transferItems(
          index,
          (NetworkPortResource.Item) resource,
          (int) row.stored(),
          requested,
          extracting,
          expectedRevision);
      case FLUID -> transferFluid(
          index,
          (NetworkPortResource.Fluid) resource,
          (int) row.stored(),
          requested,
          extracting,
          expectedRevision);
      case CHEMICAL -> transferChemical(index, (NetworkPortResource.Chemical) resource, requested, extracting);
      case ENERGY -> throw new IllegalStateException("Energy is not a network buffer");
    }
  }

  private void transferItems(int index, NetworkPortResource.Item resource, int stored, long maximum, boolean extracting, long expectedRevision) {
    var prepared = NonNullList.withSize(rows, ItemStack.EMPTY);
    externalCall = true;
    try {
      for (int slot = 0; slot < rows; slot++) {
        prepared.set(slot, inventory().getStackInSlot(slot).copy());
      }
    } finally {
      externalCall = false;
    }
    var prototype = resource.stack();
    try (var lease = acquireItemStart(expectedRevision)) {
      externalCall = true;
      try {
        int actual = actual(
            extracting ? connection().extractItem(prototype, maximum) : connection().insertItem(prototype, maximum),
            maximum);
        if (actual > 0) {
          prepared.set(index, prototype.copyWithCount(extracting ? stored + actual : stored - actual));
          lease.replacePrepared(prepared);
        }
        lease.resolve(true);
      } finally {
        externalCall = false;
      }
      lease.publish();
    }
  }

  private void transferFluid(int index, NetworkPortResource.Fluid resource, int stored, long maximum, boolean extracting, long expectedRevision) {
    var prototype = resource.stack();
    var prepared = prototype.copyWithAmount(stored);
    try (var lease = kind()
        .isInput() ? acquireFluidStart(expectedRevision, index) : acquireFluidOutputStart(expectedRevision, index)) {
      externalCall = true;
      try {
        int actual = actual(
            extracting ? connection().extractFluid(prototype, maximum) : connection().insertFluid(prototype, maximum),
            maximum);
        if (actual > 0) {
          prepared.setAmount(extracting ? stored + actual : stored - actual);
          lease.replacePrepared(prepared.isEmpty() ? FluidStack.EMPTY : prepared);
        }
        lease.resolve(true);
      } finally {
        externalCall = false;
      }
      lease.publish();
    }
  }

  private void transferChemical(int index, NetworkPortResource.Chemical resource, long maximum, boolean extracting) {
    var prototype = new ChemicalStackData(resource.id(), 1);
    try (var lease = kind().isInput() ? acquireChemicalStart(resourceRevision(), index) : acquireChemicalOutputStart(
        resourceRevision(),
        index)) {
      externalCall = true;
      try {
        int actual = actual(
            extracting ? connection().extractChemical(prototype, maximum) : connection()
                .insertChemical(prototype, maximum),
            maximum);
        if (actual > 0
            && !(extracting ? lease.fillExact(prototype.withAmount(actual)) : lease
                .drainExact(prototype.withAmount(actual)))) {
          throw new IllegalStateException("Leased chemical buffer changed during network transfer");
        }
        lease.resolve(true);
      } finally {
        externalCall = false;
      }
      lease.publish();
    }
  }

  private static int actual(long actual, long maximum) {
    if (actual < 0 || actual > maximum || actual > Integer.MAX_VALUE) {
      throw new IllegalStateException("Network adapter returned an invalid transfer amount");
    }
    return (int) actual;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel) {
      connection().onLoad();
      NetworkPortService.add(this);
    }
  }

  @Override
  public void onChunkUnloaded() {
    NetworkPortService.remove(this);
    if (connection != null) {
      connection.onChunkUnloaded();
    }
    super.onChunkUnloaded();
  }

  @Override
  public void setRemoved() {
    NetworkPortService.remove(this);
    if (connection != null) {
      connection.destroy();
    }
    super.setRemoved();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (externalCall || resourceTransactionInProgress()) {
      throw new IllegalStateException("Cannot save a leased network port");
    }
    if (unsupportedNetwork != null) {
      tag.merge(unsupportedNetwork.copy());
      MachineColorable.save(this, tag, machineColor());
      return;
    }
    super.saveAdditional(tag, registries);
    var data = new CompoundTag();
    data.putInt("schema", 1);
    data.putString("kind", kind().name());
    var configs = new ListTag();
    if (kind().isInput()) {
      for (int index = 0; index < 9; index++) {
        var entry = new CompoundTag();
        if (configured[index] != null) {
          entry.put("resource", configured[index].save(registries));
          entry.putInt("amount", targets[index]);
        }
        configs.add(entry);
      }
    }
    data.put("config", configs);
    var extra = new ListTag();
    for (int index = 1; index < rows && kind().resource() != PortKind.Resource.ITEM; index++) {
      var row = row(index, registries);
      var entry = new CompoundTag();
      if (row.buffered() != null) {
        entry.put("resource", row.buffered().save(registries));
        entry.putLong("amount", row.stored());
      }
      extra.add(entry);
    }
    data.put("extra_buffers", extra);
    tag.put("network_port", data);
    var network = new CompoundTag();
    connection().save(network, registries);
    tag.put("network_connection", network);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    assertMutationThread();
    if (externalCall || resourceTransactionInProgress() || level instanceof ServerLevel && readOnlyCallback()) {
      throw new IllegalStateException("Cannot replace a busy network port");
    }
    if (level != null && level.isClientSide && tag.getBoolean("network_client")) {
      super.loadAdditional(tag, registries);
      return;
    }
    unsupportedNetwork = null;
    try {
      var data = tag.getCompound("network_port");
      if (data.isEmpty()) {
        // Only a freshly created block may lack the network save envelope.
        if (tag.contains("mmcece") || tag.contains("network_connection")) {
          throw new IllegalArgumentException("Missing network port state");
        }
        super.loadAdditional(tag, registries);
        return;
      }
      if (data.getInt("schema") != 1
          || !data.getString("kind").equals(kind().name())
          || !data.contains("config", Tag.TAG_LIST)
          || !data.contains("extra_buffers", Tag.TAG_LIST)) {
        throw new IllegalArgumentException("Invalid network port state");
      }
      var configs = data.getList("config", Tag.TAG_COMPOUND);
      var extra = data.getList("extra_buffers", Tag.TAG_COMPOUND);
      requireCompoundList((ListTag) data.get("config"));
      requireCompoundList((ListTag) data.get("extra_buffers"));
      if (configs.size() != (kind().isInput() ? 9 : 0)
          || extra.size() != (kind().resource() == PortKind.Resource.ITEM ? 0 : rows - 1)) {
        throw new IllegalArgumentException("Wrong network buffer count");
      }
      var newConfig = new NetworkPortResource[9];
      var newTargets = new int[9];
      var buffers = new ArrayList<NetworkPortSnapshot.Row>();
      for (int index = 0; index < configs.size(); index++) {
        var entry = configs.getCompound(index);
        if (!entry.isEmpty()) {
          newConfig[index] = readResource(entry, registries);
          newTargets[index] = entry.getInt("amount");
        }
      }
      for (int index = 0; index < extra.size(); index++) {
        var entry = extra.getCompound(index);
        NetworkPortResource resource = null;
        long amount = 0;
        if (!entry.isEmpty()) {
          if (!entry.contains("amount", Tag.TAG_LONG)
              || entry.getLong("amount") <= 0
              || !entry.contains("resource", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Invalid stored network quantity");
          }
          amount = entry.getLong("amount");
          resource = readIdentity(entry.getCompound("resource"), registries);
          if (kind().resource() == PortKind.Resource.FLUID && amount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Fluid quantity exceeds int capacity");
          }
        }
        buffers.add(new NetworkPortSnapshot.Row(null, 0, resource, amount));
      }
      validateBaseStorage(tag, registries);
      super.loadAdditional(tag, registries);
      for (int index = 0; index < 9; index++) {
        configured[index] = newConfig[index];
        targets[index] = newTargets[index];
      }
      for (int index = 0; index < buffers.size(); index++) {
        var buffer = buffers.get(index);
        if (kind().resource() == PortKind.Resource.FLUID) {
          fluidTank(index + 1).setFluid(
              buffer.buffered() instanceof NetworkPortResource.Fluid fluid ? fluid.stack()
                  .copyWithAmount((int) buffer.stored()) : FluidStack.EMPTY);
        } else {
          var stored = buffer.buffered() instanceof NetworkPortResource.Chemical chemical ? new ChemicalStackData(
              chemical.id(),
              buffer.stored()) : ChemicalStackData.EMPTY;
          chemicalTank(index + 1).load(stored.save());
        }
      }
      configRevision = Math.incrementExact(configRevision);
      boolean loaded = level instanceof ServerLevel && !isRemoved();
      if (connection != null) {
        connection.destroy();
      }
      connection().load(tag.getCompound("network_connection"), registries);
      if (loaded) {
        connection.onLoad();
      }
    } catch (RuntimeException invalid) {
      unsupportedNetwork = tag.copy();
    }
  }

  private static void requireCompoundList(ListTag list) {
    if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Network data requires compound entries");
    }
  }

  private void validateBaseStorage(CompoundTag tag, HolderLookup.Provider registries) {
    if (!tag.contains("mmcece", Tag.TAG_COMPOUND)) {
      throw new IllegalArgumentException("Missing physical network buffers");
    }
    var data = tag.getCompound("mmcece");
    if (!data.contains("schema", Tag.TAG_INT) || data.getInt("schema") != 1) {
      throw new IllegalArgumentException("Invalid physical network buffer schema");
    }
    String field = switch (kind().resource()) {
      case ITEM -> "items";
      case FLUID -> "fluid";
      case CHEMICAL -> "chemical";
      case ENERGY -> throw new IllegalStateException("Unexpected network energy buffer");
    };
    if (!data.contains(field, Tag.TAG_COMPOUND)) {
      throw new IllegalArgumentException("Missing physical network buffer");
    }
    var saved = data.getCompound(field);
    if (kind().resource() == PortKind.Resource.FLUID && !saved.isEmpty()) {
      if (!saved.contains("Fluid", Tag.TAG_COMPOUND)) {
        throw new IllegalArgumentException("Invalid physical fluid network buffer");
      }
      FluidStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), saved.getCompound("Fluid"))
          .getOrThrow();
    }
    if (kind().resource() == PortKind.Resource.CHEMICAL) {
      var chemical = ChemicalStackData.load(saved);
      if (!chemical.isEmpty() && !ChemicalPlatform.contains(chemical.chemical())) {
        throw new IllegalArgumentException("Invalid physical chemical network buffer");
      }
    }
  }

  private NetworkPortResource readResource(CompoundTag entry, HolderLookup.Provider registries) {
    if (!entry.contains("amount", Tag.TAG_INT)
        || entry.getInt("amount") < 1
        || !entry.contains("resource", Tag.TAG_COMPOUND)) {
      throw new IllegalArgumentException("Invalid network buffer quantity");
    }
    return readIdentity(entry.getCompound("resource"), registries);
  }

  private NetworkPortResource readIdentity(CompoundTag tag, HolderLookup.Provider registries) {
    var resource = NetworkPortResource.load(registries, tag.copy());
    if (resource.kind() != kind().resource()
        || resource instanceof NetworkPortResource.Chemical chemical && !ChemicalPlatform.contains(chemical.id())) {
      throw new IllegalArgumentException("Unavailable or mixed network resource");
    }
    return resource;
  }

  public CompoundTag portableData(HolderLookup.Provider registries) {
    var data = saveWithoutMetadata(registries);
    data.put("network_connection", new CompoundTag());
    return data;
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = new CompoundTag();
    MachineColorable.save(this, tag, machineColor());
    tag.putBoolean("network_client", true);
    return tag;
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    return schemaSupported() ? new NetworkPortMenu(id, inventory, this) : null;
  }
}
