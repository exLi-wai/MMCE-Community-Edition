package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import java.util.function.ToIntFunction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Reserves physical fluid quantities for ordinary batches and independent
 * factory runs.
 */
@ApiStatus.Internal
final class StartupFluidAllocation {
  record Debit(RecipePortSnapshot port, FluidStack amount) {
  }
  private StartupFluidAllocation() {
  }

  static List<Debit> plan(RecipeExecutionTicket ticket, List<FluidRequirement> requirements, String defaultSelector) {
    return plan(ticket, requirements, defaultSelector, row -> 1);
  }

  static List<Debit> plan(RecipeExecutionTicket ticket, List<FluidRequirement> requirements, String defaultSelector, ToIntFunction<FluidRequirement> repetitions) {
    var reserved = new HashMap<String, Long>();
    var result = new ArrayList<Debit>();
    for (var requirement : requirements) {
      var fluid = requirement.stack();
      String selector = requirement.selector(defaultSelector);
      long remaining = Math.multiplyExact((long) fluid.getAmount(), repetitions.applyAsInt(requirement));
      for (var port : ticket.ports()) {
        if (port.kind() != PortKind.FLUID_INPUT
            || !port.selector().equals(selector)
            || !FluidStack.isSameFluidSameComponents(port.fluid(), fluid)) {
          continue;
        }
        long already = reserved.getOrDefault(port.partId(), 0L);
        long available = Math.max(0, port.fluidAmount() - already);
        while (remaining > 0 && available > 0) {
          int amount = (int) Math.min(Integer.MAX_VALUE, Math.min(remaining, available));
          already += amount;
          reserved.put(port.partId(), already);
          result.add(new Debit(port, fluid.copyWithAmount(amount)));
          remaining -= amount;
          available -= amount;
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
