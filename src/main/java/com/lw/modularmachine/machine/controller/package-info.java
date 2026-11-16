/**
 * Internal ordinary-controller state, search, execution and inspection.
 * ControllerRunState owns paid runs; ControllerRecipeSearch owns candidates;
 * ControllerExecutionCoordinator preserves the ordered tick. Block entities
 * retain platform identity, persistence entrypoints and lifecycle wiring.
 * ControllerBlueprintInventory owns the ordinary/factory blueprint slot and its
 * registered machine identity rules; generic storage retains only mechanisms.
 * Player ownership, script state and command/pattern sidecars remain distinct.
 */
@ApiStatus.Internal
package com.lw.modularmachine.machine.controller;

import org.jetbrains.annotations.ApiStatus;
