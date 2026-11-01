package com.lw.modularmachine.machine.recipe;

import java.util.Objects;

/**
 * Server-thread preflight. A successful check does not itself commit or consume
 * a run.
 */
public final class RecipeCommitGuard {
  private RecipeCommitGuard() {
  }

  public static boolean validate(RecipeExecutionTicket ticket) {
    Objects.requireNonNull(ticket, "ticket");
    // Legacy state predicates may reenter and change structure or resources during
    // full scan.
    return ticket.matchesLive() && ticket.revalidateStructure() && ticket.matchesLive();
  }
}
