package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Detached output slot images and their shared capacity domain. Nothing is
 * inserted during planning.
 */
@ApiStatus.Internal
public final class ItemOutputLayout {
  private record Slot(String part, int index, ItemStack before, ItemSlotDomain domain) {
  }

  private final List<Slot> slots;
  private final Map<String, List<ItemStack>> images;
  private final ResourceSnapshot<ItemResourceKey> snapshot;

  public ItemOutputLayout(RecipeExecutionTicket ticket, Set<ItemResourceKey> keys) {
    var refs = new ArrayList<Slot>();
    var spaces = new ArrayList<ResourceSnapshot.OutputSlot<ItemResourceKey>>();
    var copied = new HashMap<String, List<ItemStack>>();
    var limitsBySize = new HashMap<ItemSlotDomain, Map<ItemResourceKey, Long>>();
    for (var port : ticket.ports()) {
      var items = port.items();
      copied.put(port.partId(), items);
      for (int i = 0; i < items.size(); i++) {
        var stack = items.get(i);
        int limit = port.slotLimits().get(i);
        var domain = new ItemSlotDomain(limit, port.selector(), port.bulkItems());
        var limits = limitsBySize.computeIfAbsent(domain, current -> current.limits(keys));
        refs.add(new Slot(port.partId(), i, stack, domain));
        spaces.add(
            new ResourceSnapshot.OutputSlot<>(
                stack.isEmpty() ? null : ItemResourceKey.of(stack).withSelector(port.selector()),
                stack.getCount(),
                limit,
                limits));
      }
    }
    slots = List.copyOf(refs);
    images = Map.copyOf(copied);
    snapshot = new ResourceSnapshot<>(ticket.structureRevision(), Map.of(), spaces);
  }

  public ResourceSnapshot<ItemResourceKey> snapshot() {
    return snapshot;
  }

  public Map<String, NonNullList<ItemStack>> prepare(RecipePlan<ItemResourceKey> placement) {
    var changed = new HashMap<String, NonNullList<ItemStack>>();
    var used = new HashSet<Integer>();
    for (var insertion : placement.insertions()) {
      if (!used.add(insertion.slot())) {
        throw new IllegalArgumentException("Output slot claimed twice");
      }
      var slot = slots.get(insertion.slot());
      var contents = changed.computeIfAbsent(slot.part(), part -> {
        var image = images.get(part);
        var result = NonNullList.withSize(image.size(), ItemStack.EMPTY);
        for (int i = 0; i < image.size(); i++) {
          result.set(i, image.get(i));
        }
        return result;
      });
      int amount = Math.toIntExact(insertion.count());
      int total = Math.addExact(slot.before().getCount(), amount);
      if (amount <= 0 || total > slot.domain().capacityFor(insertion.key())) {
        throw new IllegalStateException("Output stack limit exceeded");
      }
      if (slot.before().isEmpty()) {
        contents.set(slot.index(), insertion.key().stack(1).copyWithCount(total));
      } else {
        if (!insertion.key().matches(slot.before())) {
          throw new IllegalStateException("Output component mismatch");
        }
        contents.set(slot.index(), slot.before().copyWithCount(total));
      }
    }
    return Map.copyOf(changed);
  }
}
