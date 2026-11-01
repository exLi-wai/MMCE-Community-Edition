package com.lw.modularmachine.machine.recipe;

/**
 * Stable logical address. Physical identity and permissions remain in
 * RecipeExecutionTicket.
 */
public record ItemSlotRef(String partId, int slot) {
  public ItemSlotRef {
    if (partId == null || partId.isBlank() || slot < 0) {
      throw new IllegalArgumentException("Invalid logical item slot");
    }
  }
}
