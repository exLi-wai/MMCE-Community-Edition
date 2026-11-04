package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.machine.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.machine.recipe.ResourceSelector;
import com.lw.modularmachine.machine.smart.SmartInterfaceEvents;
import com.lw.modularmachine.machine.smart.SmartInterfaceType;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.menu.SmartInterfaceMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-owned independent values per controller/type. Reads never establish or
 * mutate a binding.
 */
public final class SmartInterfaceBlockEntity extends BlockEntity implements MenuProvider, MachineColorable {
  public static final int MAX_BINDINGS = 64;
  public record Binding(BlockPos controller, ResourceLocation machine, String type, double value, long revision) {
    public Binding {
      controller = controller.immutable();
      ResourceSelector.require(type);
      if (machine == null
          || machine.toString().length() > 256
          || type.isEmpty()
          || !Double.isFinite(value)
          || revision < 0) {
        throw new IllegalArgumentException("Invalid numerical interface binding");
      }
    }
  }

  public record Entry(Binding binding, String machineName, SmartInterfaceType specification, boolean editable) {
    public Entry {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(specification, "specification");
      Objects.requireNonNull(machineName, "machineName");
      if (!binding.type().equals(specification.id())) {
        throw new IllegalArgumentException("Interface entry type mismatch");
      }
      if (machineName.length() > 512) {
        machineName = machineName.substring(0, 512);
      }
    }
  }

  private record Key(BlockPos controller, String type) {
  }

  private record Membership(WeakReference<StructureService.Registration> registration, int part) {
  }

  private final Map<Key, Binding> bindings = new LinkedHashMap<>();
  private final Map<Key, Membership> memberships = new LinkedHashMap<>();
  private final Map<BlockPos, SmartInterfaceEvents.Change> pendingNotifications = new LinkedHashMap<>();
  private UUID instance = UUID.randomUUID();
  private long revision;
  private boolean valid = true;
  private CompoundTag opaque;
  private int machineColor = 0xFFFFFF;

  public SmartInterfaceBlockEntity(BlockPos pos, BlockState state) {
    super(MachineRegistries.SMART_INTERFACE_ENTITY.get(), pos, state);
  }

  public boolean schemaSupported() {
    return valid;
  }

  public UUID instance() {
    return instance;
  }

  public long revision() {
    return revision;
  }

  @Override
  public int machineColor() {
    return machineColor;
  }

  @Override
  public void setMachineColor(int color) {
    color &= 0xFFFFFF;
    if (machineColor != color) {
      machineColor = color;
      MachineColorable.update(this);
    }
  }

  private ServerLevel server() {
    if (!(level instanceof ServerLevel server)) {
      throw new IllegalStateException("Smart interface requires a server level");
    }
    ServerThreadGuard.require(server, "Smart interface accessed");

    return server;
  }

  private SmartInterfaceType specification(BlockPos owner, ResourceLocation machine, String type, boolean ready) {
    var server = server();
    if (!valid
        || isRemoved()
        || !server.hasChunkAt(worldPosition)
        || server.getBlockEntity(worldPosition) != this
        || !server.hasChunkAt(owner)
        || !(server.getBlockEntity(owner) instanceof StructureService.StructureSubject controller)
        || !machine.equals(controller.structureMachineId())) {
      return null;
    }
    var registration = controller.structureRegistration();
    if (registration == null
        || !registration.verifier.canCommit()
        || registration.epoch() != StructureService.definitionEpoch()
        || registration.pattern.definition != StructureService.definition(machine)
        || ready && !registration.canCommit(server)) {
      return null;
    }
    var definition = registration.pattern.definition;
    var key = new Key(owner, type);
    var cached = memberships.get(key);
    if (cached != null
        && cached.registration().get() == registration
        && registration.interfaceType(cached.part()).equals(type)) {
      return definition.interfaceTypes().get(type);
    }
    int index = registration.interfacePart(worldPosition);
    if (index >= 0 && registration.interfaceType(index).equals(type)) {
      if (memberships.size() < MAX_BINDINGS || memberships.containsKey(key)) {
        memberships.put(key, new Membership(new WeakReference<>(registration), index));
      }
      return definition.interfaceTypes().get(type);
    }

    return null;
  }

  public void establish(StructureService.StructureSubject controller, SmartInterfaceType type, int partIndex) {
    var server = server();
    if (SmartInterfaceEvents.isDispatching() || RecipeLifecycleEvents.isDispatching()) {
      throw new IllegalStateException("Cannot establish interfaces during a read-only notification");
    }
    var registration = controller.structureRegistration();
    if (registration == null
        || partIndex < 0
        || partIndex >= registration.pattern.definition.parts().size()
        || !registration.interfaceType(partIndex).equals(type.id())
        || !registration.pattern.position(partIndex).equals(worldPosition)) {
      throw new IllegalStateException("Smart interface role does not match the supplied structure position");
    }
    var membershipKey = new Key(controller.structurePosition(), type.id());
    if (memberships.size() < MAX_BINDINGS || memberships.containsKey(membershipKey)) {
      memberships.put(membershipKey, new Membership(new WeakReference<>(registration), partIndex));
    }
    if (controller.structureLevel() != server
        || specification(controller.structurePosition(), controller.structureMachineId(), type.id(), false) == null) {
      throw new IllegalStateException("Smart interface is not a confirmed member of this controller");
    }
    var key = new Key(controller.structurePosition(), type.id());
    var previous = bindings.get(key);
    if (previous != null && previous.machine().equals(controller.structureMachineId())) {
      return;
    }
    if (revision == Long.MAX_VALUE) {
      throw new IllegalStateException("Smart interface binding capacity/revision exhausted");
    }
    var obsolete = bindings.keySet().stream()
        .filter(candidate -> candidate.controller().equals(controller.structurePosition()) && !candidate.equals(key))
        .toList();
    int remaining = bindings.size() - obsolete.size();
    if (previous == null && remaining >= MAX_BINDINGS) {
      throw new IllegalStateException("Smart interface binding capacity/revision exhausted");
    }
    var previousBinding = bindings.values().stream()
        .filter(binding -> binding.controller().equals(controller.structurePosition())).findFirst().orElse(null);
    for (var stale : obsolete) {
      bindings.remove(stale);
      memberships.remove(stale);
    }
    var next = new Binding(
        controller.structurePosition(),
        controller.structureMachineId(),
        type.id(),
        type.defaultValue(),
        revision + 1);
    bindings.put(key, next);
    revision++;
    setChanged();
    if (SmartInterfaceEvents.hasListeners()) {
      var staged = pendingNotifications.get(controller.structurePosition());
      var before = staged == null ? Optional.ofNullable(previousBinding) : staged.before();
      pendingNotifications.put(
          controller.structurePosition().immutable(),
          new SmartInterfaceEvents.Change(
              before.isEmpty() ? SmartInterfaceEvents.Reason.BOUND : SmartInterfaceEvents.Reason.REBOUND,
              before,
              next));
    } else {
      pendingNotifications.remove(controller.structurePosition());
    }
  }

  /**
   * Internal readiness publication. Failed/Pending formation keeps one final
   * change per owner.
   */
  public void publishBindingChange(StructureService.StructureSubject owner) {
    server();
    var change = pendingNotifications.get(owner.structurePosition());
    if (change == null) {
      return;
    }
    if (!matchesBinding(change.after())) {
      pendingNotifications.remove(owner.structurePosition());
      return;
    }
    if (read(owner, change.after().type()).orElse(null) != change.after()) {
      return;
    }
    pendingNotifications.remove(owner.structurePosition());
    SmartInterfaceEvents.publish(this, owner, change, Optional.empty());
  }

  /**
   * Persisted type is only an allocation hint, never permission to use its value.
   */
  public Optional<String> assignmentHint(StructureService.StructureSubject owner) {
    var server = server();
    if (!valid || isRemoved() || owner.structureLevel() != server || server.getBlockEntity(worldPosition) != this) {
      return Optional.empty();
    }

    return bindings.values().stream()
        .filter(
            binding -> binding.controller().equals(owner.structurePosition())
                && binding.machine().equals(owner.structureMachineId()))
        .map(Binding::type).findFirst();
  }

  public Optional<Binding> read(StructureService.StructureSubject owner, String type) {
    server();
    var binding = bindings.get(new Key(owner.structurePosition(), type));
    var specification = binding == null ? null : specification(binding.controller(), binding.machine(), type, true);

    return binding != null
        && owner.structureMachineId().equals(binding.machine())
        && specification != null
        && specification.accepts(binding.value()) ? Optional.of(binding) : Optional.empty();
  }

  public boolean matchesBinding(Binding binding) {
    server();

    return valid && bindings.get(new Key(binding.controller(), binding.type())) == binding;
  }

  public void serverTick() {
    var server = server();
    if (!valid
        || revision == Long.MAX_VALUE
        || SmartInterfaceEvents.isDispatching()
        || RecipeLifecycleEvents.isDispatching()) {
      return;
    }
    if (!SmartInterfaceEvents.hasListeners()) {
      pendingNotifications.clear();
    }
    if (!pendingNotifications.isEmpty()) {
      for (var position : List.copyOf(pendingNotifications.keySet())) {
        if (server.hasChunkAt(position)
            && server.getBlockEntity(position) instanceof StructureService.StructureSubject owner) {
          publishBindingChange(owner);
        }
      }
    }
    if (Math.floorMod(server.getGameTime() + worldPosition.asLong(), 20) != 0) {
      return;
    }
    boolean changed = false;
    var iterator = bindings.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var binding = entry.getValue();
      if (!server.hasChunkAt(binding.controller())) {
        continue;
      }
      var owner = server.getBlockEntity(binding.controller());
      if (!(owner instanceof StructureService.StructureSubject subject)
          || !subject.structureMachineId().equals(binding.machine())
          || subject.structureRegistration() != null
              && subject.structureRegistration().canCommit(server)
              && specification(binding.controller(), binding.machine(), binding.type(), true) == null) {
        memberships.remove(entry.getKey());
        pendingNotifications.remove(binding.controller());
        iterator.remove();
        changed = true;
      }
    }
    if (changed) {
      revision++;
      setChanged();
    }
  }

  public List<Entry> entries() {
    server();
    var entries = new ArrayList<Entry>();
    for (var binding : bindings.values()) {
      var type = specification(binding.controller(), binding.machine(), binding.type(), true);
      var definition = StructureService.definition(binding.machine());
      String name = definition == null ? binding.machine().toString() : definition.translationKey();
      var shown = type == null ? new SmartInterfaceType(
          binding.type(),
          binding.value(),
          -Double.MAX_VALUE,
          Double.MAX_VALUE,
          "",
          "",
          "") : type;
      entries.add(
          new Entry(
              binding,
              name,
              shown,
              type != null && binding.revision() < Long.MAX_VALUE && revision < Long.MAX_VALUE));
    }
    entries.sort(
        Comparator.comparingLong((Entry entry) -> entry.binding().controller().asLong())
            .thenComparing(entry -> entry.binding().type()));

    return List.copyOf(entries);
  }

  public boolean update(Player player, BlockPos controller, String type, long expectedRevision, double value) {
    var server = server();
    if (!valid
        || SmartInterfaceEvents.isDispatching()
        || RecipeLifecycleEvents.isDispatching()
        || revision == Long.MAX_VALUE
        || player.level() != server
        || player.isSpectator()
        || !player.getAbilities().mayBuild
        || player.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5) > 64
        || !server.mayInteract(player, worldPosition)
        || !server.mayInteract(player, controller)) {
      return false;
    }
    var key = new Key(controller, type);
    var current = bindings.get(key);
    var specification = current == null ? null : specification(controller, current.machine(), type, true);
    if (current == null
        || current.revision() != expectedRevision
        || expectedRevision == Long.MAX_VALUE
        || specification == null
        || !specification.accepts(value)) {
      return false;
    }
    var owner = (StructureService.StructureSubject) server.getBlockEntity(controller);
    if (pendingNotifications.containsKey(controller)) {
      publishBindingChange(owner);
      if (bindings.get(key) != current || specification(controller, current.machine(), type, true) == null) {
        return false;
      }
    }
    if (Double.compare(current.value(), value) == 0) {
      return true;
    }
    // Rebinding A -> B -> A must never make an old A editor current again.
    var next = new Binding(controller, current.machine(), type, value, revision + 1);
    bindings.put(key, next);
    revision++;
    setChanged();
    if (SmartInterfaceEvents.hasListeners()) {
      SmartInterfaceEvents.publish(
          this,
          owner,
          new SmartInterfaceEvents.Change(SmartInterfaceEvents.Reason.VALUE_CHANGED, Optional.of(current), next),
          Optional.of(player.getUUID()));
    }

    return true;
  }

  @Override
  public Component getDisplayName() {
    return Component.translatable("block.mmcece.smart_interface");
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    return new SmartInterfaceMenu(id, inventory, this);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    MachineColorable.save(this, tag, machineColor);
    if (!valid && opaque != null) {
      tag.merge(opaque);
      return;
    }
    tag.putInt("smart_schema", 1);
    tag.putUUID("smart_instance", instance);
    tag.putLong("smart_revision", revision);
    var saved = new ListTag();
    for (var binding : bindings.values()) {
      var entry = new CompoundTag();
      entry.putLong("controller", binding.controller().asLong());
      entry.putString("machine", binding.machine().toString());
      entry.putString("type", binding.type());
      entry.putDouble("value", binding.value());
      entry.putLong("revision", binding.revision());
      saved.add(entry);
    }
    tag.put("smart_bindings", saved);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Smart interface load");
      if (SmartInterfaceEvents.isDispatching() || RecipeLifecycleEvents.isDispatching()) {
        throw new IllegalStateException("Cannot reload interfaces during a read-only notification");
      }
    }
    super.loadAdditional(tag, registries);
    machineColor = MachineColorable.load(tag);
    valid = false;
    bindings.clear();
    memberships.clear();
    pendingNotifications.clear();
    try {
      if (!tag.contains("smart_schema", Tag.TAG_INT)
          || tag.getInt("smart_schema") != 1
          || !tag.hasUUID("smart_instance")
          || !tag.contains("smart_revision", Tag.TAG_LONG)
          || tag.getLong("smart_revision") < 0
          || !(tag.get("smart_bindings") instanceof ListTag saved)
          || saved.size() > MAX_BINDINGS
          || !saved.isEmpty() && saved.getElementType() != Tag.TAG_COMPOUND) {
        throw new IllegalArgumentException("Invalid smart interface header");
      }
      for (var element : saved) {
        var entry = (CompoundTag) element;
        if (!entry.getAllKeys().equals(Set.of("controller", "machine", "type", "value", "revision"))
            || !entry.contains("controller", Tag.TAG_LONG)
            || !entry.contains("machine", Tag.TAG_STRING)
            || !entry.contains("type", Tag.TAG_STRING)
            || !entry.contains("value", Tag.TAG_DOUBLE)
            || !entry.contains("revision", Tag.TAG_LONG)) {
          throw new IllegalArgumentException("Invalid smart interface binding data");
        }
        var binding = new Binding(
            BlockPos.of(entry.getLong("controller")),
            ResourceLocation.parse(entry.getString("machine")),
            entry.getString("type"),
            entry.getDouble("value"),
            entry.getLong("revision"));
        if (binding.revision() > tag.getLong("smart_revision")) {
          throw new IllegalArgumentException("Smart interface entry revision exceeds its owner revision");
        }
        if (bindings.putIfAbsent(new Key(binding.controller(), binding.type()), binding) != null) {
          throw new IllegalArgumentException("Duplicate smart interface binding");
        }
      }
      instance = tag.getUUID("smart_instance");
      revision = tag.getLong("smart_revision");
      valid = true;
      opaque = null;
    } catch (RuntimeException invalid) {
      bindings.clear();
      opaque = new CompoundTag();
      for (String key : List.of("smart_schema", "smart_instance", "smart_revision", "smart_bindings")) {
        if (tag.contains(key)) {
          opaque.put(key, tag.get(key).copy());
        }
      }
    }
  }
}
