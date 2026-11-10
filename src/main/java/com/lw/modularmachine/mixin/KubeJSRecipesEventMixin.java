package com.lw.modularmachine.mixin;

import com.google.common.collect.Maps;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import dev.latvian.mods.kubejs.recipe.RecipeTypeFunction;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps native type lookup available without exposing an object recipe
 * function.
 */
@Mixin(value = RecipesKubeEvent.class, remap = false)
public abstract class KubeJSRecipesEventMixin {
  @Inject(method = "getRecipes", at = @At("RETURN"), cancellable = true)
  private void MMCECE$filterScriptRecipeFunctions(CallbackInfoReturnable<Map<String, Object>> callback) {
    callback.setReturnValue(
        Maps.filterValues(
            callback.getReturnValue(),
            function -> !(function instanceof RecipeTypeFunction recipe)
                || !recipe.serializerKey.location().equals(MachineRecipeRegistries.ITEM_SERIALIZER.getId())));
  }
}
