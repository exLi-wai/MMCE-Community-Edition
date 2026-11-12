package com.lw.modularmachine.machine.network;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.blockentity.NetworkPortBlockEntity;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal platform boundary. Recipes only consume the port's physical buffers.
 */
@ApiStatus.Internal
public final class MachineNetworkPlatform {
  private static Adapter adapter;

  private MachineNetworkPlatform() {
  }

  public interface Adapter {
    Connection create(NetworkPortBlockEntity port);

    boolean chemicalAvailable();
  }

  /**
   * Main-thread network access. The caller owns the local buffer lease and must
   * commit the actual returned quantity before leaving that lease.
   */
  public interface Connection {
    void onLoad();

    void onChunkUnloaded();

    void destroy();

    void load(CompoundTag tag, HolderLookup.Provider registries);

    void save(CompoundTag tag, HolderLookup.Provider registries);

    void setOwningPlayer(Player player);

    boolean active();

    long extractItem(ItemStack prototype, long maximum);

    long insertItem(ItemStack prototype, long maximum);

    long extractFluid(FluidStack prototype, long maximum);

    long insertFluid(FluidStack prototype, long maximum);

    long extractChemical(ChemicalStackData prototype, long maximum);

    long insertChemical(ChemicalStackData prototype, long maximum);
  }

  public static void install(Adapter value) {
    if (adapter != null) {
      throw new IllegalStateException("Machine network platform already installed");
    }
    adapter = Objects.requireNonNull(value);
  }

  public static boolean available() {
    return adapter != null;
  }

  public static boolean chemicalAvailable() {
    return adapter != null && adapter.chemicalAvailable();
  }

  public static Connection create(NetworkPortBlockEntity port) {
    return adapter == null ? UnavailableConnection.INSTANCE : adapter.create(port);
  }

  private enum UnavailableConnection implements Connection {
    INSTANCE;

    @Override
    public void onLoad() {
    }

    @Override
    public void onChunkUnloaded() {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
    }

    @Override
    public void save(CompoundTag tag, HolderLookup.Provider registries) {
    }

    @Override
    public void setOwningPlayer(Player player) {
    }

    @Override
    public boolean active() {
      return false;
    }

    @Override
    public long extractItem(ItemStack prototype, long maximum) {
      return 0;
    }

    @Override
    public long insertItem(ItemStack prototype, long maximum) {
      return 0;
    }

    @Override
    public long extractFluid(FluidStack prototype, long maximum) {
      return 0;
    }

    @Override
    public long insertFluid(FluidStack prototype, long maximum) {
      return 0;
    }

    @Override
    public long extractChemical(ChemicalStackData prototype, long maximum) {
      return 0;
    }

    @Override
    public long insertChemical(ChemicalStackData prototype, long maximum) {
      return 0;
    }
  }
}
