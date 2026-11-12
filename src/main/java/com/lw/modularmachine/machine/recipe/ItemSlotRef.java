package com.lw.modularmachine.machine.recipe;

/**
 * Transient structure-local buffer address. Logical bindings, physical identity
 * and permissions remain in RecipeExecutionTicket; this address is not saved.
 */
public record ItemSlotRef(String partId, int slot) {
  public ItemSlotRef {
    if (partId == null || partId.isBlank() || slot < 0) {
      throw new IllegalArgumentException("Invalid logical item slot");
    }
  }
}
