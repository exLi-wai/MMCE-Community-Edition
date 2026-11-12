package com.lw.modularmachine.integration.ae2;

import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.StorageHelper;
import appeng.api.util.AECableType;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.network.MachineNetworkPlatform;
import java.util.EnumSet;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal node lifecycle and powered transfers; never exposes network stock as
 * recipe stock.
 */
@ApiStatus.Internal
final class Ae2Connection implements MachineNetworkPlatform.Connection, IInWorldGridNodeHost, IActionHost {
  private static final String NODE_TAG = "node";
  private static final String OWNER_TAG = "owner_id";
  private static final IGridNodeListener<Ae2Connection> LISTENER = new IGridNodeListener<>() {
    @Override
    public void onSaveChanges(Ae2Connection owner, IGridNode node) {
      owner.port.setChanged();
    }

    @Override
    public void onStateChanged(Ae2Connection owner, IGridNode node, State state) {
      owner.port.setChanged();
    }
  };
  private final NetworkPortBlockEntity port;
  private final Function<ChemicalStackData, AEKey> chemicalKeys;
  private final IActionSource source = IActionSource.ofMachine(this);
  private IManagedGridNode node;
  private CompoundTag retained = new CompoundTag();
  private int owningPlayerId = -1;
  private boolean loaded;
  private boolean transferring;
  private long lifecycle;

  Ae2Connection(NetworkPortBlockEntity port, Function<ChemicalStackData, AEKey> chemicalKeys) {
    this.port = port;
    this.chemicalKeys = chemicalKeys;
  }

  @Override
  public void onLoad() {
    if (!(port.getLevel() instanceof ServerLevel)) {
      return;
    }
    requireServerThread();
    loaded = true;
    long expected = ++lifecycle;
    GridHelper.onFirstTick(port, ignored -> {
      if (expected == lifecycle && current() && node == null) {
        initializeNode();
      }
    });
  }

  private void initializeNode() {
    node = GridHelper.createManagedNode(this, LISTENER).setTagName(NODE_TAG).setInWorldNode(true)
        .setFlags(GridFlags.REQUIRE_CHANNEL).setExposedOnSides(EnumSet.allOf(Direction.class)).setIdlePowerUsage(1.0D)
        .setVisualRepresentation(port.getBlockState().getBlock());
    node.loadFromNBT(retained.copy());
    node.create(port.getLevel(), port.getBlockPos());
    if (owningPlayerId >= 0) {
      node.setOwningPlayerId(owningPlayerId);
    }
  }

  @Override
  public void onChunkUnloaded() {
    destroy();
  }

  @Override
  public void destroy() {
    if (port.getLevel() instanceof ServerLevel) {
      requireServerThread();
    }
    loaded = false;
    lifecycle++;
    if (node != null) {
      retained = new CompoundTag();
      node.saveToNBT(retained);
      if (node.getNode() != null) {
        owningPlayerId = node.getNode().getOwningPlayerId();
      }
      node.destroy();
      node = null;
    }
  }

  @Override
  public void load(CompoundTag tag, HolderLookup.Provider registries) {
    if (node != null) {
      throw new IllegalStateException("Cannot replace a live network node");
    }
    retained = tag.copy();
    owningPlayerId = tag.contains(OWNER_TAG, Tag.TAG_INT) ? tag.getInt(OWNER_TAG) : -1;
  }

  @Override
  public void save(CompoundTag tag, HolderLookup.Provider registries) {
    if (node != null) {
      node.saveToNBT(tag);
    } else if (retained.contains(NODE_TAG, Tag.TAG_COMPOUND)) {
      tag.put(NODE_TAG, retained.getCompound(NODE_TAG).copy());
    }
    int owner = node != null && node.getNode() != null ? node.getNode().getOwningPlayerId() : owningPlayerId;
    if (owner >= 0) {
      tag.putInt(OWNER_TAG, owner);
    }
  }

  @Override
  public void setOwningPlayer(Player player) {
    if (player instanceof ServerPlayer serverPlayer) {
      requireServerThread();
      owningPlayerId = IPlayerRegistry.getPlayerId(serverPlayer);
      if (node != null) {
        node.setOwningPlayerId(owningPlayerId);
      }
      port.setChanged();
    }
  }

  @Override
  public boolean active() {
    return current() && node != null && node.isActive();
  }

  private boolean current() {
    if (!(port.getLevel() instanceof ServerLevel level)) {
      return false;
    }
    requireServerThread();

    return loaded
        && !port.isRemoved()
        && port.schemaSupported()
        && level.hasChunkAt(port.getBlockPos())
        && level.getBlockEntity(port.getBlockPos()) == port;
  }

  private void requireServerThread() {
    if (!(port.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
      throw new IllegalStateException("Machine network access requires the server thread");
    }
  }

  @Override
  public IGridNode getGridNode(Direction side) {
    return node == null || !current() ? null : node.getNode();
  }

  @Override
  public AECableType getCableConnectionType(Direction side) {
    return AECableType.SMART;
  }

  @Override
  public IGridNode getActionableNode() {
    return getGridNode(null);
  }

  @Override
  public long extractItem(ItemStack prototype, long maximum) {
    requireResource(PortKind.Resource.ITEM);

    return transfer(prototype.isEmpty() ? null : AEItemKey.of(prototype), maximum, false);
  }

  @Override
  public long insertItem(ItemStack prototype, long maximum) {
    requireResource(PortKind.Resource.ITEM);

    return transfer(prototype.isEmpty() ? null : AEItemKey.of(prototype), maximum, true);
  }

  @Override
  public long extractFluid(FluidStack prototype, long maximum) {
    requireResource(PortKind.Resource.FLUID);

    return transfer(prototype.isEmpty() ? null : AEFluidKey.of(prototype), maximum, false);
  }

  @Override
  public long insertFluid(FluidStack prototype, long maximum) {
    requireResource(PortKind.Resource.FLUID);

    return transfer(prototype.isEmpty() ? null : AEFluidKey.of(prototype), maximum, true);
  }

  @Override
  public long extractChemical(ChemicalStackData prototype, long maximum) {
    requireResource(PortKind.Resource.CHEMICAL);

    return transfer(chemicalKey(prototype), maximum, false);
  }

  @Override
  public long insertChemical(ChemicalStackData prototype, long maximum) {
    requireResource(PortKind.Resource.CHEMICAL);

    return transfer(chemicalKey(prototype), maximum, true);
  }

  private AEKey chemicalKey(ChemicalStackData prototype) {
    return chemicalKeys == null || prototype.isEmpty() ? null : chemicalKeys.apply(prototype);
  }

  private void requireResource(PortKind.Resource resource) {
    requireServerThread();
    if (port.kind().resource() != resource) {
      throw new IllegalArgumentException("Network port resource type mismatch");
    }
  }

  private long transfer(AEKey key, long maximum, boolean insert) {
    requireServerThread();
    if (key == null
        || maximum <= 0
        || !active()
        || !port.networkTransferInProgress()
        || !insert && !port.kind().isInput()) {
      return 0;
    }
    if (transferring) {
      throw new IllegalStateException("Reentrant machine network transfer");
    }
    var grid = node.getGrid();
    if (grid == null) {
      return 0;
    }
    var inventory = grid.getStorageService().getInventory();

    transferring = true;
    try {
      if (insert) {
        return StorageHelper.poweredInsert(grid.getEnergyService(), inventory, key, maximum, source);
      }

      return StorageHelper.poweredExtraction(grid.getEnergyService(), inventory, key, maximum, source);
    } finally {
      transferring = false;
    }
  }
}
