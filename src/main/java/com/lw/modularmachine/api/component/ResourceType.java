package com.lw.modularmachine.api.component;

import com.lw.modularmachine.machine.component.ResourceTypeRegistry;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable resource identity. Quantities and access permissions belong to the
 * resource adapter.
 */
public final class ResourceType {
  public static final ResourceType ITEM = new ResourceType(
      ResourceLocation.fromNamespaceAndPath("mmcece", "item"),
      "ITEM",
      0);
  public static final ResourceType FLUID = new ResourceType(
      ResourceLocation.fromNamespaceAndPath("mmcece", "fluid"),
      "FLUID",
      1);
  public static final ResourceType ENERGY = new ResourceType(
      ResourceLocation.fromNamespaceAndPath("mmcece", "energy"),
      "ENERGY",
      2);
  public static final ResourceType CHEMICAL = new ResourceType(
      ResourceLocation.fromNamespaceAndPath("mmcece", "chemical"),
      "CHEMICAL",
      3);
  private final ResourceLocation id;
  private final String name;
  private final int ordinal;

  private ResourceType(ResourceLocation id, String name, int ordinal) {
    this.id = Objects.requireNonNull(id, "id");
    this.name = Objects.requireNonNull(name, "name");
    this.ordinal = ordinal;
  }

  /**
   * Register during Java integration setup, before resource definitions or wire
   * values are used.
   */
  public static ResourceType register(ResourceLocation id) {
    return ResourceTypeRegistry.register(new ResourceType(id, id.toString(), -1));
  }

  public ResourceLocation id() {
    return id;
  }

  /** Stable saved name; built-in names retain their original spelling. */
  public String name() {
    return name;
  }

  /**
   * Legacy built-in wire discriminator; extensions use the namespaced escape
   * value.
   */
  public int ordinal() {
    return ordinal;
  }

  public static ResourceType valueOf(String name) {
    return ResourceTypeRegistry.require(name);
  }

  public static ResourceType[] values() {
    return ResourceTypeRegistry.snapshot().toArray(ResourceType[]::new);
  }

  public void write(FriendlyByteBuf buffer) {
    ResourceTypeRegistry.requireRegistered(this);
    buffer.writeVarInt(ordinal);
    if (ordinal < 0) {
      buffer.writeResourceLocation(id);
    }
  }

  public static ResourceType read(FriendlyByteBuf buffer) {
    ResourceTypeRegistry.snapshot();
    int discriminator = buffer.readVarInt();
    if (discriminator == -1) {
      return ResourceTypeRegistry.require(buffer.readResourceLocation().toString());
    }
    return ResourceTypeRegistry.requireBuiltin(discriminator);
  }

  @Override
  public String toString() {
    return id.toString();
  }
}
