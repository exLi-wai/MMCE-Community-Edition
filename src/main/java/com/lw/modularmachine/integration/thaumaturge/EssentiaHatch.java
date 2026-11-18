package com.lw.modularmachine.integration.thaumaturge;

import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import com.leclowndu93150.thaumaturge.content.item.PhialItem;
import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import com.lw.modularmachine.block.TickingRecipeHatchBlock;
import com.lw.modularmachine.blockentity.MachineCasingBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.recipe.resource.keyed.KeyedRequestObservers;
import com.lw.modularmachine.machine.recipe.resource.keyed.KeyedResourcePort;
import com.lw.modularmachine.machine.recipe.resource.keyed.KeyedStock;
import com.lw.modularmachine.machine.recipe.search.RecipeSearchResource.ReplenishmentProof;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public abstract class EssentiaHatch extends MachineCasingBlockEntity implements TickingRecipeHatchBlock.ServerTicking, KeyedResourcePort {
  protected static final int CAPACITY = 4096;
  protected final KeyedStock stock = new KeyedStock(CAPACITY);
  protected final boolean input;
  protected boolean transferring;
  private int reservations;
  private final Map<ResourceLocation, Demand> demands = new LinkedHashMap<>();
  private final KeyedRequestObservers requestObservers = new KeyedRequestObservers();

  protected EssentiaHatch(BlockEntityType<?> type, BlockPos position, BlockState state, boolean input) {
    super(type, position, state);
    this.input = input;
  }

  @Override
  public Object resourceAddress() {
    return live() ? this : null;
  }

  @Override
  public long available(ResourceLocation key) {
    return input && live() ? stock.amount(key) : 0;
  }

  @Override
  public long space(ResourceLocation key) {
    return totalSpace();
  }

  @Override
  public long totalSpace() {
    return !input && live() ? stock.space() : 0;
  }

  @Override
  public boolean watchInput(ReplenishmentProof proof) {
    stock.watchInput(proof);
    return true;
  }

  @Override
  public final void watchOutput(ReplenishmentProof proof) {
    stock.watchOutput(proof);
  }

  @Override
  public final boolean watchInputAmount(ReplenishmentProof proof) {
    if (!watchInput(proof)) {
      return false;
    }
    stock.watchOutput(proof);
    return true;
  }

  @Override
  public void setRemoved() {
    stock.invalidateProofs();
    requestObservers.invalidate();
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    stock.invalidateProofs();
    requestObservers.invalidate();
    super.onChunkUnloaded();
  }

  @Override
  public final long requestSpace() {
    if (!input || !mutable()) {
      return 0;
    }
    long free = unpromisedSpace();
    return free > 0 && hasRequestSource() ? free : 0;
  }

  protected abstract boolean hasRequestSource();

  @Override
  public final long outstandingRequest(ResourceLocation key, ReplenishmentProof proof) {
    if (!input || !live()) {
      return 0;
    }
    requestObservers.watch(key, proof);
    long pending = deficit(key);
    if (pending == 0) {
      return 0;
    }
    boolean connected = hasRequestSource();
    if (proof != null) {
      proof.observeExpiry(demands.get(key).expires(), level::getGameTime);
      proof.addLiveCheck(() -> hasRequestSource() == connected);
    }
    return connected ? pending : 0;
  }

  @Override
  public long request(ResourceLocation key, long additional, ReplenishmentProof publisher) {
    if (!input || !mutable() || additional <= 0 || stock.space() == 0) {
      return 0;
    }
    pruneDemands();
    if (demands.size() >= 64 && !demands.containsKey(key)) {
      return 0;
    }
    var current = demands.get(key);
    long promised = current == null ? 0 : Math.max(0, current.target() - stock.amount(key));
    long accepted = Math.min(additional, unpromisedSpace());
    if (accepted <= 0) {
      return 0;
    }
    long target = stock.amount(key) + promised + accepted;
    demands.put(key, new Demand(target, level.getGameTime() + 60));
    requestObservers.changed(key, publisher);
    return accepted;
  }

  private long unpromisedSpace() {
    long free = stock.space();
    for (var key : demands.keySet()) {
      free -= deficit(key);
    }
    return Math.max(0, free);
  }

  protected final ResourceLocation requested() {
    pruneDemands();
    return demands.keySet().stream().filter(key -> deficit(key) > 0).findFirst().orElse(null);
  }

  protected final int deficit(ResourceLocation key) {
    var demand = demands.get(key);
    return demand == null || demand.expires() < level.getGameTime() ? 0 : (int) Math
        .clamp(demand.target() - stock.amount(key), 0, stock.space());
  }

  protected final void rotateDemand(ResourceLocation key) {
    var demand = demands.remove(key);
    if (demand != null && stock.amount(key) < demand.target()) {
      demands.put(key, demand);
    }
  }

  private void pruneDemands() {
    demands.values().removeIf(demand -> demand.expires() < level.getGameTime());
  }

  @Override
  public RecipeResourceParticipant acquire(ResourceLocation key, long delta) {
    if (!live() || RecipeLifecyclePublisher.isDispatching() || transferring || (input ? delta >= 0 : delta <= 0)) {
      throw new IllegalStateException("Essentia hatch cannot acquire this resource change");
    }
    var lease = stock.acquire(key, delta);
    reservations++;
    return commit -> {
      try {
        lease.resolve(commit);
      } finally {
        reservations--;
      }
    };
  }

  protected final boolean live() {
    if (!(level instanceof ServerLevel server)) {
      return false;
    }
    ServerThreadGuard.require(server, "Essentia hatch access");
    return !isRemoved() && server.hasChunkAt(worldPosition) && server.getBlockEntity(worldPosition) == this;
  }

  protected final boolean mutable() {
    return live() && reservations == 0 && !transferring && !RecipeLifecyclePublisher.isDispatching();
  }

  protected final Holder<IAspect> aspect(ResourceLocation key) {
    return resolveAspect(level.registryAccess(), key);
  }

  void dropContents() {
    for (var key : stock.keys()) {
      var aspect = aspect(key);
      if (aspect == null) {
        continue;
      }
      long remainder = -stock.move(key, -stock.amount(key), false);
      while (remainder > 0) {
        int amount = (int) Math.min(PhialItem.BASE_AMOUNT, remainder);
        var stack = PhialItem.makeFilled(aspect, amount);
        int count = amount == PhialItem.BASE_AMOUNT ? (int) Math.min(stack.getMaxStackSize(), remainder / amount) : 1;
        stack.setCount(count);
        Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
        remainder -= (long) count * amount;
      }
    }
  }

  void recoverContents() {
    if (mutable()) {
      dropContents();
      clearDemands();
      setChanged();
    }
  }

  private void clearDemands() {
    demands.clear();
    requestObservers.invalidate();
  }

  public static Holder<IAspect> resolveAspect(HolderLookup.Provider registries, ResourceLocation key) {
    return registries.lookup(IAspect.REGISTRY_KEY)
        .flatMap(lookup -> lookup.get(ResourceKey.create(IAspect.REGISTRY_KEY, key)))
        .map(holder -> (Holder<IAspect>) holder).orElse(null);
  }

  protected static ResourceLocation identity(Holder<IAspect> aspect) {
    return aspect == null ? null : aspect.unwrapKey().map(ResourceKey::location).orElse(null);
  }

  @Override
  public void publish() {
    setChanged();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    tag.put("essentia", stock.save());
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Essentia hatch load");
      if (transferring || reservations != 0 || RecipeLifecyclePublisher.isDispatching()) {
        throw new IllegalStateException("Cannot replace reserved essentia stock");
      }
    }
    super.loadAdditional(tag, registries);
    stock.load(tag.getCompound("essentia"));
    clearDemands();
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = new CompoundTag();
    MachineColorable.save(this, tag, machineColor());
    return tag;
  }

  private record Demand(long target, long expires) {
  }
}
