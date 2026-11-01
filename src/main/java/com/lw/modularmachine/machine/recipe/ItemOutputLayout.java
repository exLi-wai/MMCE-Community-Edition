package com.lw.modularmachine.machine.recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/**
 * Detached output slot images and their shared capacity domain. Nothing is
 * inserted during planning.
 */
final class ItemOutputLayout {
  private record Slot(String part, int index, ItemStack before) {
  }

  private final List<Slot> slots;
  private final Map<String, List<ItemStack>> images;
  final ItemResourceSnapshot<ItemResourceKey> snapshot;

  ItemOutputLayout(RecipeExecutionTicket ticket, Set<ItemResourceKey> keys) {
    var refs = new ArrayList<Slot>();
    var spaces = new ArrayList<ItemResourceSnapshot.OutputSlot<ItemResourceKey>>();
    var copied = new HashMap<String, List<ItemStack>>();
    var limitsBySize = new HashMap<ItemSlotDomain, Map<ItemResourceKey, Long>>();
    for (var port : ticket.ports()) {
      var items = port.items();
      copied.put(port.partId(), items);
      for (int i = 0; i < items.size(); i++) {
        var stack = items.get(i);
        int limit = port.slotLimits().get(i);
        var domain = new ItemSlotDomain(limit, port.selector());
        var limits = limitsBySize.computeIfAbsent(domain, current -> current.limits(keys));
        refs.add(new Slot(port.partId(), i, stack));
        spaces.add(
            new ItemResourceSnapshot.OutputSlot<>(
                stack.isEmpty() ? null : ItemResourceKey.of(stack).withSelector(port.selector()),
                stack.getCount(),
                limit,
                limits));
      }
    }
    slots = List.copyOf(refs);
    images = Map.copyOf(copied);
    snapshot = new ItemResourceSnapshot<>(ticket.structureRevision(), Map.of(), spaces);
  }

  Map<String, NonNullList<ItemStack>> prepare(RecipePlan<ItemResourceKey> placement) {
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
      if (slot.before().isEmpty()) {
        contents.set(slot.index(), insertion.key().stack(amount));
      } else {
        if (!insertion.key().matches(slot.before())) {
          throw new IllegalStateException("Output component mismatch");
        }
        int total = Math.addExact(slot.before().getCount(), amount);
        if (total > insertion.key().maxStackSize()) {
          throw new IllegalStateException("Output stack limit exceeded");
        }
        contents.set(slot.index(), slot.before().copyWithCount(total));
      }
    }
    return Map.copyOf(changed);
  }
}
