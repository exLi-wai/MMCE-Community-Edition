package com.lw.modularmachine.util.minecraft.placement;

import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal Minecraft placement eligibility and candidate queries. No world,
 * inventory or fluid handler is read or changed.
 */
@ApiStatus.Internal
public final class PlacementMaterials {
  @ApiStatus.Internal
  public record Choices(List<Item> items, List<Fluid> fluids) {
    public Choices {
      items = List.copyOf(items);
      fluids = List.copyOf(fluids);
    }
  }

  private PlacementMaterials() {
  }

  public static boolean automaticItem(Item item) {
    if (item.getClass() != BlockItem.class) {
      return false;
    }
    var block = ((BlockItem) item).getBlock();
    return !(block instanceof BedBlock || block instanceof DoorBlock || block instanceof DoublePlantBlock);
  }

  public static List<Fluid> sourceFluids(List<BlockState> states) {
    return states.stream().filter(state -> state.getBlock() instanceof LiquidBlock).map(BlockState::getFluidState)
        .filter(state -> !state.isEmpty() && state.isSource()).map(state -> state.getType()).distinct().toList();
  }

  public static boolean canReplace(List<BlockState> states, BlockState actual) {
    if (actual.isAir()) {
      return true;
    }
    var flow = actual.getFluidState();
    return actual.getBlock() instanceof LiquidBlock
        && !actual.hasBlockEntity()
        && !flow.isEmpty()
        && !flow.isSource()
        && sourceFluids(states).stream().anyMatch(fluid -> fluid.isSame(flow.getType()));
  }

  /**
   * Returns deduplicated candidates in registry-ID order; registries must be
   * available.
   */
  public static Choices choices(List<BlockState> states) {
    var items = states.stream().map(state -> state.getBlock().asItem()).filter(PlacementMaterials::automaticItem)
        .distinct().sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).toList();
    var fluids = sourceFluids(states).stream()
        .sorted(Comparator.comparing(fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString())).toList();
    return new Choices(items, fluids);
  }
}
