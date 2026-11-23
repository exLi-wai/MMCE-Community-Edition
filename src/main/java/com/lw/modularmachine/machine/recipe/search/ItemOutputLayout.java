package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.RecipePortSnapshot;
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
 * Detached item-output slot images and their shared capacity domain. Full
 * resource tickets are accepted, but input storage never contributes space.
 * Nothing is inserted during planning.
 */
@ApiStatus.Internal
public final class ItemOutputLayout {
  private record Slot(RecipePortSnapshot port, int index, ItemSlotDomain domain) {
  }

  private final List<Slot> slots;
  private final ResourceSnapshot<ItemResourceKey> snapshot;

  public ItemOutputLayout(RecipeExecutionTicket ticket, Set<ItemResourceKey> keys) {
    var refs = new ArrayList<Slot>();
    var spaces = new ArrayList<ResourceSnapshot.OutputSlot<ItemResourceKey>>();
    var keysSnapshot = Set.copyOf(keys);
    var limitsBySize = new HashMap<ItemSlotDomain, ResourceSnapshot.SlotLimit<ItemResourceKey>>();
    for (var port : ticket.ports()) {
      if (port.kind() != PortKind.ITEM_OUTPUT) {
        continue;
      }
      for (int i = 0; i < port.itemSlotCount(); i++) {
        int limit = port.slotLimits().get(i);
        var domain = new ItemSlotDomain(limit, port.selector(), port.bulkItems());
        var limits = limitsBySize.computeIfAbsent(domain, current -> current.outputLimits(keysSnapshot));
        refs.add(new Slot(port, i, domain));
        spaces.add(new ResourceSnapshot.OutputSlot<>(port.itemKey(i), port.itemAmount(i), limit, limits));
      }
    }
    slots = List.copyOf(refs);
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
      var contents = changed.computeIfAbsent(slot.port().partId(), part -> slot.port().copyItemContents());
      var before = contents.get(slot.index());
      int amount = Math.toIntExact(insertion.count());
      int total = Math.addExact(before.getCount(), amount);
      if (amount <= 0 || total > slot.domain().capacityFor(insertion.key())) {
        throw new IllegalStateException("Output stack limit exceeded");
      }
      if (before.isEmpty()) {
        contents.set(slot.index(), insertion.key().stack(1).copyWithCount(total));
      } else {
        if (!insertion.key().matches(before)) {
          throw new IllegalStateException("Output component mismatch");
        }
        contents.set(slot.index(), before.copyWithCount(total));
      }
    }
    return Map.copyOf(changed);
  }
}
