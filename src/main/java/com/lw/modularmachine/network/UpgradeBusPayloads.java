package com.lw.modularmachine.network;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.UpgradeBusTier;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCatalog;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRestrictions;
import com.lw.modularmachine.machine.factory.UpgradeBusBinding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Server-authored descriptions. Owner rows are interpretations, never extra
 * inventories.
 */
public final class UpgradeBusPayloads {
  public static final CustomPacketPayload.Type<Status> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "upgrade_bus_status"));
  public static final StreamCodec<RegistryFriendlyByteBuf, Status> CODEC = StreamCodec
      .of(UpgradeBusPayloads::write, UpgradeBusPayloads::read);
  private static final int MAX_BOUND_SLOTS = UpgradeBusBinding.MAX_BINDINGS * Arrays.stream(UpgradeBusTier.values())
      .mapToInt(UpgradeBusTier::slots).max().orElseThrow();
  private static volatile Consumer<Status> receiver = ignored -> {
  };

  private UpgradeBusPayloads() {
  }

  public record BindingView(
      BlockPos controller,
      ResourceLocation machine,
      boolean valid,
      boolean active,
      List<FactoryUpgradeEntry> entries,
      List<ResourceLocation> incompatible,
      int conflictCount,
      List<ResourceLocation> conflicts) {
    public BindingView {
      controller = controller.immutable();
      if (machine == null
          || machine.getPath().isEmpty()
          || machine.toString().length() > 256
          || (long) entries.size() + incompatible.size() > FactoryUpgradeCatalog.MAX_SLOTS
          || !valid && (!entries.isEmpty() || !incompatible.isEmpty())
          || active && !valid
          || conflictCount < 0) {
        throw new IllegalArgumentException("Invalid bound upgrade description");
      }
      entries = List.copyOf(entries);
      incompatible = List.copyOf(incompatible);
      conflicts = List.copyOf(conflicts);
      validateIdentifiers(entries, incompatible, conflicts);
      if (conflictCount == 0 && !conflicts.isEmpty() || conflicts.size() > FactoryUpgradeRestrictions.MAX_DIAGNOSTICS) {
        throw new IllegalArgumentException("Invalid bound upgrade conflicts");
      }
    }
  }

  public record Status(
      int containerId,
      BlockPos position,
      long revision,
      boolean valid,
      List<FactoryUpgradeEntry> entries,
      ResourceLocation machine,
      List<ResourceLocation> incompatible,
      int conflictCount,
      List<ResourceLocation> conflicts,
      long bindingRevision,
      List<BindingView> bindings) implements CustomPacketPayload {
    public Status(int containerId, BlockPos position, long revision, boolean valid, List<FactoryUpgradeEntry> entries, ResourceLocation machine, List<ResourceLocation> incompatible, int conflictCount, List<ResourceLocation> conflicts) {
      this(containerId, position, revision, valid, entries, machine, incompatible, conflictCount, conflicts, 0,
          List.of());
    }

    public Status(int containerId, BlockPos position, long revision, boolean valid, List<FactoryUpgradeEntry> entries, ResourceLocation machine, List<ResourceLocation> incompatible) {
      this(containerId, position, revision, valid, entries, machine, incompatible, 0, List.of());
    }

    public Status(int containerId, BlockPos position, long revision, boolean valid, List<FactoryUpgradeEntry> entries) {
      this(containerId, position, revision, valid, entries, null, List.of());
    }

    public Status {
      if (containerId < 0
          || revision < 0
          || bindingRevision < 0
          || (long) entries.size() + incompatible.size() > FactoryUpgradeCatalog.MAX_SLOTS
          || !valid && (!entries.isEmpty() || !incompatible.isEmpty())
          || machine != null && (machine.getPath().isEmpty() || machine.toString().length() > 256)) {
        throw new IllegalArgumentException("Invalid upgrade bus status");
      }
      position = position.immutable();
      entries = List.copyOf(entries);
      incompatible = List.copyOf(incompatible);
      conflicts = List.copyOf(conflicts);
      bindings = List.copyOf(bindings);
      validateIdentifiers(entries, incompatible, conflicts);
      if (conflictCount < 0
          || (conflictCount == 0) != conflicts.isEmpty()
          || conflicts.size() > FactoryUpgradeRestrictions.MAX_DIAGNOSTICS
          || bindings.size() > UpgradeBusBinding.MAX_BINDINGS
          || bindings.stream().mapToLong(view -> (long) view.entries().size() + view.incompatible().size())
              .sum() > MAX_BOUND_SLOTS
          || bindings.stream().mapToLong(view -> view.conflicts().size())
              .sum() > FactoryUpgradeRestrictions.MAX_DIAGNOSTICS) {
        throw new IllegalArgumentException("Upgrade status description budget exceeded");
      }
      var owners = new HashSet<BlockPos>();
      if (bindings.stream().anyMatch(view -> !owners.add(view.controller()))) {
        throw new IllegalArgumentException("Duplicate upgrade owner description");
      }
    }

    @Override
    public CustomPacketPayload.Type<Status> type() {
      return TYPE;
    }
  }

  private static void validateIdentifiers(List<FactoryUpgradeEntry> entries, List<ResourceLocation> incompatible, List<ResourceLocation> conflicts) {
    if (entries.stream().anyMatch(entry -> entry.id().toString().length() > 256)
        || incompatible.stream().anyMatch(id -> id.toString().length() > 256)
        || conflicts.stream().anyMatch(id -> id.toString().length() > 256)
        || conflicts.stream().distinct().count() != conflicts.size()) {
      throw new IllegalArgumentException("Invalid upgrade description identifiers");
    }
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    event.registrar("4")
        .playToClient(TYPE, CODEC, (payload, context) -> context.enqueueWork(() -> receiver.accept(payload)));
  }

  public static void setClientReceiver(Consumer<Status> clientReceiver) {
    receiver = Objects.requireNonNull(clientReceiver, "clientReceiver");
  }

  public static Status capture(int containerId, UpgradeBusBlockEntity bus) {
    bus.refreshUpgradeDefinitions();
    bus.refreshBinding();
    boolean valid = bus.schemaSupported() && bus.upgradeCatalogValid();
    var restrictions = bus.upgradeRestrictionStatus();
    var bindings = new ArrayList<BindingView>();
    int remainingIds = FactoryUpgradeRestrictions.MAX_DIAGNOSTICS;
    for (var binding : bus.bindings()) {
      var description = bus.description(binding);
      boolean readable = bus.descriptionValid(binding);
      var conflicts = bus.upgradeRestrictionStatus(binding);
      var ids = conflicts.upgradeIds().stream().limit(remainingIds).toList();
      remainingIds -= ids.size();
      bindings.add(
          new BindingView(
              binding.controller(),
              binding.machine(),
              readable,
              readable && bus.bindingActive(binding),
              readable ? description.catalog().entries() : List.of(),
              readable ? description.incompatible() : List.of(),
              conflicts.totalViolations(),
              ids));
    }
    return new Status(
        containerId,
        bus.getBlockPos(),
        bus.upgradeRevision(),
        valid,
        valid ? bus.upgradeCatalog().entries() : List.of(),
        bus.boundMachine().orElse(null),
        valid ? bus.incompatibleUpgrades() : List.of(),
        restrictions.totalViolations(),
        restrictions.upgradeIds(),
        bus.bindingRevision(),
        bindings);
  }

  private static void writeEntries(RegistryFriendlyByteBuf buffer, List<FactoryUpgradeEntry> entries) {
    buffer.writeVarInt(entries.size());
    for (var entry : entries) {
      buffer.writeUtf(entry.id().toString(), 256);
      buffer.writeVarInt(entry.parallelAdd());
      buffer.writeVarInt(entry.speedNumerator());
      buffer.writeVarInt(entry.speedDenominator());
      buffer.writeVarInt(entry.energyNumerator());
      buffer.writeVarInt(entry.energyDenominator());
      buffer.writeVarInt(entry.fluidNumerator());
      buffer.writeVarInt(entry.fluidDenominator());
    }
  }

  private static List<FactoryUpgradeEntry> readEntries(RegistryFriendlyByteBuf buffer, int limit) {
    int size = readSize(buffer, limit);
    var entries = new ArrayList<FactoryUpgradeEntry>();
    for (int index = 0; index < size; index++) {
      entries.add(
          new FactoryUpgradeEntry(
              ResourceLocation.parse(buffer.readUtf(256)),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt(),
              buffer.readVarInt()));
    }
    return entries;
  }

  private static void writeIds(RegistryFriendlyByteBuf buffer, List<ResourceLocation> ids) {
    buffer.writeVarInt(ids.size());
    for (var id : ids) {
      buffer.writeUtf(id.toString(), 256);
    }
  }

  private static List<ResourceLocation> readIds(RegistryFriendlyByteBuf buffer, int limit) {
    int size = readSize(buffer, limit);
    var ids = new ArrayList<ResourceLocation>();
    for (int index = 0; index < size; index++) {
      ids.add(ResourceLocation.parse(buffer.readUtf(256)));
    }
    return ids;
  }

  private static int readSize(RegistryFriendlyByteBuf buffer, int limit) {
    int size = buffer.readVarInt();
    if (size < 0 || size > limit) {
      throw new IllegalArgumentException("Upgrade description exceeds its resource budget");
    }
    return size;
  }

  private static void write(RegistryFriendlyByteBuf buffer, Status status) {
    buffer.writeVarInt(status.containerId());
    buffer.writeBlockPos(status.position());
    buffer.writeVarLong(status.revision());
    buffer.writeBoolean(status.valid());
    writeEntries(buffer, status.entries());
    buffer.writeBoolean(status.machine() != null);
    if (status.machine() != null) {
      buffer.writeUtf(status.machine().toString(), 256);
    }
    writeIds(buffer, status.incompatible());
    buffer.writeVarInt(status.conflictCount());
    writeIds(buffer, status.conflicts());
    buffer.writeVarLong(status.bindingRevision());
    buffer.writeVarInt(status.bindings().size());
    for (var binding : status.bindings()) {
      buffer.writeBlockPos(binding.controller());
      buffer.writeUtf(binding.machine().toString(), 256);
      buffer.writeBoolean(binding.valid());
      buffer.writeBoolean(binding.active());
      writeEntries(buffer, binding.entries());
      writeIds(buffer, binding.incompatible());
      buffer.writeVarInt(binding.conflictCount());
      writeIds(buffer, binding.conflicts());
    }
  }

  private static Status read(RegistryFriendlyByteBuf buffer) {
    int container = buffer.readVarInt();
    var position = buffer.readBlockPos();
    long revision = buffer.readVarLong();
    boolean valid = buffer.readBoolean();
    var entries = readEntries(buffer, FactoryUpgradeCatalog.MAX_SLOTS);
    var machine = buffer.readBoolean() ? ResourceLocation.parse(buffer.readUtf(256)) : null;
    var incompatible = readIds(buffer, FactoryUpgradeCatalog.MAX_SLOTS - entries.size());
    int conflictCount = buffer.readVarInt();
    var conflicts = readIds(buffer, FactoryUpgradeRestrictions.MAX_DIAGNOSTICS);
    long bindingRevision = buffer.readVarLong();
    int count = readSize(buffer, UpgradeBusBinding.MAX_BINDINGS);
    var bindings = new ArrayList<BindingView>();
    int remainingSlots = MAX_BOUND_SLOTS;
    int remainingIds = FactoryUpgradeRestrictions.MAX_DIAGNOSTICS;
    for (int index = 0; index < count; index++) {
      var owner = buffer.readBlockPos();
      var ownerMachine = ResourceLocation.parse(buffer.readUtf(256));
      boolean ownerValid = buffer.readBoolean();
      boolean active = buffer.readBoolean();
      var ownerEntries = readEntries(buffer, Math.min(remainingSlots, FactoryUpgradeCatalog.MAX_SLOTS));
      var ownerIncompatible = readIds(
          buffer,
          Math.min(remainingSlots - ownerEntries.size(), FactoryUpgradeCatalog.MAX_SLOTS - ownerEntries.size()));
      remainingSlots -= ownerEntries.size() + ownerIncompatible.size();
      int ownerConflictCount = buffer.readVarInt();
      var ownerConflicts = readIds(buffer, remainingIds);
      remainingIds -= ownerConflicts.size();
      bindings.add(
          new BindingView(
              owner,
              ownerMachine,
              ownerValid,
              active,
              ownerEntries,
              ownerIncompatible,
              ownerConflictCount,
              ownerConflicts));
    }
    return new Status(
        container,
        position,
        revision,
        valid,
        entries,
        machine,
        incompatible,
        conflictCount,
        conflicts,
        bindingRevision,
        bindings);
  }
}
