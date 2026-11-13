/**
 * Internal machine recipe search, candidate catalogs, captured resource views
 * and resumable output planning. Necessary-input indexing lives in index with
 * resource adapters in index.item, index.fluid and index.chemical. Production
 * transactions and paid runs remain in the parent recipe domain; this package
 * does not commit storage or world changes.
 */
@ApiStatus.Internal
package com.lw.modularmachine.machine.recipe.search;

import org.jetbrains.annotations.ApiStatus;
