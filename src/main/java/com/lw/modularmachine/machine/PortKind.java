package com.lw.modularmachine.machine;

import com.lw.modularmachine.api.component.ResourceType;

public enum PortKind {
  ITEM_INPUT("item_input_bus", ResourceType.ITEM, true),
  ITEM_OUTPUT("item_output_bus", ResourceType.ITEM, false),
  FLUID_INPUT("fluid_input_hatch", ResourceType.FLUID, true),
  FLUID_OUTPUT("fluid_output_hatch", ResourceType.FLUID, false),
  ENERGY_INPUT("energy_input_hatch", ResourceType.ENERGY, true),
  ENERGY_OUTPUT("energy_output_hatch", ResourceType.ENERGY, false),
  CHEMICAL_INPUT("chemical_input_hatch", ResourceType.CHEMICAL, true),
  CHEMICAL_OUTPUT("chemical_output_hatch", ResourceType.CHEMICAL, false);

  private final String path;
  private final ResourceType resource;
  private final boolean input;

  PortKind(String path, ResourceType resource, boolean input) {
    this.path = path;
    this.resource = resource;
    this.input = input;
  }

  public String path() {
    return path;
  }

  public ResourceType resource() {
    return resource;
  }

  public boolean isInput() {
    return input;
  }

}
