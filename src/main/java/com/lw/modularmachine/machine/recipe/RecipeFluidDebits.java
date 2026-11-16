package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceTransaction;
import java.util.List;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Shared resolved-by-caller fluid payment step; planning remains role-specific.
 */
@ApiStatus.Internal
final class RecipeFluidDebits {
  record Debit(RecipePortSnapshot port, FluidStack amount) {
  }
  private RecipeFluidDebits() {
  }
  static boolean acquireAndDrain(List<Debit> fluidDebits, RecipeResourceTransaction.Phase fluidResources, List<MachinePortBlockEntity.FluidStartLease> fluidLeases) {
    for (var debit : fluidDebits) {
      if (debit.port().patternInput()) {
        continue;
      }
      var lease = fluidResources.enlist(
          debit.port().identity().acquireFluidStart(debit.port().revision(), debit.port().tankIndex()),
          MachinePortBlockEntity.FluidStartLease::resolve);
      fluidLeases.add(lease);
      if (!lease.drainExact(debit.amount())) {
        return false;
      }

    }
    return true;
  }
}
