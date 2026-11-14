package com.lw.modularmachine.api.controller;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.controller.MachineEventPublisher;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Committed structure observations and bounded controller-owned script edits.
 */
public final class MachineEvents {
  public enum Type {
    STRUCTURE_FORMED,
    STRUCTURE_UPDATE,
    MACHINE_PRE_TICK,
    MACHINE_POST_TICK,
    MACHINE_TICK
  }

  public record Structure(
      VerificationState state,
      VerificationFailure failure,
      boolean formed,
      long generation,
      Map<String, Integer> dynamicLengths) {
    public Structure {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(failure, "failure");
      dynamicLengths = Map.copyOf(dynamicLengths);
      if (formed && state != VerificationState.CONFIRMED) {
        throw new IllegalArgumentException("A formed machine requires confirmed proofs");
      }
    }
  }

  public record Context(
      Type type,
      ResourceLocation dimension,
      BlockPos controllerPosition,
      ResourceLocation machine,
      boolean factory,
      long tick,
      Optional<Structure> before,
      Structure after,
      Optional<ControllerScriptData> scriptData,
      ControllerOwnership ownership) {
    public Context {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(before, "before");
      Objects.requireNonNull(after, "after");
      Objects.requireNonNull(scriptData, "scriptData");
      Objects.requireNonNull(ownership, "ownership");
      controllerPosition = controllerPosition.immutable();
      if (tick < 0 || type == Type.STRUCTURE_FORMED && !after.formed()) {
        throw new IllegalArgumentException("Invalid machine event boundary");
      }
    }
  }

  @FunctionalInterface
  public interface Listener {
    void onEvent(Context context);
  }

  @FunctionalInterface
  public interface ScriptAction {
    ScriptProposal propose(Context context);
  }

  /**
   * Full replacement of independent script fields; null return means unchanged.
   */
  public record ScriptProposal(String customData, Map<ResourceLocation, RecipeModifier> customModifiers) {
    public ScriptProposal {
      var validated = new ControllerScriptData(0, customData, customModifiers);
      customData = validated.customData();
      customModifiers = validated.customModifiers();
    }
  }

  private MachineEvents() {
  }

  public static ScriptProposal scriptData(String customData, Map<ResourceLocation, RecipeModifier> modifiers) {
    return new ScriptProposal(customData, modifiers);
  }

  public static void register(ResourceLocation id, Set<Type> types, Listener listener) {
    MachineEventPublisher.register(id, types, listener);
  }

  public static void registerAction(ResourceLocation id, Set<Type> types, ScriptAction action) {
    MachineEventPublisher.registerAction(id, types, action);
  }

  public static void remove(ResourceLocation id) {
    MachineEventPublisher.remove(id);
  }
}
