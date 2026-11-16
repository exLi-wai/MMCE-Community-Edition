/**
 * Internal shared ordinary/factory upgrade catalog, bindings, costs and
 * callbacks. Public values and contracts live in api.upgrade; script parsing
 * lives in integration.kubejs. Callback guards and epoch publication remain
 * domain responsibilities; resource commits use the existing storage leases.
 */
@ApiStatus.Internal
package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
