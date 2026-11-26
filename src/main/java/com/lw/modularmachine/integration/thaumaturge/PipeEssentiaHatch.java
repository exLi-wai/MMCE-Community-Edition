package com.lw.modularmachine.integration.thaumaturge;

import com.leclowndu93150.thaumaturge.api.aspect.AspectList;
import com.leclowndu93150.thaumaturge.api.aspect.IAspect;
import com.leclowndu93150.thaumaturge.api.aspect.IAspectSource;
import com.leclowndu93150.thaumaturge.api.essentia.EssentiaCapabilities;
import com.leclowndu93150.thaumaturge.api.essentia.EssentiaPull;
import com.leclowndu93150.thaumaturge.api.essentia.IEssentiaConduit;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class PipeEssentiaHatch extends EssentiaHatch implements IEssentiaConduit, IAspectSource {
  private static final int SUCTION = 64;
  // Transfusers retry an unsuccessful remote delivery after 200 ticks.
  private static final int REMOTE_OFFER_TICKS = 240;
  private static final int MAX_REMOTE_OFFERS = 64;
  private final Map<ResourceLocation, Long> remoteOffers = new HashMap<>();

  public PipeEssentiaHatch(BlockPos position, BlockState state) {
    this(position, state, state.is(MachineRegistries.ESSENTIA_INPUT.get()));
  }

  private PipeEssentiaHatch(BlockPos position, BlockState state, boolean input) {
    super(MachineRegistries.PIPE_ESSENTIA_ENTITY.get(), position, state, input,
        input ? CAPACITY : MachineConfig.essentiaOutputCapacity());
  }

  @Override
  protected int savedAmountLimit() {
    return input ? CAPACITY : MachineConfig.MAX_ESSENTIA_OUTPUT_CAPACITY;
  }

  @Override
  protected boolean hasRequestSource() {
    for (var direction : Direction.values()) {
      if (supplyFrom(direction) != null) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected long externalOfferExpiry(ResourceLocation key) {
    return remoteOffers.getOrDefault(key, Long.MIN_VALUE);
  }

  @Override
  public long requestSupply(ResourceLocation key, long maximum) {
    if (maximum <= 0 || !input || !mutable()) {
      return 0;
    }
    var aspect = aspect(key);
    if (aspect == null) {
      return 0;
    }
    int limit = (int) Math.min(maximum, CAPACITY);
    int found = 0;
    for (var direction : Direction.values()) {
      var pipe = supplyFrom(direction);
      if (pipe != null) {
        int remaining = limit - found;
        found += Math.clamp(pipe.extract(aspect, remaining, direction.getOpposite(), true), 0, remaining);
        if (found == limit) {
          break;
        }
      }
    }
    return found;
  }

  private IEssentiaConduit supplyFrom(Direction direction) {
    var adjacent = worldPosition.relative(direction);
    if (!level.hasChunkAt(adjacent)) {
      return null;
    }
    var facing = direction.getOpposite();
    var pipe = level.getCapability(EssentiaCapabilities.CONDUIT, adjacent, facing);
    return pipe != null
        && pipe.connectsOn(facing)
        && pipe.releasesOn(facing)
        && pipe.pullThreshold() <= SUCTION ? pipe : null;
  }

  @Override
  public void tickHatch() {
    if (!input || !mutable() || Math.floorMod(level.getGameTime() + worldPosition.asLong(), 10) != 0) {
      return;
    }
    var key = requested();
    if (key == null) {
      return;
    }
    var aspect = aspect(key);
    if (aspect != null) {
      int maximum = Math.min(8, deficit(key));
      for (var direction : Direction.values()) {
        if (maximum == 0) {
          break;
        }
        var pipe = supplyFrom(direction);
        if (pipe == null) {
          continue;
        }
        transferring = true;
        try {
          int extracted = pipe.extract(aspect, maximum, direction.getOpposite(), false);
          if (extracted < 0 || extracted > maximum || moveLocalStock(key, extracted, false) != extracted) {
            throw new IllegalStateException("Invalid essentia pipe transfer amount");
          }
          if (extracted > 0) {
            maximum -= extracted;
            setChanged();
          }
        } finally {
          transferring = false;
        }
      }
    }
    rotateDemand(key);
  }

  @Override
  public int insert(Holder<IAspect> aspect, int amount, Direction side, boolean simulate) {
    var key = identity(aspect);
    if (!input || !mutable() || key == null || amount <= 0) {
      return 0;
    }
    int moved = (int) moveLocalStock(key, Math.min(amount, deficit(key)), simulate);
    if (!simulate && moved > 0) {
      setChanged();
    }
    return moved;
  }

  @Override
  public int roomFor(Holder<IAspect> aspect, Direction side) {
    return insert(aspect, Integer.MAX_VALUE, side, true);
  }

  @Override
  public int extract(Holder<IAspect> aspect, int amount, Direction side, boolean simulate) {
    var key = identity(aspect);
    if (input || !mutable() || key == null || amount <= 0) {
      return 0;
    }
    int moved = (int) -stock.move(key, -amount, simulate);
    if (!simulate && moved > 0) {
      setChanged();
    }
    return moved;
  }

  @Override
  public Holder<IAspect> heldAspect(Direction side) {
    return !input && live() && !stock.keys().isEmpty() ? aspect(stock.keys().getFirst()) : null;
  }

  @Override
  public int heldAmount(Direction side) {
    var key = identity(heldAspect(side));
    return key == null ? 0 : (int) stock.amount(key);
  }

  @Override
  public EssentiaPull pull(Direction side) {
    var key = input && mutable() ? requested() : null;
    var aspect = key == null ? null : aspect(key);
    return aspect == null ? EssentiaPull.NONE : EssentiaPull.of(aspect, SUCTION);
  }

  @Override
  public int pullThreshold() {
    return 0;
  }

  @Override
  public boolean releasesOn(Direction side) {
    return !input;
  }

  @Override
  public boolean receivesOn(Direction side) {
    return input;
  }

  @Override
  public boolean connectsOn(Direction side) {
    return true;
  }

  @Override
  public AspectList getAspects() {
    var contents = AspectList.EMPTY;
    if (mutable()) {
      for (var key : stock.keys()) {
        var aspect = aspect(key);
        if (aspect != null) {
          contents = contents.add(aspect, (int) stock.amount(key));
        }
      }
    }
    return contents;
  }

  @Override
  public void setAspects(AspectList aspects) {
    // Like Thaumaturge's essentia mirror, this view only allows transfers through
    // fill and drain.
  }

  @Override
  public boolean isBlocked() {
    return !mutable();
  }

  @Override
  public boolean accepts(Holder<IAspect> aspect) {
    return input && mutable() && stock.space() > 0 && identity(aspect) != null;
  }

  @Override
  public int fill(Holder<IAspect> aspect, int amount) {
    if (amount <= 0 || !accepts(aspect)) {
      return amount;
    }
    // A remote transfuser has no adjacent conduit to discover. Only a real delivery
    // attempt identifies it as a possible source for this aspect.
    var key = identity(aspect);
    long now = level.getGameTime();
    if (remoteOffers.size() >= MAX_REMOTE_OFFERS && !remoteOffers.containsKey(key)) {
      remoteOffers.values().removeIf(expires -> expires < now);
    }
    if (remoteOffers.size() < MAX_REMOTE_OFFERS || remoteOffers.containsKey(key)) {
      Long previous = remoteOffers.put(key, now + REMOTE_OFFER_TICKS);
      if (previous == null || previous < now) {
        invalidateRequestProofs();
      }
    }
    return amount - insert(aspect, amount, null, false);
  }

  @Override
  public boolean drain(Holder<IAspect> aspect, int amount) {
    return amount > 0
        && extract(aspect, amount, null, true) == amount
        && extract(aspect, amount, null, false) == amount;
  }

  @Override
  protected void clearDemands() {
    super.clearDemands();
    remoteOffers.clear();
  }

  @Override
  public void setRemoved() {
    remoteOffers.clear();
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    remoteOffers.clear();
    super.onChunkUnloaded();
  }
}
