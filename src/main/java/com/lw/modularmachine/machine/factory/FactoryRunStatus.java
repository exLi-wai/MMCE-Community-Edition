package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.machine.recipe.FactoryRunResourceResult;
import com.lw.modularmachine.machine.factory.FactoryThreadState.Reason;
import com.lw.modularmachine.machine.factory.FactoryThreadState.State;
import org.jetbrains.annotations.ApiStatus;

/**
 * Exact diagnostic mapping shared by paid batch and individual-run advancement.
 */
@ApiStatus.Internal
record FactoryRunStatus(State state, Reason reason) {
  static FactoryRunStatus from(FactoryRunResourceResult result) {
    return of(switch (result) {
      case ADVANCED -> Reason.NONE;
      case WAITING_ENERGY -> Reason.WAITING_ENERGY;
      case WAITING_RESOURCE -> Reason.WAITING_RESOURCE;
      case MISSING_RESOURCE_INPUT -> Reason.MISSING_RESOURCE_INPUT;
      case WAITING_CHEMICAL -> Reason.WAITING_CHEMICAL;
      case MISSING_CHEMICAL_INPUT -> Reason.MISSING_CHEMICAL_INPUT;
      case WAITING_FLUID -> Reason.WAITING_FLUID;
      case MISSING_ENERGY_INPUT -> Reason.MISSING_ENERGY_INPUT;
      case MISSING_ENERGY_OUTPUT -> Reason.MISSING_ENERGY_OUTPUT;
      case OUTPUT_BLOCKED -> Reason.OUTPUT_BLOCKED;
      case MISSING_FLUID_INPUT -> Reason.MISSING_FLUID_INPUT;
      case WAITING_UPGRADE -> Reason.WAITING_UPGRADE_COST;
      case UPGRADE_ERROR -> Reason.UPGRADE_ERROR;
      case UPGRADE_PENDING -> Reason.UPGRADE_DATA_PENDING;
      case STALE -> Reason.STALE_RESOURCES;
    });
  }

  private static FactoryRunStatus of(Reason reason) {
    return new FactoryRunStatus(switch (reason) {
      case NONE -> State.RUNNING;
      case OUTPUT_BLOCKED -> State.BLOCKED;
      default -> State.WAITING;
    }, reason);
  }
}
