package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.controller.DedicatedControllerCatalog;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import com.google.gson.JsonParser;
import java.util.function.Supplier;

/**
 * Script declarations augment configuration-owned identities before descriptor
 * capture.
 */
@ApiStatus.Internal
public final class MachineDefinitionScripts {
  public record Snapshot(
      long generation,
      Map<ResourceLocation, String> declarations,
      Map<String, StructureScriptChecks.Condition> conditions,
      Map<String, StructureModifierDefinition.Display> displays) {
    public Snapshot {
      declarations = Map.copyOf(declarations);
      conditions = Map.copyOf(conditions);
      displays = Map.copyOf(displays);
    }
  }

  private static volatile Snapshot current = new Snapshot(0, Map.of(), Map.of(), Map.of());
  private static final ThreadLocal<Snapshot> COMPILING = new ThreadLocal<>();

  private MachineDefinitionScripts() {
  }

  public static synchronized void stage(Map<ResourceLocation, String> declarations, Map<String, StructureScriptChecks.Condition> conditions, Map<String, StructureModifierDefinition.Display> displays) {
    current = new Snapshot(Math.incrementExact(current.generation()), declarations, conditions, displays);
  }

  public static Snapshot snapshot() {
    return current;
  }

  public static boolean current(Snapshot snapshot) {
    return current == snapshot;
  }

  /** Applies the generation's scripts to exclusively owned compilation inputs. */
  public static void applyInPlace(Map<ResourceLocation, JsonElement> sources, Snapshot snapshot) {
    for (var entry : snapshot.declarations().entrySet()) {
      requireRegistered(entry.getKey());
      var source = sources.get(entry.getKey());
      if (source == null || !source.isJsonObject()) {
        throw new IllegalArgumentException("Script machine requires a configuration definition: " + entry.getKey());
      }
      applyPatch(source.getAsJsonObject(), entry.getValue());
    }
  }

  private static void applyPatch(JsonObject root, String declaration) {
    var patch = JsonParser.parseString(declaration).getAsJsonObject();
    patch.getAsJsonArray("remove").forEach(field -> root.remove(field.getAsString()));
    patch.getAsJsonObject("set").entrySet().forEach(field -> root.add(field.getKey(), field.getValue()));
  }

  public static synchronized void clear() {
    current = new Snapshot(Math.incrementExact(current.generation()), Map.of(), Map.of(), Map.of());
  }

  public static <T> T compile(Snapshot snapshot, Supplier<T> operation) {
    var previous = COMPILING.get();
    COMPILING.set(snapshot);
    try {
      return StructureScriptChecks.capture(snapshot.conditions(), operation);
    } finally {
      if (previous == null) {
        COMPILING.remove();
      } else {
        COMPILING.set(previous);
      }
    }
  }

  static StructureModifierDefinition.Display display(String key) {
    var snapshot = COMPILING.get();
    var result = snapshot == null ? null : snapshot.displays().get(key);
    if (result == null) {
      throw new IllegalArgumentException("Unknown script modifier display " + key);
    }
    return result;
  }

  public static void requireRegistered(ResourceLocation id) {
    if (DedicatedControllerCatalog.machines().stream().noneMatch(machine -> machine.id().equals(id))) {
      throw new IllegalArgumentException(
          "Unknown startup machine " + id + "; create its machinery configuration and restart both sides before using MachineBuilder");
    }
  }
}
