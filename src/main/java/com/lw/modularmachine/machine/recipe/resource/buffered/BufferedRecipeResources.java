package com.lw.modularmachine.machine.recipe.resource.buffered;

import com.lw.modularmachine.api.recipe.BufferedAmountRecipeData;
import com.lw.modularmachine.api.recipe.EmberRecipeData;
import com.lw.modularmachine.api.recipe.LaserRecipeData;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.Set;
import java.util.stream.Collectors;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class BufferedRecipeResources {
  public static final BufferedRecipeExecution<LaserRecipeData> LASER = new BufferedRecipeExecution<>(
      RecipeResourceDefinitions.LASER,
      () -> MachineRegistries.LASER_INPUT_HATCH == null ? Set.of() : Set.of(MachineRegistries.LASER_INPUT_HATCH.get()),
      LaserRecipeData::input,
      amount -> amount >= 0);
  public static final BufferedRecipeExecution<EmberRecipeData> EMBER = new BufferedRecipeExecution<>(
      RecipeResourceDefinitions.EMBER,
      () -> MachineRegistries.EMBER_INPUT_HATCHES.values().stream().map(DeferredBlock::get)
          .collect(Collectors.toUnmodifiableSet()),
      EmberRecipeData::micros,
      EmberRecipeData::supportsMicros);
  public static final BufferedRecipeExecution<BufferedAmountRecipeData> MANA = new BufferedRecipeExecution<>(
      RecipeResourceDefinitions.MANA,
      () -> MachineRegistries.SPARK_MANA_INPUT == null ? Set.of() : Set.of(MachineRegistries.SPARK_MANA_INPUT.get()),
      BufferedAmountRecipeData::input,
      amount -> amount >= 0,
      () -> MachineRegistries.SPARK_MANA_OUTPUT == null ? Set.of() : Set.of(MachineRegistries.SPARK_MANA_OUTPUT.get()),
      BufferedAmountRecipeData::output);
  public static final BufferedRecipeExecution<BufferedAmountRecipeData> AURA = new BufferedRecipeExecution<>(
      RecipeResourceDefinitions.AURA,
      () -> MachineRegistries.AURA_INPUT == null ? Set.of() : Set.of(MachineRegistries.AURA_INPUT.get()),
      BufferedAmountRecipeData::input,
      amount -> amount >= 0);

  public static final BufferedRecipeExecution<BufferedAmountRecipeData> HEAT = new BufferedRecipeExecution<>(
      RecipeResourceDefinitions.HEAT,
      () -> MachineRegistries.HEAT_INPUT == null ? Set.of() : Set.of(MachineRegistries.HEAT_INPUT.get()),
      BufferedAmountRecipeData::input,
      amount -> amount >= 0,
      () -> MachineRegistries.HEAT_OUTPUT == null ? Set.of() : Set.of(MachineRegistries.HEAT_OUTPUT.get()),
      BufferedAmountRecipeData::output);

  public static final BufferedRecipeExecution<BufferedAmountRecipeData> LIFE_ESSENCE = new BufferedRecipeExecution<>(
      RecipeResourceDefinitions.LIFE_ESSENCE,
      () -> MachineRegistries.LIFE_ESSENCE_INPUT == null ? Set.of() : Set
          .of(MachineRegistries.LIFE_ESSENCE_INPUT.get()),
      BufferedAmountRecipeData::input,
      amount -> amount >= 0,
      () -> MachineRegistries.LIFE_ESSENCE_OUTPUT == null ? Set.of() : Set
          .of(MachineRegistries.LIFE_ESSENCE_OUTPUT.get()),
      BufferedAmountRecipeData::output);

  private BufferedRecipeResources() {
  }
}
