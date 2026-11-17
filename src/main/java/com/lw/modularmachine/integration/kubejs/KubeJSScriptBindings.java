package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.smart.SmartInterfaceBinding;
import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.api.controller.ControllerOwnershipApi;
import com.lw.modularmachine.api.controller.ControllerScriptData;
import com.lw.modularmachine.api.controller.ControllerScriptDataApi;
import com.lw.modularmachine.api.controller.MachineEvents;
import com.lw.modularmachine.api.client.controller.ControllerClientEvents;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.google.gson.JsonObject;
import com.lw.modularmachine.api.smart.SmartInterfaceApi;
import com.lw.modularmachine.api.upgrade.DynamicUpgrades;
import com.lw.modularmachine.api.upgrade.UpgradeBusData;
import com.lw.modularmachine.api.upgrade.UpgradeEntry;
import com.lw.modularmachine.api.upgrade.UpgradeEvents;
import com.lw.modularmachine.api.upgrade.UpgradeItemData;
import com.lw.modularmachine.api.upgrade.UpgradeRecipeChecks;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData;
import com.lw.modularmachine.api.upgrade.UpgradeStartCost;
import com.lw.modularmachine.api.recipe.RecipeRunState;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.api.recipe.RecipeCommandActions;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.ChemicalRequirement;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.smart.SmartInterfaceEvents;
import dev.latvian.mods.kubejs.plugin.ClassFilter;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dev.latvian.mods.kubejs.script.ScriptType;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Script names and the explicit allowlist; registration order remains
 * declaration order.
 */
@ApiStatus.Internal
final class KubeJSScriptBindings {
  private KubeJSScriptBindings() {
  }

  static void registerBindings(BindingRegistry registry) {
    if (registry.type() == ScriptType.SERVER) {
      registry.add("RecipeBuilder", KubeJSRecipeBuilder.class);
      registry.add("RecipeModifierBuilder", KubeJSRecipeModifierBuilder.class);
      registry.add("IngredientArrayBuilder", KubeJSIngredientArrayBuilder.class);
    }
  }

  static void registerClasses(ClassFilter filter) {
    filter.allow(KubeJSMachineRegistration.class);
    filter.allow(MachineEvents.Context.class);
    filter.allow(MachineEvents.Type.class);
    filter.allow(MachineEvents.Structure.class);
    filter.allow(MachineEvents.Listener.class);
    filter.allow(MachineEvents.ScriptAction.class);
    filter.allow(MachineEvents.ScriptProposal.class);
    filter.allow(RecipeItemCallbacks.Context.class);
    filter.allow(RecipeItemCallbacks.Checker.class);
    filter.allow(RecipeItemCallbacks.Modifier.class);
    filter.allow(RecipeItemCallbacks.InputModifier.class);
    filter.allow(RecipeItemCallbacks.ItemRequirement.class);
    filter.allow(ControllerClientEvents.Context.class);
    filter.allow(ControllerClientEvents.GuiListener.class);
    filter.allow(ControllerClientEvents.GuiEvent.class);
    filter.allow(ControllerClientEvents.ModelListener.class);
    filter.allow(ControllerClientEvents.ModelEvent.class);
    filter.allow(ControllerClientEvents.AnimationListener.class);
    filter.allow(ControllerClientEvents.AnimationEvent.class);
    filter.allow(ControllerClientEvents.Animation.class);
    filter.allow(KubeJSUpgradeHandlerRegistration.class);
    filter.allow(KubeJSUpgradeDeclarations.FixedUpgradeBuilder.class);
    filter.allow(KubeJSSmartInterfaceRegistration.class);
    filter.allow(KubeJSRecipeLifecycleRegistration.class);
    filter.allow(KubeJSRecipeLifecycleRegistration.CommandBuilder.class);
    filter.allow(RecipeCommandActions.Action.class);
    filter.allow(RecipeCommandActions.Source.class);
    filter.allow(RecipeCommandActions.Status.class);
    filter.allow(KubeJSRecipeAdapterRegistration.class);
    filter.allow(KubeJSRecipeBuilder.class);
    filter.allow(KubeJSRecipeBuilder.RecipeBuilder.class);
    filter.allow(ChemicalRequirement.class);
    filter.allow(ChemicalStackData.class);
    filter.allow(IngredientArrayData.class);
    filter.allow(IngredientArrayData.Group.class);
    filter.allow(IngredientArrayData.Choice.class);
    filter.allow(KubeJSIngredientArrayBuilder.class);
    filter.allow(KubeJSIngredientArrayBuilder.IngredientArrayPrimer.class);
    filter.allow(Ingredient.class);
    filter.allow(JsonObject.class);
    filter.allow(KubeJSRecipeModifierBuilder.class);
    filter.allow(KubeJSRecipeModifierBuilder.Builder.class);
    filter.allow(RecipeModifier.class);
    filter.allow(RecipeModifier.Target.class);
    filter.allow(RecipeModifier.Io.class);
    filter.allow(RecipeModifier.Operation.class);
    filter.allow(KubeJSRecipeAdapterBuilder.class);
    filter.allow(SmartInterfaceApi.class);
    filter.allow(ControllerOwnershipApi.class);
    filter.allow(ControllerOwnership.class);
    filter.allow(ControllerScriptDataApi.class);
    filter.allow(ControllerScriptDataApi.Result.class);
    filter.allow(ControllerScriptData.class);
    filter.allow(SmartInterfaceApi.Snapshot.class);
    filter.allow(SmartInterfaceApi.Value.class);
    filter.allow(SmartInterfaceEvents.Listener.class);
    filter.allow(SmartInterfaceEvents.Context.class);
    filter.allow(SmartInterfaceEvents.Change.class);
    filter.allow(SmartInterfaceEvents.Reason.class);
    filter.allow(SmartInterfaceBinding.class);
    filter.allow(RecipeLifecycleEvents.Listener.class);
    filter.allow(RecipeLifecycleEvents.FailurePolicy.class);
    filter.allow(RecipeLifecycleEvents.FailureContext.class);
    filter.allow(RecipeLifecycleEvents.FailureDecision.class);
    filter.allow(RecipeLifecycleEvents.Context.class);
    filter.allow(RecipeLifecycleEvents.Type.class);
    filter.allow(RecipeLifecycleEvents.ResourceFailure.class);
    filter.allow(RecipeLifecycleEvents.RunSnapshot.class);
    filter.allow(RecipeRunState.class);
    filter.allow(UpgradeRuntimeData.Preparer.class);
    filter.allow(UpgradeRuntimeData.Phase.class);
    filter.allow(UpgradeRuntimeData.Context.class);
    filter.allow(UpgradeRuntimeData.Run.class);
    filter.allow(UpgradeRuntimeData.Item.class);
    filter.allow(UpgradeRuntimeData.ItemUpdate.class);
    filter.allow(UpgradeRuntimeData.Update.class);
    filter.allow(DynamicUpgrades.Context.class);
    filter.allow(DynamicUpgrades.Evaluator.class);
    filter.allow(UpgradeEntry.class);
    filter.allow(UpgradeRecipeChecks.Context.class);
    filter.allow(UpgradeRecipeChecks.Checker.class);
    filter.allow(UpgradeRecipeChecks.Decision.class);
    filter.allow(UpgradeEvents.Context.class);
    filter.allow(UpgradeEvents.Upgrade.class);
    filter.allow(UpgradeEvents.RunSnapshot.class);
    filter.allow(UpgradeEvents.Listener.class);
    filter.allow(UpgradeEvents.Type.class);
    filter.allow(UpgradeItemData.Context.class);
    filter.allow(UpgradeItemData.Preparer.class);
    filter.allow(UpgradeItemData.Update.class);
    filter.allow(UpgradeBusData.Context.class);
    filter.allow(UpgradeBusData.Preparer.class);
    filter.allow(UpgradeBusData.Update.class);
    filter.allow(UpgradeStartCost.class);
  }
}
