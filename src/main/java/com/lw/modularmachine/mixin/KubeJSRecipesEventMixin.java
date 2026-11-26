package com.lw.modularmachine.mixin;

import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lw.modularmachine.integration.kubejs.MachineItemRecipeKubeJSSources;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import dev.latvian.mods.kubejs.recipe.RecipeTypeFunction;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bridges KubeJS recipe visibility and its final merge of script additions.
 */
@Mixin(value = RecipesKubeEvent.class, remap = false)
public abstract class KubeJSRecipesEventMixin implements MachineItemRecipeKubeJSSources.Replacements {
  @Unique
  private Map<ResourceLocation, MachineItemRecipeKubeJSSources.Replacement> MMCECE$replacements = Map.of();

  @Override
  public MachineItemRecipeKubeJSSources.Replacement MMCECE$replacement(ResourceLocation id) {
    return MMCECE$replacements.get(id);
  }

  // KubeJS writes the edited originals first, then overlays the resolved
  // additions.
  @WrapOperation(method = "applyChanges", at = @At(value = "INVOKE", target = "Ljava/util/Map;putAll(Ljava/util/Map;)V", ordinal = 1))
  private void MMCECE$captureShadowedRecipes(Map<ResourceLocation, JsonElement> surviving, Map<ResourceLocation, JsonElement> additions, Operation<Void> putAll) {
    MMCECE$replacements = MachineItemRecipeKubeJSSources.machineReplacements(surviving, additions);
    putAll.call(surviving, additions);
  }

  @Inject(method = "getRecipes", at = @At("RETURN"), cancellable = true)
  private void MMCECE$filterScriptRecipeFunctions(CallbackInfoReturnable<Map<String, Object>> callback) {
    callback.setReturnValue(
        Maps.filterValues(
            callback.getReturnValue(),
            function -> !(function instanceof RecipeTypeFunction recipe)
                || !recipe.serializerKey.location().equals(MachineRecipeRegistries.ITEM_SERIALIZER.getId())));
  }
}
