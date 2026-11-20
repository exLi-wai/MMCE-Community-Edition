package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-import callback rows and deterministic immutable bindings; publication
 * remains in build.
 */
@ApiStatus.Internal
final class KubeJSAdapterCallbackBindings {
  private final Map<String, CallbackRow> callbackRows = new LinkedHashMap<>();

  static final class CallbackRow {
    final RecipeModifier.Io io;
    final int row;
    final boolean addedOutput;
    Optional<ResourceLocation> checker = Optional.empty();
    RecipeItemCallbacks.Checker inlineChecker;
    final List<ModifierEntry> modifiers = new ArrayList<>();

    CallbackRow(RecipeModifier.Io io, int row, boolean addedOutput) {
      this.io = io;
      this.row = row;
      this.addedOutput = addedOutput;
    }
  }

  record ModifierEntry(
      ResourceLocation id,
      RecipeItemCallbacks.Modifier output,
      RecipeItemCallbacks.InputModifier input) {
  }

  record CompiledCallbacks(
      List<RecipeAdapterCustomization.CallbackBinding> bindings,
      Map<ResourceLocation, RecipeItemCallbacks.Checker> checkers,
      Map<ResourceLocation, RecipeItemCallbacks.Modifier> modifiers,
      Map<ResourceLocation, RecipeItemCallbacks.InputModifier> inputModifiers) {
  }

  CallbackRow row(KubeJSRecipeAdapterRegistration owner, RecipeModifier.Io io, int index, boolean addedOutput) {
    String key = io.serialized() + ":" + addedOutput + ":" + index;
    if (!callbackRows.containsKey(key) && callbackRows.size() >= RecipeScriptOptions.MAX_ITEM_CALLBACKS) {
      throw owner.reject("Too many adapter item callback rows");
    }

    return callbackRows.computeIfAbsent(key, ignored -> new CallbackRow(io, index, addedOutput));
  }
  CompiledCallbacks compile(ResourceLocation machine, ResourceLocation adapter, String id, int[] retainedOutputRows) {
    var bindings = new ArrayList<RecipeAdapterCustomization.CallbackBinding>();
    var checkers = new LinkedHashMap<ResourceLocation, RecipeItemCallbacks.Checker>();
    var outputModifiers = new LinkedHashMap<ResourceLocation, RecipeItemCallbacks.Modifier>();
    var inputModifiers = new LinkedHashMap<ResourceLocation, RecipeItemCallbacks.InputModifier>();
    for (var row : callbackRows.values()) {
      int bindingRow = row.addedOutput ? retainedOutputRows[row.row] : row.row;
      if (bindingRow < 0) {
        throw new IllegalArgumentException("Triggered item rows cannot attach item callbacks");
      }
      // Inline function IDs follow the declaration row even when an earlier output
      // moves to a trigger.
      String role = "adapter/" + adapter + "/" + id + "/" + row.io.serialized() + "/" + row.addedOutput + "/" + row.row;
      var checker = row.checker;
      if (row.inlineChecker != null) {
        var identifier = KubeJSCallbackIds.identity(machine, role + "/checker");
        checker = Optional.of(identifier);
        checkers.put(identifier, row.inlineChecker);
      }
      var modifierIds = new ArrayList<ResourceLocation>();
      int index = 0;
      for (var entry : row.modifiers) {
        var identifier = entry.id();
        if (identifier == null) {
          identifier = KubeJSCallbackIds.identity(machine, role + "/modifier/" + index);
          if (entry.output() != null) {
            outputModifiers.put(identifier, entry.output());
          } else {
            inputModifiers.put(identifier, entry.input());
          }
        }
        modifierIds.add(identifier);
        index++;
      }
      bindings.add(
          new RecipeAdapterCustomization.CallbackBinding(
              new RecipeScriptOptions.ItemCallbacks(row.io, bindingRow, checker, modifierIds),
              row.addedOutput));
    }

    return new CompiledCallbacks(
        List.copyOf(bindings),
        Map.copyOf(checkers),
        Map.copyOf(outputModifiers),
        Map.copyOf(inputModifiers));
  }

}
