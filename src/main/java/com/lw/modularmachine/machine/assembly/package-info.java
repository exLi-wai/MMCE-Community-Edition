/**
 * Internal player-scoped projection, blueprint and construction sessions.
 * AssemblyService wires events and preserves visit order. Coordinators own
 * sessions; survival/creative placement own complete world transactions.
 * Helpers consume the caller's budget; payment, rollback and lease resolution
 * finish before yielding. Logout and server stop release session state.
 */
@ApiStatus.Internal
package com.lw.modularmachine.machine.assembly;

import org.jetbrains.annotations.ApiStatus;
