package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.network.MachineJeiPayloads;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * One atomic server-authored display generation, separate from StructureService
 * authority.
 */
@EventBusSubscriber(modid = ModularMachine.MODID, value = Dist.CLIENT)
public final class ClientMachineJeiCatalog {
  public record Machine(ResourceLocation id, String translation, int color, List<MachineJeiPayloads.Part> parts) {
    public Machine {
      parts = List.copyOf(parts);
    }
  }
  private static Map<ResourceLocation, Machine> active = Map.of();
  private static final Map<ResourceLocation, Machine> pending = new LinkedHashMap<>();
  private static final Map<ResourceLocation, Integer> totals = new LinkedHashMap<>();
  private static boolean receiving;
  private static long epoch = -1;
  private static int expected;
  private static int positions;
  private ClientMachineJeiCatalog() {
  }
  public static String translation(ResourceLocation machine) {
    var value = active.get(machine);
    return value == null ? "machine." + machine.getNamespace() + "." + machine.getPath() : value.translation();
  }
  public static Map<ResourceLocation, Machine> machines() {
    return active;
  }
  public static void receive(MachineJeiPayloads.Snapshot packet) {
    if (packet.start() == -1) {
      pending.clear();
      totals.clear();
      receiving = true;
      epoch = packet.epoch();
      expected = packet.machines();
      positions = 0;
      return;
    }
    if (!receiving || epoch != packet.epoch() || expected != packet.machines()) {
      throw new IllegalArgumentException("Unpaired preview generation");
    }
    if (packet.start() == -2) {
      if (pending.size() != expected
          || pending.entrySet().stream()
              .anyMatch(entry -> entry.getValue().parts().size() != totals.get(entry.getKey()))) {
        throw new IllegalArgumentException("Incomplete preview generation");
      }
      active = Map.copyOf(pending);
      receiving = false;
      totals.clear();
      pending.clear();
      return;
    }
    var declared = totals.putIfAbsent(packet.machine(), packet.total());
    if (packet.parts().isEmpty() || declared != null && declared != packet.total()) {
      throw new IllegalArgumentException("Invalid preview total");
    }
    var previous = pending.get(packet.machine());
    int size = previous == null ? 0 : previous.parts().size();
    if (size != packet.start() || (positions += packet.parts().size()) > 4_194_304) {
      throw new IllegalArgumentException("Invalid preview chunk ordering/size");
    }
    var parts = new ArrayList<MachineJeiPayloads.Part>();
    if (previous != null) {
      parts.addAll(previous.parts());
    }
    parts.addAll(packet.parts());
    pending.put(packet.machine(), new Machine(packet.machine(), packet.translation(), packet.color(), parts));
  }
  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
    active = Map.of();
    pending.clear();
    totals.clear();
    receiving = false;
    epoch = -1;
  }
}
