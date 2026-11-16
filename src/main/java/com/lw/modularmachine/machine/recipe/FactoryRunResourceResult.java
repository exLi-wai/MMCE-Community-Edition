package com.lw.modularmachine.machine.recipe;

import org.jetbrains.annotations.ApiStatus;

/**
 * Shared result of a factory single or paid-batch resource tick; order is
 * unchanged.
 */
@ApiStatus.Internal
public enum FactoryRunResourceResult {
  ADVANCED,
  WAITING_ENERGY,
  WAITING_FLUID,
  WAITING_CHEMICAL,
  WAITING_UPGRADE,
  UPGRADE_ERROR,
  STALE,
  UPGRADE_PENDING,
  MISSING_ENERGY_INPUT,
  MISSING_ENERGY_OUTPUT,
  OUTPUT_BLOCKED,
  MISSING_FLUID_INPUT,
  MISSING_CHEMICAL_INPUT,
  WAITING_RESOURCE,
  MISSING_RESOURCE_INPUT
}
