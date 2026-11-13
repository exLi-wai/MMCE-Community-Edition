package com.lw.modularmachine.machine.network.pattern;

import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Internal optional-platform boundary. Mirrors never create a connection. */
@ApiStatus.Internal
public final class PatternNetworkPlatform {
  private static Adapter adapter;

  private PatternNetworkPlatform() {
  }

  public interface Adapter {
    Connection create(PatternProviderBlockEntity provider);
  }

  public interface Connection {
    void onLoad();

    void destroy();

    default void onChunkUnloaded() {
      destroy();
    }

    void load(CompoundTag tag, HolderLookup.Provider registries);

    void save(CompoundTag tag, HolderLookup.Provider registries);

    void setOwningPlayer(Player player);

    boolean active();

    void patternsChanged();

    Optional<PatternRecipeDefinition> pattern(ItemStack stack);

    void networkStep();
  }

  public static void install(Adapter value) {
    if (adapter != null) {
      throw new IllegalStateException("Pattern network platform already installed");
    }
    adapter = Objects.requireNonNull(value);
  }

  public static Connection create(PatternProviderBlockEntity provider) {
    return adapter == null ? Unavailable.INSTANCE : adapter.create(provider);
  }

  private enum Unavailable implements Connection {
    INSTANCE;

    @Override
    public void onLoad() {
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
    public void patternsChanged() {
    }

    @Override
    public Optional<PatternRecipeDefinition> pattern(ItemStack stack) {
      return Optional.empty();
    }

    @Override
    public void networkStep() {
    }
  }
}
