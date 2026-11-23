package com.lw.modularmachine.machine.recipe;

import java.util.Objects;
import java.util.List;

/**
 * Server-thread preflight. A successful check does not itself commit or consume
 * a run.
 */
public final class RecipeCommitGuard {
  private RecipeCommitGuard() {
  }

  public static boolean validate(RecipeExecutionTicket ticket) {
    Objects.requireNonNull(ticket, "ticket");
    return ticket.commitCurrent();
  }

  /**
   * Grouped run views share all physical ports. Each selected pattern receipt
   * still has its own proof.
   */
  public static boolean validateViews(RecipeExecutionTicket root, List<RecipeExecutionTicket> views) {
    for (var view : views) {
      if (!root.sharesCapture(view) || view.patternSource() != null && !view.patternSource().current()) {
        return false;
      }
    }
    if (!validate(root)) {
      return false;
    }
    for (var view : views) {
      if (view.patternSource() != null && !view.patternSource().current()) {
        return false;
      }
    }
    return true;
  }
}
