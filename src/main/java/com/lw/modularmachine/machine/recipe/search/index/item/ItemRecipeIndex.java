package com.lw.modularmachine.machine.recipe.search.index.item;

import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.search.CompiledRecipeSet;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputIndex;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Item matching adaptation for one compiled catalog. Logical aliases only
 * select candidates; the search ticket retains exact physical keys for actual
 * deductions.
 */
@ApiStatus.Internal
public final class ItemRecipeIndex {
  private final RequiredInputIndex<ItemResourceKey> inputs;
  private final Set<ItemResourceKey> aliases;

  public ItemRecipeIndex(CompiledRecipeSet<ItemResourceKey> recipes) {
    inputs = recipes.inputIndex();
    var collected = new HashSet<ItemResourceKey>();
    for (var candidate : recipes.candidates()) {
      for (var input : candidate.inputs()) {
        if (input.key().ignoresComponents()) {
          if (candidate.inputs().size() != 1) {
            throw new IllegalArgumentException("Item-only matching requires a single item input row");
          }
          collected.add(input.key());
        }
      }
    }
    aliases = Set.copyOf(collected);
  }

  /**
   * Reuses the generic planner's immutable index without compiling a second copy.
   */
  public RequiredInputIndex<ItemResourceKey> inputs() {
    return inputs;
  }

  /** The captured item view includes these aliases alongside exact item keys. */
  public Set<ItemResourceKey> aliases() {
    return aliases;
  }
}
