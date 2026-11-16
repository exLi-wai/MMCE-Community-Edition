package com.lw.modularmachine.util.minecraft.state;

import java.util.Map;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal typed property application to immutable states; no world or registry
 * access.
 */
@ApiStatus.Internal
public final class BlockStateValues {
  private BlockStateValues() {
  }

  /**
   * Keeps platform validation for missing properties, wrong value types and
   * disallowed values.
   */
  public static BlockState apply(BlockState state, Map<? extends Property<?>, ? extends Comparable<?>> properties) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(properties, "properties");
    for (var property : properties.entrySet()) {
      state = withValue(state, property.getKey(), property.getValue());
    }

    return state;
  }

  /**
   * Matches alternatives and only their named constraints, without world access.
   */
  public static boolean matchesAny(List<BlockState> states, List<String> properties, BlockState actual) {
    for (var state : states) {
      if (state.getBlock() != actual.getBlock()) {
        continue;
      }
      boolean matches = true;
      for (String name : properties) {
        var property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null || !state.getValue(property).equals(actual.getValue(property))) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return true;
      }
    }
    return false;
  }

  private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, Comparable<?> value) {
    return state.setValue(property, property.getValueClass().cast(value));
  }
}
