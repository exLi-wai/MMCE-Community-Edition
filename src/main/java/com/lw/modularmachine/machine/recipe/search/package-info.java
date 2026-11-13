/**
 * Internal machine recipe search, candidate indexes, captured resource views
 * and resumable output planning. Production transactions and paid runs remain
 * in the parent recipe domain; this package does not commit storage or world
 * changes.
 */
@ApiStatus.Internal
package com.lw.modularmachine.machine.recipe.search;

import org.jetbrains.annotations.ApiStatus;
