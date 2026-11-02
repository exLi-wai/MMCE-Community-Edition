package com.lw.modularmachine.machine.orientation;

import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureModifierDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Pure state-constraint compilation against immutable, server-captured
 * descriptors.
 */
public final class OrientationCompilation {
  private static final int MAX_WORK = 100_000_000;

  private static int workBudget() {
    String override = System.getProperty("mmcece.orientationWorkBudget");
    if (override != null) {
      try {
        return Integer.parseInt(override);
      } catch (NumberFormatException ignored) {
        // fall through to the production budget
      }
    }
    return MAX_WORK;
  }
  private static final int MAX_DIAGNOSTICS = 256;

  public record Diagnostic(String orientation, String partId, String path, String reason) {
  }

  private final int schema;
  private final List<MachineDefinition.Part> parts;
  private final int allowedMask;
  private final int[] replacementMasks;
  private final List<Diagnostic> diagnostics;
  private final boolean diagnosticsTruncated;
  private final Map<MachineOrientation, List<MachineDefinition.Rule>> rules = new ConcurrentHashMap<>();

  private OrientationCompilation(int schema, List<MachineDefinition.Part> parts, int allowedMask, List<Diagnostic> diagnostics, boolean diagnosticsTruncated, int[] replacementMasks) {
    this.schema = schema;
    this.parts = List.copyOf(parts);
    this.allowedMask = allowedMask;
    this.replacementMasks = replacementMasks.clone();
    this.diagnostics = List.copyOf(diagnostics);
    this.diagnosticsTruncated = diagnosticsTruncated;
  }

  public static OrientationCompilation compile(ResourceLocation id, int schema, OrientationPolicy policy, List<MachineDefinition.Part> parts) {
    return compile(id, schema, policy, parts, List.of());
  }

  public static OrientationCompilation compile(ResourceLocation id, int schema, OrientationPolicy policy, List<MachineDefinition.Part> parts, List<StructureModifierDefinition> modifiers) {
    if (schema == 1) {
      return new OrientationCompilation(1, parts, policy.requestedMask(), List.of(), false, new int[parts.size()]);
    }
    List<Diagnostic> diagnostics = new ArrayList<>();
    boolean truncated = false;
    int mask = 0;
    int work = 0;
    var replacementMasks = new int[parts.size()];
    for (var modifier : modifiers) {
      if (modifier.allowReplacement()) {
        for (int index : modifier.partIndices()) {
          replacementMasks[index] |= modifier.compilation().allowedMask();
        }
      }
    }
    for (MachineOrientation orientation : MachineOrientation.all()) {
      if (!policy.allows(orientation)) {
        continue;
      }
      boolean allParts = true;
      for (int index = 0; index < parts.size(); index++) {
        var part = parts.get(index);
        boolean anyAlternative = part.rule().port() != null || (replacementMasks[index] & orientation.maskBit()) != 0;
        for (var alternative : part.rule().alternatives()) {
          work += 1 + alternative.properties().size();
          int budget = workBudget();
          if (work > budget) {
            throw new JsonParseException(id + ": $.parts: Orientation compilation exceeds " + budget + " work units");
          }
          String path = id + ": $.parts[" + index + "]";
          String failure = representabilityFailure(alternative, part.propertiesSpace(), orientation, path);
          if (failure == null) {
            anyAlternative = true;
          } else {
            if (diagnostics.size() < MAX_DIAGNOSTICS) {
              diagnostics.add(new Diagnostic(orientation.key(), part.id(), path, failure));
            } else {
              truncated = true;
            }
          }
        }
        allParts &= anyAlternative;
      }
      if (allParts) {
        mask |= orientation.maskBit();
      }
    }
    if (mask == 0) {
      throw new JsonParseException(
          id + ": $.orientation: No representable orientation for policy " + policy.serializedName());
    }
    return new OrientationCompilation(schema, parts, mask, diagnostics, truncated, replacementMasks);
  }

  public int allowedMask() {
    return allowedMask;
  }

  public boolean allows(MachineOrientation orientation) {
    return (allowedMask & Objects.requireNonNull(orientation, "orientation").maskBit()) != 0;
  }

  public List<Diagnostic> diagnostics() {
    return diagnostics;
  }

  public boolean diagnosticsTruncated() {
    return diagnosticsTruncated;
  }

  public int cachedOrientationCount() {
    return rules.size();
  }

  /**
   * At most 24 canonical keys; resolved predicates are immutable and shared
   * between instances.
   */
  public List<MachineDefinition.Rule> rulesFor(MachineOrientation orientation) {
    RulePreparation preparation = prepareRules(orientation);
    while (!preparation.complete()) {
      preparation.advance(4096);
    }
    return preparation.result();
  }

  public RulePreparation prepareRules(MachineOrientation orientation) {
    if (!allows(orientation)) {
      throw new IllegalArgumentException("ORIENTATION_NOT_ALLOWED: " + orientation.key());
    }
    return new RulePreparation(orientation);
  }

  /**
   * One part/alternative boundary or one property per unit; incomplete rules are
   * never cached.
   */
  public final class RulePreparation {
    private final MachineOrientation orientation;
    private final List<MachineDefinition.Rule> prepared = new ArrayList<>();
    private List<MachineDefinition.Alternative> alternatives = new ArrayList<>();
    private int partIndex;
    private int alternativeIndex;
    private MachineDefinition.Alternative active;
    private Iterator<Map.Entry<Property<?>, Comparable<?>>> properties;
    private Map<Property<?>, Comparable<?>> transformed;
    private List<MachineDefinition.Rule> result;

    private RulePreparation(MachineOrientation orientation) {
      this.orientation = orientation;
      result = rules.get(orientation);
    }

    public boolean complete() {
      return result != null;
    }

    public List<MachineDefinition.Rule> result() {
      if (!complete()) {
        throw new IllegalStateException("Rules are still preparing");
      }
      return result;
    }

    public int advance(int budget) {
      if (budget < 0) {
        throw new IllegalArgumentException("Negative preparation budget");
      }
      int used = 0;
      while (used < budget && !complete()) {
        var part = parts.get(partIndex);
        used++;
        if (schema == 1 || part.rule().port() != null) {
          prepared.add(part.rule());
          partIndex++;
        } else {
          if (active != null) {
            if (properties.hasNext()) {
              var entry = properties.next();
              var descriptor = active.snapshot().properties().get(entry.getKey().getName());
              Comparable<?> value = transform(entry.getValue(), descriptor.kind(), part.propertiesSpace(), orientation);
              if (part.propertiesSpace() == PropertySpace.LOCAL && !descriptor.possibleValues().contains(value)) {
                active = null;
                properties = null;
                transformed = null;
                alternativeIndex++;
              } else {
                transformed.put(entry.getKey(), value);
              }
            } else {
              alternatives.add(new MachineDefinition.Alternative(active.snapshot(), transformed));
              active = null;
              properties = null;
              transformed = null;
              alternativeIndex++;
            }
          } else {
            if (alternativeIndex < part.rule().alternatives().size()) {
              active = part.rule().alternatives().get(alternativeIndex);
              properties = active.properties().entrySet().iterator();
              transformed = new HashMap<>();
            } else {
              if (alternatives.isEmpty() && (replacementMasks[partIndex] & orientation.maskBit()) == 0) {
                throw new IllegalStateException("Validated orientation lost every alternative");
              }
              prepared.add(new MachineDefinition.Rule(null, alternatives));
              alternatives = new ArrayList<>();
              alternativeIndex = 0;
              partIndex++;
            }
          }
        }
        if (partIndex == parts.size()) {
          var frozen = List.copyOf(prepared);
          var existing = rules.putIfAbsent(orientation, frozen);
          result = existing == null ? frozen : existing;
        }
      }
      return used;
    }
  }

  private static String representabilityFailure(MachineDefinition.Alternative alternative, PropertySpace space, MachineOrientation orientation, String path) {
    if (space == PropertySpace.WORLD) {
      return null;
    }
    for (var entry : alternative.properties().entrySet()) {
      Property<?> property = entry.getKey();
      var descriptor = alternative.snapshot().properties().get(property.getName());
      if (descriptor.kind() == StateOrientationAdapters.TransformKind.UNSUPPORTED) {
        throw new JsonParseException(
            path + ".properties." + property.getName() + ": UNSUPPORTED_ORIENTATION_PROPERTY: " + alternative.snapshot()
                .id() + "; declare world semantics or provide an audited adapter");
      }
      Comparable<?> transformed = transform(entry.getValue(), descriptor.kind(), space, orientation);
      if (!descriptor.possibleValues().contains(transformed)) {
        return alternative.snapshot().id() + "." + property.getName() + " cannot represent " + transformed;
      }
    }
    return null;
  }

  private static Comparable<?> transform(Comparable<?> value, StateOrientationAdapters.TransformKind kind, PropertySpace space, MachineOrientation orientation) {
    if (space == PropertySpace.WORLD) {
      return value;
    }
    return switch (kind) {
      case DIRECTION -> orientation.toWorldDirection(directionValue(value));
      case AXIS -> orientation.toWorldAxis(axisValue(value));
      case SCALAR -> value;
      case UNSUPPORTED -> throw new IllegalStateException("Unvalidated property transform");
    };
  }

  private static Direction directionValue(Comparable<?> value) {
    for (Direction direction : Direction.values()) {
      if (direction.equals(value)) {
        return direction;
      }
    }
    throw new IllegalArgumentException("Expected a Direction property value");
  }

  private static Direction.Axis axisValue(Comparable<?> value) {
    for (Direction.Axis axis : Direction.Axis.values()) {
      if (axis.equals(value)) {
        return axis;
      }
    }
    throw new IllegalArgumentException("Expected a Direction.Axis property value");
  }
}
