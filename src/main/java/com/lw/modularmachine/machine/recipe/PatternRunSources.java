package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.PatternProviderBlockEntity;
import com.lw.modularmachine.machine.network.pattern.PatternContainerRef;
import com.lw.modularmachine.machine.network.pattern.PatternProviderReference;
import com.lw.modularmachine.machine.structure.StructureService.StructureSubject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal paid-run source sidecar; it never follows a mirror after admission.
 */
@ApiStatus.Internal
public final class PatternRunSources {
  public static final String NBT_KEY = "controller_pattern_runs";
  private final Map<UUID, Binding> bindings = new LinkedHashMap<>();
  private Tag opaque;

  record Binding(PatternProviderReference provider, PatternContainerRef container, UUID job, String selector) {
    static Binding capture(PatternInputSource source) {
      var provider = source.provider();
      return new Binding(
          new PatternProviderReference(
              provider.getLevel().dimension().location(),
              provider.getBlockPos(),
              source.providerId()),
          source.container(),
          source.job(),
          source.selector());
    }

    CompoundTag save() {
      var row = new CompoundTag();
      row.put("provider", provider.save());
      row.putString("selector", selector);
      if (container != null) {
        row.putInt("slot", container.slot());
        row.putLong("generation", container.generation());
      }
      if (job != null) {
        row.putUUID("job", job);
      }
      return row;
    }

    static Binding load(CompoundTag row) {
      if (!row.contains("provider", Tag.TAG_COMPOUND)
          || !row.contains("selector", Tag.TAG_STRING)
          || row.contains("job") && !row.hasUUID("job")) {
        throw new IllegalArgumentException("Invalid frozen pattern source");
      }
      var reference = PatternProviderReference.load(row.getCompound("provider"));
      PatternContainerRef container = null;
      if (row.contains("slot") || row.contains("generation")) {
        if (!row.contains("slot", Tag.TAG_INT) || !row.contains("generation", Tag.TAG_LONG)) {
          throw new IllegalArgumentException("Invalid frozen pattern container");
        }
        container = new PatternContainerRef(reference.provider(), row.getInt("slot"), row.getLong("generation"));
      }
      if (container == null && row.contains("job")) {
        throw new IllegalArgumentException("Pattern job requires a container");
      }
      return new Binding(
          reference,
          container,
          row.hasUUID("job") ? row.getUUID("job") : null,
          ResourceSelector.require(row.getString("selector")));
    }
  }

  public boolean valid() {
    return opaque == null;
  }

  public static PatternRunSources of(StructureSubject owner) {
    if (owner instanceof MachineControllerBlockEntity controller) {
      return controller.patternRunSources();
    }
    if (owner instanceof FactoryControllerBlockEntity factory) {
      return factory.patternRunSources();
    }
    throw new IllegalArgumentException("Unsupported pattern resource owner");
  }

  /**
   * Called only from a resolved successful admission, after the source was
   * validated.
   */
  public static void bind(StructureSubject owner, UUID run, PatternInputSource source) {
    if (source == null) {
      return;
    }
    of(owner).bindings.put(run, Binding.capture(source));
  }

  /**
   * Called with the same successful commit decision as run removal and hold
   * release.
   */
  public static void unbind(StructureSubject owner, UUID run) {
    of(owner).bindings.remove(run);
  }

  /**
   * Null means an ordinary source; an unavailable frozen source must pause the
   * run.
   */
  public static PatternInputSource source(StructureSubject owner, FrozenItemRun run) {
    var state = of(owner);
    if (!state.valid()) {
      throw new IllegalStateException("Unsupported pattern run sources");
    }
    var binding = state.bindings.get(run.runId());
    if (!run.matchesPatternSource(binding)) {
      throw new IllegalStateException("Frozen pattern source does not match its run");
    }
    if (binding == null) {
      return null;
    }
    if (!(owner.structureLevel() instanceof ServerLevel level)
        || !level.getServer().isSameThread()
        || !binding.provider.dimension().equals(level.dimension().location())
        || !level.hasChunkAt(binding.provider.position())
        || !(level.getBlockEntity(binding.provider.position()) instanceof PatternProviderBlockEntity provider)
        || !provider.canMutate()
        || !provider.providerId().equals(binding.provider.provider())) {
      throw new IllegalStateException("Frozen pattern source is unavailable");
    }
    return new PatternInputSource(provider, null, binding.selector, binding.container, binding.job, run.runId());
  }

  /** Lightweight loaded-source gate for runs without fluid or chemical fees. */
  public static boolean available(StructureSubject owner, FrozenItemRun run) {
    var state = of(owner);
    if (!state.valid()) {
      return false;
    }
    var binding = state.bindings.get(run.runId());
    if (!run.matchesPatternSource(binding)) {
      return false;
    }
    if (binding == null) {
      return true;
    }

    return owner.structureLevel() instanceof ServerLevel level
        && binding.provider.dimension().equals(level.dimension().location())
        && level.hasChunkAt(binding.provider.position())
        && level.getBlockEntity(binding.provider.position()) instanceof PatternProviderBlockEntity provider
        && provider.canMutate()
        && provider.providerId().equals(binding.provider.provider())
        && (binding.container == null || provider.state().contains(binding.container));
  }

  public static Optional<RecipeExecutionTicket> forRun(RecipeExecutionTicket ticket, FrozenItemRun run) {
    try {
      var source = source(ticket.owner(), run);
      return Optional.of(ticket.withInputGroup(run.inputGroup()).withPatternSource(source));
    } catch (RuntimeException unavailable) {
      return Optional.empty();
    }
  }

  public Tag save() {
    if (opaque != null) {
      return opaque.copy();
    }
    var root = new CompoundTag();
    root.putInt("schema", 1);
    var entries = new ListTag();
    bindings.forEach((run, binding) -> {
      var row = binding.save();
      row.putUUID("run", run);
      entries.add(row);
    });
    root.put("sources", entries);
    return root;
  }

  public static PatternRunSources load(Tag value) {
    var result = new PatternRunSources();
    if (value == null) {
      return result;
    }
    try {
      if (!(value instanceof CompoundTag root)
          || !root.contains("schema", Tag.TAG_INT)
          || root.getInt("schema") != 1
          || !(root.get("sources") instanceof ListTag entries)
          || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
        throw new IllegalArgumentException();
      }
      for (var tag : entries) {
        var row = (CompoundTag) tag;
        if (!row.hasUUID("run")) {
          throw new IllegalArgumentException();
        }
        var binding = Binding.load(row);
        if (result.bindings.putIfAbsent(row.getUUID("run"), binding) != null) {
          throw new IllegalArgumentException();
        }
      }
    } catch (RuntimeException invalid) {
      result.opaque = value.copy();
      result.bindings.clear();
    }
    return result;
  }
}
