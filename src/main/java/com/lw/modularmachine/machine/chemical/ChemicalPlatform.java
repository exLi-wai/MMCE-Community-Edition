package com.lw.modularmachine.machine.chemical;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.component.ChemicalStackData;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal startup-installed platform boundary; core resources do not link
 * optional platform types.
 */
@ApiStatus.Internal
public final class ChemicalPlatform {
  private static Adapter adapter;

  private ChemicalPlatform() {
  }

  public interface Adapter {
    boolean contains(ResourceLocation chemical);

    boolean hasContainer(ItemStack stack);

    boolean interact(Player player, InteractionHand hand, MachinePortBlockEntity port);

    default ChemicalStackData sampleContainer(ItemStack stack) {
      return ChemicalStackData.EMPTY;
    }
  }

  public static void install(Adapter value) {
    if (adapter != null) {
      throw new IllegalStateException("Chemical platform already installed");
    }
    adapter = Objects.requireNonNull(value);
  }

  public static boolean available() {
    return adapter != null;
  }

  public static ChemicalStackData sampleContainer(ItemStack stack) {
    return adapter == null ? ChemicalStackData.EMPTY : adapter.sampleContainer(stack.copy());
  }

  public static boolean contains(ResourceLocation chemical) {
    return adapter != null && adapter.contains(chemical);
  }

  public static boolean hasContainer(ItemStack stack) {
    return adapter != null && adapter.hasContainer(stack);
  }

  public static boolean interact(Player player, InteractionHand hand, MachinePortBlockEntity port) {
    return adapter != null && adapter.interact(player, hand, port);
  }
}
