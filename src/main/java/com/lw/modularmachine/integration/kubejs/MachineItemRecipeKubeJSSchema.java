package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import dev.latvian.mods.kubejs.recipe.RecipeKey;
import dev.latvian.mods.kubejs.recipe.component.BooleanComponent;
import dev.latvian.mods.kubejs.recipe.component.CustomObjectRecipeComponent;
import dev.latvian.mods.kubejs.recipe.component.FluidStackComponent;
import dev.latvian.mods.kubejs.recipe.component.ItemStackComponent;
import dev.latvian.mods.kubejs.recipe.component.NumberComponent;
import dev.latvian.mods.kubejs.recipe.component.StringComponent;
import dev.latvian.mods.kubejs.recipe.schema.RecipeMappingRegistry;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchema;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchemaRegistry;
import dev.latvian.mods.kubejs.util.IntBounds;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Typed KubeJS schema whose serializer is the same native machine recipe codec.
 */
public final class MachineItemRecipeKubeJSSchema {
  private static final CustomObjectRecipeComponent ITEM_ROW = new CustomObjectRecipeComponent(
      List.of(
          new CustomObjectRecipeComponent.Key("stack", ItemStackComponent.ITEM_STACK.instance()),
          new CustomObjectRecipeComponent.Key("amount", NumberComponent.POSITIVE_LONG.instance()),
          new CustomObjectRecipeComponent.Key("chance", NumberComponent.DOUBLE, true),
          new CustomObjectRecipeComponent.Key("selector", StringComponent.STRING.instance(), true)));
  private static final CustomObjectRecipeComponent SELECTORS = new CustomObjectRecipeComponent(
      List.of(
          new CustomObjectRecipeComponent.Key("fluid_input", StringComponent.STRING.instance(), true),
          new CustomObjectRecipeComponent.Key("fluid_output", StringComponent.STRING.instance(), true),
          new CustomObjectRecipeComponent.Key("energy_input", StringComponent.STRING.instance(), true),
          new CustomObjectRecipeComponent.Key("fluid_per_tick", StringComponent.STRING.instance(), true)));
  private static final CustomObjectRecipeComponent INTERFACE_INPUT = new CustomObjectRecipeComponent(
      List.of(
          new CustomObjectRecipeComponent.Key("type", StringComponent.STRING.instance()),
          new CustomObjectRecipeComponent.Key("min", NumberComponent.DOUBLE),
          new CustomObjectRecipeComponent.Key("max", NumberComponent.DOUBLE)));
  private static final CustomObjectRecipeComponent MODIFIER = new CustomObjectRecipeComponent(
      List.of(
          new CustomObjectRecipeComponent.Key("target", StringComponent.STRING.instance()),
          new CustomObjectRecipeComponent.Key("io", StringComponent.STRING.instance()),
          new CustomObjectRecipeComponent.Key("operation", StringComponent.STRING.instance()),
          new CustomObjectRecipeComponent.Key("value", NumberComponent.DOUBLE),
          new CustomObjectRecipeComponent.Key("affect_chance", BooleanComponent.BOOLEAN.instance(), true)));

  private static final RecipeKey<String> MACHINE = StringComponent.ID.instance().otherKey("machine");
  private static final RecipeKey<Integer> PRIORITY = NumberComponent.INT.otherKey("priority").optional(0);
  private static final RecipeKey<Integer> DURATION = NumberComponent.POSITIVE_INT.instance().otherKey("duration");
  private static final RecipeKey<Long> ENERGY = NumberComponent.NON_NEGATIVE_LONG.instance().otherKey("energy_per_tick")
      .optional(0L);
  private static final RecipeKey<String> FAILURE = StringComponent.STRING.instance().otherKey("failure_action")
      .optional("still");
  private static final RecipeKey<Long> SEED = NumberComponent.NON_NEGATIVE_LONG.instance().otherKey("random_seed")
      .optional(0L);
  private static final RecipeKey<List<List<CustomObjectRecipeComponent.Value>>> INPUTS = ITEM_ROW.asList()
      .withBounds(IntBounds.of(1, 256)).inputKey("inputs");
  private static final RecipeKey<List<List<CustomObjectRecipeComponent.Value>>> OUTPUTS = ITEM_ROW.asList()
      .withBounds(IntBounds.of(1, 256)).outputKey("outputs");
  private static final RecipeKey<FluidStack> FLUID_PER_TICK = FluidStackComponent.OPTIONAL_FLUID_STACK.instance()
      .otherKey("fluid_per_tick").defaultOptional();
  private static final RecipeKey<List<FluidStack>> FLUID_INPUTS = FluidStackComponent.FLUID_STACK.instance().asList()
      .withBounds(IntBounds.of(1, 256)).otherKey("fluid_inputs").defaultOptional();
  private static final RecipeKey<List<FluidStack>> FLUID_OUTPUTS = FluidStackComponent.FLUID_STACK.instance().asList()
      .withBounds(IntBounds.of(1, 256)).otherKey("fluid_outputs").defaultOptional();
  private static final RecipeKey<List<List<CustomObjectRecipeComponent.Value>>> INTERFACE_INPUTS = INTERFACE_INPUT
      .asList().withBounds(IntBounds.of(0, 16)).otherKey("interface_inputs").defaultOptional();
  private static final RecipeKey<List<CustomObjectRecipeComponent.Value>> SELECTOR_VALUE = SELECTORS
      .otherKey("selectors").defaultOptional();
  private static final RecipeKey<List<List<CustomObjectRecipeComponent.Value>>> MODIFIERS = MODIFIER.asList()
      .withBounds(IntBounds.of(0, 32)).otherKey("modifiers").defaultOptional();

  /**
   * Required keys must stay ahead of every optional key: KubeJS rejects the
   * schema at class-init otherwise.
   */
  public static final RecipeSchema SCHEMA = new RecipeSchema(
      MACHINE,
      DURATION,
      INPUTS,
      OUTPUTS,
      PRIORITY,
      ENERGY,
      FLUID_PER_TICK,
      FLUID_INPUTS,
      FLUID_OUTPUTS,
      FAILURE,
      SEED,
      INTERFACE_INPUTS,
      SELECTOR_VALUE,
      MODIFIERS).typeOverride(MachineRecipeRegistries.ITEM_SERIALIZER.getId())
      .uniqueIds(List.of(MACHINE, PRIORITY, DURATION, INPUTS, OUTPUTS));

  private MachineItemRecipeKubeJSSchema() {
  }

  public static ResourceLocation id() {
    return ResourceLocation.fromNamespaceAndPath(ModularMachine.MODID, "machine");
  }

  public static void register(RecipeSchemaRegistry registry) {
    registry.register(id(), SCHEMA);
  }

  public static void registerMappings(RecipeMappingRegistry registry) {
    registry.register("machine", id());
  }
}
