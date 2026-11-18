package com.lw.modularmachine.integration.astral;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.api.recipe.KeyedAmountRecipeData;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.recipe.resource.keyed.KeyedResourcePort;
import com.lw.modularmachine.machine.recipe.resource.keyed.KeyedStock;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource.ReplenishmentProof;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import com.mojang.serialization.Codec;
import hellfirepvp.astralsorcery.common.constellation.BaseConstellation;
import hellfirepvp.astralsorcery.common.constellation.level.LevelSkyHandler;
import hellfirepvp.astralsorcery.common.lib.RegistriesAS;
import hellfirepvp.astralsorcery.common.lib.StarlightNetworkNodesAS;
import hellfirepvp.astralsorcery.common.starlight.api.TransmissionNode;
import hellfirepvp.astralsorcery.common.starlight.api.provider.TransmissionNodeProvider;
import hellfirepvp.astralsorcery.common.starlight.transmission.StarlightTransmissionPacket;
import hellfirepvp.astralsorcery.common.tile.base.TileEntityNetwork;
import hellfirepvp.astralsorcery.common.tile.network.ForwardingStarlightReceiverNode;
import hellfirepvp.astralsorcery.common.util.data.TileRegistryObject;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public abstract class StarlightHatch<N extends TransmissionNode> extends TileEntityNetwork<N, TileEntityNetwork.Data> implements MachineColorable, TickingRecipeHatchBlock.ServerTicking, KeyedResourcePort {
  public static final ResourceLocation SKY = ResourceLocation.fromNamespaceAndPath("mmcece", "sky");
  public static final ResourceLocation UNATTUNED = ResourceLocation.fromNamespaceAndPath("mmcece", "unattuned");
  private final boolean input;
  protected final KeyedStock stock;
  private final int capacity;
  private int color = MachineConfig.defaultCasingColor();
  private boolean assigned;
  private int leases;

  private StarlightHatch(TileRegistryObject<?> type, BlockPos position, BlockState state, boolean input) {
    super(type, position, state);
    this.input = input;
    capacity = input ? inputCapacity(state) : 2_000_000;
    stock = new KeyedStock(capacity);
  }

  static int inputCapacity(BlockState state) {
    if (state.is(MachineRegistries.STARLIGHT_INPUT_BRILLIANT.get())) {
      return 32_000_000;
    }
    return state.is(MachineRegistries.STARLIGHT_INPUT_TRAIT.get()) ? 8_000_000 : 2_000_000;
  }

  void openMenu(net.minecraft.world.entity.player.Player player) {
    com.lw.modularmachine.menu.LegacyHatchMenu
        .open(player, com.lw.modularmachine.menu.LegacyHatchMenu.Kind.STARLIGHT, this, index -> {
          throw new IndexOutOfBoundsException(index);
        }, this::storedStarlight, () -> capacity, () -> (int) stock.amount(SKY), () -> (int) stock.amount(UNATTUNED));
  }

  @Override
  protected final Data newTileData() {
    return new InitialData();
  }

  @Override
  public final Codec<Data> dataCodec() {
    return Data.CODEC;
  }

  @Override
  public final void tickHatch() {
    var server = liveLevel();
    if (server != null && mutable()) {
      super.serverTick(server);
      if (input && Math.floorMod(server.getGameTime() + worldPosition.asLong(), 20) == 0) {
        refreshReceiverStock(server);
      }
    }
  }

  private void refreshReceiverStock(ServerLevel server) {
    boolean changed = stock.total() > 0;
    for (var key : stock.keys()) {
      stock.move(key, -Math.max(1, stock.amount(key) / 20), false);
    }
    int height = worldPosition.getY() - 40;
    if (height > 0
        && server.dimensionType().hasSkyLight()
        && !server.isRaining()
        && server.canSeeSky(worldPosition.above())
        && LevelSkyHandler.getContext(server).isPresent()) {
      int collection = Math.min(160_000, height * (server.isNight() ? 1_000 : 250));
      changed |= stock.move(SKY, collection, false) > 0;
    }
    if (changed) {
      publish();
    }
  }

  public static BaseConstellation constellation(ResourceLocation id) {
    return RegistriesAS.REGISTRY_CONSTELLATIONS.getOptional(id).orElse(null);
  }

  public static boolean active(ServerLevel level, ResourceLocation id) {
    var constellation = constellation(id);
    return constellation != null
        && LevelSkyHandler.getContext(level)
            .map(context -> context.getConstellationHandler().isCurrentlyActive(constellation)).orElse(false);
  }

  private ServerLevel liveLevel() {
    if (!(level instanceof ServerLevel server)) {
      return null;
    }
    ServerThreadGuard.require(server, "Starlight hatch access");
    return !isRemoved()
        && server.hasChunkAt(worldPosition)
        && server.getBlockEntity(worldPosition) == this ? server : null;
  }

  protected final boolean mutable() {
    return liveLevel() != null && leases == 0 && !RecipeLifecyclePublisher.isDispatching();
  }

  @Override
  public final Object resourceAddress() {
    return liveLevel() != null ? this : null;
  }

  @Override
  public final long available(ResourceLocation key) {
    return input && liveLevel() != null ? stock.amount(key) : 0;
  }

  @Override
  public final java.util.List<ResourceLocation> inputKeys(ResourceLocation requested) {
    return KeyedAmountRecipeData.ANY_STARLIGHT.equals(requested) ? stock.keys() : java.util.List.of(requested);
  }

  @Override
  public final long space(ResourceLocation key) {
    return totalSpace();
  }

  @Override
  public final long totalSpace() {
    return !input && liveLevel() != null ? stock.space() : 0;
  }

  @Override
  public final boolean watchInput(ReplenishmentProof proof) {
    stock.watchInput(proof);
    return true;
  }

  @Override
  public final void watchOutput(ReplenishmentProof proof) {
    stock.watchOutput(proof);
  }

  @Override
  public void setRemoved() {
    stock.invalidateProofs();
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    stock.invalidateProofs();
    super.onChunkUnloaded();
  }

  @Override
  public final RecipeResourceParticipant acquire(ResourceLocation key, long delta) {
    if (liveLevel() == null || RecipeLifecyclePublisher.isDispatching() || (input ? delta >= 0 : delta <= 0)) {
      throw new IllegalStateException("Starlight hatch cannot acquire this resource change");
    }
    var participant = stock.acquire(key, delta);
    leases++;
    return commit -> {
      try {
        participant.resolve(commit);
      } finally {
        leases--;
      }
    };
  }

  public final int storedStarlight() {
    return (int) stock.total();
  }

  @Override
  public final void publish() {
    setChanged(false);
  }

  @Override
  public final int machineColor() {
    return color;
  }

  @Override
  public final boolean machineColorAssigned() {
    return assigned;
  }

  @Override
  public final void setMachineColor(int value) {
    int next = value & 0xFFFFFF;
    if (!assigned || color != next) {
      assigned = true;
      color = next;
      MachineColorable.update(this);
    }
  }

  @Override
  protected final void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    MachineColorable.save(this, tag, color);
    tag.put("starlight", stock.save());
  }

  @Override
  protected final void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Starlight hatch load");
      if (leases != 0 || RecipeLifecyclePublisher.isDispatching()) {
        throw new IllegalStateException("Cannot replace reserved starlight");
      }
    }
    super.loadAdditional(tag, registries);
    color = MachineColorable.load(tag);
    assigned = tag.contains(MachineColorable.COLOR_TAG);
    stock.load(tag.getCompound("starlight"));
  }

  @Override
  public final CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = super.getUpdateTag(registries);
    MachineColorable.save(this, tag, color);
    return tag;
  }

  @Override
  public final void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
    super.handleUpdateTag(tag, registries);
    color = MachineColorable.load(tag);
    assigned = tag.contains(MachineColorable.COLOR_TAG);
  }

  private static final class InitialData extends Data {
    private InitialData() {
      super(0L, false, java.util.Map.of(), true);
    }
  }

  public static final class Input extends StarlightHatch<ForwardingStarlightReceiverNode> implements ForwardingStarlightReceiverNode.ReceiverTile {
    public Input(BlockPos position, BlockState state) {
      super(new TileRegistryObject<>(MachineRegistries.STARLIGHT_INPUT_ENTITY), position, state, true);
    }

    @Override
    public DeferredHolder<TransmissionNodeProvider<?>, ? extends TransmissionNodeProvider<ForwardingStarlightReceiverNode>> getNodeProvider() {
      return StarlightNetworkNodesAS.FORWARDING_RECEIVER_NODE;
    }

    @Override
    public void receiveStarlight(ServerLevel level, StarlightTransmissionPacket packet) {
      if (!mutable() || !Float.isFinite(packet.amount()) || packet.amount() <= 0) {
        return;
      }
      var key = packet.constellation() == null ? UNATTUNED : packet.constellation().getHolder()
          .flatMap(holder -> holder.unwrapKey()).map(ResourceKey::location).orElse(null);
      if (key != null && stock.move(key, Math.round((double) packet.amount() * 1_000), false) > 0) {
        publish();
      }
    }
  }

  public static final class Output extends StarlightHatch<StarlightOutputNode> {
    private long lastEmit = Long.MIN_VALUE;

    public Output(BlockPos position, BlockState state) {
      super(new TileRegistryObject<>(MachineRegistries.STARLIGHT_OUTPUT_ENTITY), position, state, false);
    }

    @Override
    public DeferredHolder<TransmissionNodeProvider<?>, ? extends TransmissionNodeProvider<StarlightOutputNode>> getNodeProvider() {
      return AstralIntegration.OUTPUT;
    }

    Optional<StarlightTransmissionPacket> emit() {
      if (!mutable() || lastEmit == level.getGameTime()) {
        return Optional.empty();
      }
      for (var key : stock.keys()) {
        var constellation = constellation(key);
        if (constellation == null && !UNATTUNED.equals(key)) {
          continue;
        }
        long debit = Math.min(stock.amount(key), 10_000L);
        if (debit > 0) {
          stock.move(key, -debit, false);
          lastEmit = level.getGameTime();
          publish();
          return Optional.of(new StarlightTransmissionPacket(constellation, debit / 1_000F));
        }
      }
      return Optional.empty();
    }
  }
}
