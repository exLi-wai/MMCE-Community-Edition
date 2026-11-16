package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.PortKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Read-only single-start fluid reservation shared by ordinary and factory
 * starts.
 */
@ApiStatus.Internal
final class StartupFluidAllocation {
  record Debit(String partId, FluidStack amount) {
  }
  private StartupFluidAllocation() {
  }

  static List<Debit> plan(RecipeExecutionTicket ticket, List<FluidStack> requirements, String selector) {
    var reserved = new HashMap<String, Long>();
    var result = new ArrayList<Debit>();
    for (var requirement : requirements) {
      int remaining = requirement.getAmount();
      for (var port : ticket.ports()) {
        if (port.kind() != PortKind.FLUID_INPUT
            || !port.selector().equals(selector)
            || !FluidStack.isSameFluidSameComponents(port.fluid(), requirement)) {
          continue;
        }
        long already = reserved.getOrDefault(port.partId(), 0L);
        long available = Math.max(0, port.fluidAmount() - already);
        int amount = (int) Math.min(remaining, available);
        if (amount > 0) {
          reserved.put(port.partId(), already + amount);
          result.add(new Debit(port.partId(), requirement.copyWithAmount(amount)));
          remaining -= amount;
        }
        if (remaining == 0) {
          break;
        }
      }
      if (remaining > 0) {
        return null;
      }
    }
    return List.copyOf(result);
  }
}
