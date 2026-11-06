package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.ModularMachine;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MachineRecipeRegistries {
  private static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister
      .create(Registries.RECIPE_TYPE, ModularMachine.MODID);
  private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister
      .create(Registries.RECIPE_SERIALIZER, ModularMachine.MODID);
  public static final DeferredHolder<RecipeType<?>, RecipeType<MachineRecipe>> ITEM_TYPE = TYPES
      .register("machine", () -> new RecipeType<>() {
        @Override
        public String toString() {
          return MachineRecipeJson.TYPE;
        }
      });
  public static final DeferredHolder<RecipeSerializer<?>, MachineRecipe.Serializer> ITEM_SERIALIZER = SERIALIZERS
      .register("machine", MachineRecipe.Serializer::new);

  private MachineRecipeRegistries() {
  }

  public static void register(IEventBus bus) {
    TYPES.register(bus);
    SERIALIZERS.register(bus);
  }
}
