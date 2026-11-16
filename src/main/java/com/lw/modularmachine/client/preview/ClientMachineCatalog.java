package com.lw.modularmachine.client.preview;

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
import org.jetbrains.annotations.ApiStatus;

/**
 * One atomic server-authored display generation, separate from StructureService
 * authority.
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = ModularMachine.MODID, value = Dist.CLIENT)
public final class ClientMachineCatalog {
  public record Machine(ResourceLocation id, String translation, int color, MachinePreviewDefinition preview) {
    public List<MachineJeiPayloads.Part> parts() {
      return preview.minimum();
    }
  }
  private static final class Pending {
    final MachineJeiPayloads.Snapshot header;
    final List<MachineJeiPayloads.Part> parts = new ArrayList<>();

    Pending(MachineJeiPayloads.Snapshot header) {
      this.header = header;
    }

    Machine finish() {
      if (parts.size() != header.total()) {
        throw new IllegalArgumentException("Incomplete display template stream");
      }
      return new Machine(
          header.machine(),
          header.translation(),
          header.color(),
          new MachinePreviewDefinition(parts, header.fixed(), header.segments()));
    }
  }
  private static Map<ResourceLocation, Machine> active = Map.of();
  private static final Map<ResourceLocation, Pending> pending = new LinkedHashMap<>();
  private static boolean receiving, published;
  private static long epoch = -1;
  private static int expected;
  private static int positions;
  private ClientMachineCatalog() {
  }
  public static String translation(ResourceLocation machine) {
    var value = active.get(machine);
    return value == null ? "machine." + machine.getNamespace() + "." + machine.getPath() : value.translation();
  }
  public static Map<ResourceLocation, Machine> machines() {
    return active;
  }

  /** An empty, completely received generation is still a published catalog. */
  public static boolean hasPublishedGeneration() {
    return published;
  }
  public static void receive(MachineJeiPayloads.Snapshot packet) {
    if (packet.start() == -1) {
      pending.clear();
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
      if (pending.size() != expected) {
        throw new IllegalArgumentException("Incomplete preview generation");
      }
      var complete = new LinkedHashMap<ResourceLocation, Machine>();
      pending.forEach((id, value) -> complete.put(id, value.finish()));
      active = Map.copyOf(complete);
      published = true;
      receiving = false;
      pending.clear();
      return;
    }
    if (packet.parts().isEmpty()) {
      throw new IllegalArgumentException("Invalid preview total");
    }
    var previous = pending.get(packet.machine());
    if (previous == null) {
      if (packet.start() != 0) {
        throw new IllegalArgumentException("Missing preview header");
      }
      previous = new Pending(packet);
      pending.put(packet.machine(), previous);
    } else if (packet.start() == 0
        || !packet.segments().isEmpty()
        || previous.header.total() != packet.total()
        || previous.header.fixed() != packet.fixed()
        || previous.header.color() != packet.color()
        || !previous.header.translation().equals(packet.translation())) {
      throw new IllegalArgumentException("Changed preview metadata");
    }
    if (previous.parts.size() != packet.start()
        || (positions += packet.parts().size()) > MachineJeiPayloads.MAX_PARTS) {
      throw new IllegalArgumentException("Invalid preview chunk ordering/size");
    }
    previous.parts.addAll(packet.parts());
  }
  @SubscribeEvent
  public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
    active = Map.of();
    pending.clear();
    receiving = false;
    published = false;
    epoch = -1;
  }
}
