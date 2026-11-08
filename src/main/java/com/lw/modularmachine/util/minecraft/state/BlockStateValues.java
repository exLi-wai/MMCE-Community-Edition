package com.lw.modularmachine.util.minecraft.state;

import java.util.Map;
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

  private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, Comparable<?> value) {
    return state.setValue(property, property.getValueClass().cast(value));
  }
}
