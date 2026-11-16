/**
 * Internal factory-only scheduling and execution. FactoryThreadConfiguration
 * owns scheduler/revision; FactoryRunState owns paid runs; search, run and
 * execution coordinators consume these same objects. FactoryUpgradeSources owns
 * factory discovery and publication. Shared upgrade rules live in
 * machine.upgrade. Unload and NBT restore are distinct operations.
 */
@ApiStatus.Internal
package com.lw.modularmachine.machine.factory;

import org.jetbrains.annotations.ApiStatus;
