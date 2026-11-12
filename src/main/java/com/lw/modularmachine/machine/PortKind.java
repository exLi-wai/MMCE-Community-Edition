package com.lw.modularmachine.machine;

public enum PortKind {
  ITEM_INPUT("item_input_bus", Resource.ITEM, true),
  ITEM_OUTPUT("item_output_bus", Resource.ITEM, false),
  FLUID_INPUT("fluid_input_hatch", Resource.FLUID, true),
  FLUID_OUTPUT("fluid_output_hatch", Resource.FLUID, false),
  ENERGY_INPUT("energy_input_hatch", Resource.ENERGY, true),
  ENERGY_OUTPUT("energy_output_hatch", Resource.ENERGY, false),
  CHEMICAL_INPUT("chemical_input_hatch", Resource.CHEMICAL, true),
  CHEMICAL_OUTPUT("chemical_output_hatch", Resource.CHEMICAL, false);

  private final String path;
  private final Resource resource;
  private final boolean input;

  PortKind(String path, Resource resource, boolean input) {
    this.path = path;
    this.resource = resource;
    this.input = input;
  }

  public String path() {
    return path;
  }

  public Resource resource() {
    return resource;
  }

  public boolean isInput() {
    return input;
  }

  public enum Resource {
    ITEM,
    FLUID,
    ENERGY,
    CHEMICAL
  }

}
