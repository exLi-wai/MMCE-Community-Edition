package com.lw.modularmachine.integration.thaumaturge;

import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import com.leclowndu93150.thaumaturge.content.essentia.item.ComponentEssentia;
import com.leclowndu93150.thaumaturge.content.item.PhialItem;
import com.leclowndu93150.thaumaturge.registry.TTSounds;
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
import java.util.function.ObjLongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public abstract class EssentiaHatch extends MachineCasingBlockEntity implements TickingRecipeHatchBlock.ServerTicking, KeyedResourcePort {
  protected static final int CAPACITY = 4096;
  protected final KeyedStock stock;
  protected final boolean input;
  protected boolean transferring;
  private int reservations;
  private final Map<ResourceLocation, Demand> demands = new LinkedHashMap<>();
  private final KeyedRequestObservers requestObservers = new KeyedRequestObservers();

  protected EssentiaHatch(BlockEntityType<?> type, BlockPos position, BlockState state, boolean input) {
    this(type, position, state, input, CAPACITY);
  }

  protected EssentiaHatch(BlockEntityType<?> type, BlockPos position, BlockState state, boolean input, int capacity) {
    super(type, position, state);
    this.input = input;
    stock = new KeyedStock(capacity);
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
    return input && mutable() ? unpromisedSpace() : 0;
  }

  @Override
  public final boolean canRequest(ResourceLocation key) {
    return input && mutable() && (externalOfferExpiry(key) >= level.getGameTime() || hasRequestSource());
  }

  protected long externalOfferExpiry(ResourceLocation key) {
    return Long.MIN_VALUE;
  }

  protected abstract boolean hasRequestSource();

  protected boolean observedRequestSource(ReplenishmentProof proof) {
    boolean connected = hasRequestSource();
    proof.observeConnection(this, connected, this::hasRequestSource);
    return connected;
  }

  protected final void invalidateRequestProofs() {
    requestObservers.invalidate();
  }

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
    if (proof != null) {
      proof.observeExpiry(demands.get(key).expires(), level::getGameTime);
    }
    long offerExpiry = externalOfferExpiry(key);
    if (offerExpiry >= level.getGameTime()) {
      if (proof != null) {
        proof.observeExpiry(offerExpiry, level::getGameTime);
      }
      return pending;
    }
    boolean connected = proof == null ? hasRequestSource() : observedRequestSource(proof);
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
    long accepted = Math.min(additional, unpromisedSpace());
    if (accepted <= 0) {
      return 0;
    }
    demands.put(key, new Demand(deficit(key) + accepted, level.getGameTime() + 60));
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
    if (demand == null || demand.expires() < level.getGameTime()) {
      return 0;
    }
    return (int) Math.min(demand.remaining(), stock.space());
  }

  /**
   * Only actual deliveries retire outstanding demand. Paying from the buffer
   * cannot reopen it.
   */
  protected final long moveLocalStock(ResourceLocation key, long delta, boolean simulate) {
    long moved = stock.move(key, delta, simulate);
    if (!simulate && moved > 0 && input) {
      var demand = demands.get(key);
      if (demand != null) {
        long remaining = demand.remaining() - moved;
        if (remaining <= 0 || demand.expires() < level.getGameTime()) {
          demands.remove(key);
        } else {
          demands.put(key, new Demand(remaining, demand.expires()));
        }
        requestObservers.changed(key, null);
      }
    }
    return moved;
  }

  protected final void rotateDemand(ResourceLocation key) {
    var demand = demands.remove(key);
    if (demand != null) {
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

  /**
   * Server-thread view of committed contents. A false result means the inventory
   * is unavailable.
   */
  boolean visitStoredAspects(ObjLongConsumer<Holder<IAspect>> visitor) {
    if (!mutable()) {
      return false;
    }
    for (var key : stock.keys()) {
      var type = aspect(key);
      if (type != null) {
        visitor.accept(type, stock.amount(key));
      }
    }
    return true;
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

  boolean transferPhial(ItemStack held, Player player, InteractionHand hand) {
    if (!mutable()) {
      return false;
    }
    var result = held.copyWithCount(1);
    var container = ComponentEssentia.phial(result);
    var contents = container.getAspects();
    ResourceLocation key;
    int delta;
    if (contents.isEmpty()) {
      var keys = stock.keys();
      if (keys.isEmpty()) {
        return false;
      }
      key = keys.getFirst();
      var aspect = aspect(key);
      if (aspect == null) {
        return false;
      }
      delta = -(int) Math.min(PhialItem.BASE_AMOUNT, stock.amount(key));
      container.setAspects(contents.add(aspect, -delta));
    } else {
      if (!input || contents.size() != 1) {
        return false;
      }
      var entry = contents.entries().getFirst();
      key = identity(entry.aspect());
      if (key == null) {
        return false;
      }
      // Manual stocking may use free space and promises for this aspect only.
      delta = (int) Math.min(entry.amount(), unpromisedSpace() + deficit(key));
      if (delta <= 0) {
        return false;
      }
      container.setAspects(contents.remove(entry.aspect(), delta));
    }
    transferring = true;
    try {
      if ((delta < 0 || !player.hasInfiniteMaterials()) && !exchangePhial(player, hand, held, result)) {
        return false;
      }
      moveLocalStock(key, delta, false);
      setChanged();
      level.playSound(null, worldPosition, TTSounds.JAR.get(), SoundSource.BLOCKS, 0.25F, 1);
    } finally {
      transferring = false;
    }
    return true;
  }

  private static boolean exchangePhial(Player player, InteractionHand hand, ItemStack held, ItemStack result) {
    if (held.getCount() == 1 && !player.hasInfiniteMaterials()) {
      player.setItemInHand(hand, result);
      return true;
    }
    // Inventory.add can discard overflow in creative. Reserve a real destination
    // for this one bottle before consuming its source or settling the hatch.
    var inventory = player.getInventory();
    int slot = inventory.getSlotWithRemainingSpace(result);
    if (slot < 0) {
      slot = inventory.getFreeSlot();
    }
    if (slot < 0 || !inventory.add(slot, result)) {
      return false;
    }
    held.consume(1, player);
    return true;
  }

  protected void clearDemands() {
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
    stock.load(tag.getCompound("essentia"), savedAmountLimit());
    clearDemands();
  }

  protected int savedAmountLimit() {
    return CAPACITY;
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = new CompoundTag();
    MachineColorable.save(this, tag, machineColor());
    return tag;
  }

  private record Demand(long remaining, long expires) {
  }
}
