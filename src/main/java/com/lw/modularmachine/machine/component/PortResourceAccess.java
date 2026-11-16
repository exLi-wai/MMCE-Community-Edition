package com.lw.modularmachine.machine.component;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal capture adapters. Callers establish server affinity and live port
 * identity before accessing storage.
 */
@ApiStatus.Internal
public final class PortResourceAccess {
  @FunctionalInterface
  public interface Quantity {
    long read(MachinePortBlockEntity port, int tank);
  }

  public record Adapter(
      ToIntFunction<MachinePortBlockEntity> tanks,
      Quantity amount,
      Quantity capacity,
      Function<MachinePortBlockEntity, Component> status) {
    public Adapter {
      Objects.requireNonNull(tanks, "tanks");
      Objects.requireNonNull(amount, "amount");
      Objects.requireNonNull(capacity, "capacity");
      Objects.requireNonNull(status, "status");
    }
  }

  private static final Map<ResourceType, Adapter> ADAPTERS = new LinkedHashMap<>();
  private static Map<ResourceType, Adapter> frozen;

  static {
    register(
        ResourceType.ITEM,
        new Adapter(
            port -> 1,
            (port, tank) -> 0,
            (port, tank) -> 0,
            port -> Component.translatable("message.mmcece.item_port", port.inventory().getSlots())));
    register(
        ResourceType.FLUID,
        new Adapter(
            MachinePortBlockEntity::fluidTankCount,
            (port, tank) -> port.fluidTank(tank).getFluidAmount(),
            (port, tank) -> port.fluidTank(tank).getCapacity(),
            port -> Component.translatable(
                "message.mmcece.fluid_port",
                port.fluidTank().getFluidAmount(),
                port.fluidTank().getCapacity())));
    register(
        ResourceType.ENERGY,
        new Adapter(
            port -> 1,
            (port, tank) -> port.energy().stored(),
            (port, tank) -> port.energy().capacity(),
            port -> Component.translatable(
                "message.mmcece.energy_port",
                port.energy().stored(),
                port.energy().capacity(),
                port.energy().transferLimit())));
    register(
        ResourceType.CHEMICAL,
        new Adapter(
            MachinePortBlockEntity::chemicalTankCount,
            (port, tank) -> port.chemicalTank(tank).getAmount(),
            (port, tank) -> port.chemicalTank(tank).getCapacity(),
            port -> Component.translatable(
                "message.mmcece.chemical_port",
                port.chemicalTank().getAmount(),
                port.chemicalTank().getCapacity())));
  }

  private PortResourceAccess() {
  }

  public static synchronized void register(ResourceType type, Adapter adapter) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(adapter, "adapter");
    if (frozen != null) {
      throw new IllegalStateException("Resource capture registration is closed");
    }
    if (ADAPTERS.putIfAbsent(type, adapter) != null) {
      throw new IllegalArgumentException("Duplicate resource capture adapter: " + type.id());
    }
  }

  public static synchronized Adapter require(ResourceType type) {
    if (frozen == null) {
      for (var registered : ADAPTERS.keySet()) {
        ResourceTypeRegistry.requireRegistered(registered);
      }
      frozen = Map.copyOf(ADAPTERS);
    }
    var adapter = frozen.get(type);
    if (adapter == null) {
      throw new IllegalArgumentException("No storage capture adapter for resource: " + type.id());
    }
    return adapter;
  }
}
