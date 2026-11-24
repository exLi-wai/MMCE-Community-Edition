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
    this(new Capture(ticket, keys).finish());
  }

  private ItemOutputLayout(Capture capture) {
    slots = List.copyOf(capture.refs);
    snapshot = new ResourceSnapshot<>(capture.ticket.structureRevision(), Map.of(), capture.spaces);
  }

  public static final class Capture {
    private final RecipeExecutionTicket ticket;
    private final Set<ItemResourceKey> keys;
    private final List<Slot> refs = new ArrayList<>();
    private final List<ResourceSnapshot.OutputSlot<ItemResourceKey>> spaces = new ArrayList<>();
    private final Map<ItemSlotDomain, ResourceSnapshot.SlotLimit<ItemResourceKey>> limitsBySize = new HashMap<>();
    private int portIndex, slot;

    public Capture(RecipeExecutionTicket ticket, Set<ItemResourceKey> keys) {
      this.ticket = ticket;
      this.keys = Set.copyOf(keys);
    }

    public int advance(int budget) {
      int used = 0;
      while (portIndex < ticket.ports().size() && used < budget) {
        used++;
        var port = ticket.ports().get(portIndex);
        if (port.kind() != PortKind.ITEM_OUTPUT || slot == port.itemSlotCount()) {
          portIndex++;
          slot = 0;
          continue;
        }
        int i = slot++;
        int limit = port.slotLimits().get(i);
        var domain = new ItemSlotDomain(limit, port.selector(), port.bulkItems());
        var limits = limitsBySize.computeIfAbsent(domain, current -> current.outputLimits(keys));
        refs.add(new Slot(port, i, domain));
        spaces.add(new ResourceSnapshot.OutputSlot<>(port.itemKey(i), port.itemAmount(i), limit, limits));
      }
      return used;
    }

    private Capture finish() {
      advance(Integer.MAX_VALUE);
      return this;
    }

    public boolean complete() {
      return portIndex == ticket.ports().size();
    }

    public ItemOutputLayout layout() {
      if (!complete()) {
        throw new IllegalStateException("Incomplete item output capture");
      }
      return new ItemOutputLayout(this);
    }
  }

  public ResourceSnapshot<ItemResourceKey> snapshot() {
    return snapshot;
  }

  public Set<String> targetParts(RecipePlan<ItemResourceKey> placement) {
    var targets = new HashSet<String>();
    for (var insertion : placement.insertions()) {
      targets.add(slots.get(insertion.slot()).port().partId());
    }
    return targets;
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
