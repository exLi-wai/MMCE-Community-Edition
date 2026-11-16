package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonElement;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Reload-scoped declarations; integrations normalize their live maps at the
 * native apply barrier.
 */
@ApiStatus.Internal
public final class RecipeScriptSources {
  public interface Capture {
    Map<ResourceLocation, JsonElement> snapshot();
    void clear();
  }

  private static volatile Capture captured;

  private RecipeScriptSources() {
  }

  /**
   * Called by the platform recipe hook; no snapshot is taken before its recipe
   * event.
   */
  public static void capture(Capture value) {
    captured = Objects.requireNonNull(value, "value");
  }

  public static Map<ResourceLocation, JsonElement> snapshot() {
    var current = captured;
    return current == null ? Map.of() : current.snapshot();
  }

  /**
   * Ends capture, retaining prepared callback values until publication or the
   * next generation.
   */
  public static void clear() {
    var current = captured;
    captured = null;
    RecipeScriptCallbacks.end();
    if (current != null) {
      current.clear();
    }
  }
}
