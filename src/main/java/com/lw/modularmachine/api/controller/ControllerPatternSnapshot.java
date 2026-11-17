package com.lw.modularmachine.api.controller;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/** Detached non-air parts of one confirmed structure generation. */
public final class ControllerPatternSnapshot {
  private final ResourceLocation machine;
  private final long generation;
  private final List<Part> parts;

  private ControllerPatternSnapshot(ResourceLocation machine, long generation, List<Part> parts) {
    this.machine = Objects.requireNonNull(machine, "machine");
    this.generation = generation;
    this.parts = Collections.unmodifiableList(parts);
  }

  /**
   * Exclusive capture builder; finishing transfers its private list and seals it.
   */
  @ApiStatus.Internal
  public static final class Capture {
    private List<Part> parts = new ArrayList<>();

    public void add(BlockPos position, BlockState state) {
      requireOpen();
      parts.add(new Part(position, state));
    }

    public ControllerPatternSnapshot finish(ResourceLocation machine, long generation) {
      requireOpen();
      var result = new ControllerPatternSnapshot(machine, generation, parts);
      parts = null;

      return result;
    }

    private void requireOpen() {
      if (parts == null) {
        throw new IllegalStateException("Controller pattern capture is sealed");
      }
    }
  }

  public ResourceLocation machine() {
    return machine;
  }

  public long generation() {
    return generation;
  }

  public List<Part> parts() {
    return parts;
  }

  public record Part(BlockPos position, BlockState state) {
    public Part {
      position = position.immutable();
      Objects.requireNonNull(state, "state");
      if (state.isAir()) {
        throw new IllegalArgumentException("Air is not a structure query result");
      }
    }
  }

  public int getBlocksInPattern(String block) {
    return getBlocksInPattern(blockMatcher(block));
  }

  public int getBlocksInPattern(ItemStack stack) {
    var expected = Block.byItem(Objects.requireNonNull(stack, "stack").getItem());

    return getBlocksInPattern(state -> state.is(expected));
  }

  public int getBlocksInPattern(BlockState state) {
    Objects.requireNonNull(state, "state");

    return getBlocksInPattern(actual -> actual == state);
  }

  public int getBlocksInPattern(Predicate<BlockState> matcher) {
    Objects.requireNonNull(matcher, "matcher");

    return Math.toIntExact(parts.stream().filter(part -> matcher.test(part.state())).count());
  }

  public List<BlockPos> getBlockPosInPattern(String block) {
    return getBlockPosInPattern(blockMatcher(block));
  }

  private static Predicate<BlockState> blockMatcher(String block) {
    var id = ResourceLocation.parse(block);
    if (!BuiltInRegistries.BLOCK.containsKey(id)) {
      throw new IllegalArgumentException("Unknown block: " + id);
    }
    var expected = BuiltInRegistries.BLOCK.get(id);

    return state -> state.is(expected);
  }

  public List<BlockPos> getBlockPosInPattern(ItemStack stack) {
    var expected = Block.byItem(Objects.requireNonNull(stack, "stack").getItem());

    return getBlockPosInPattern(state -> state.is(expected));
  }

  public List<BlockPos> getBlockPosInPattern(BlockState state) {
    Objects.requireNonNull(state, "state");

    return getBlockPosInPattern(actual -> actual == state);
  }

  public List<BlockPos> getBlockPosInPattern(Predicate<BlockState> matcher) {
    Objects.requireNonNull(matcher, "matcher");

    return parts.stream().filter(part -> matcher.test(part.state())).map(Part::position).toList();
  }
}
