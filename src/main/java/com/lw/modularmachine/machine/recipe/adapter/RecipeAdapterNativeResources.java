package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.BufferedAmountRecipeData;
import com.lw.modularmachine.api.recipe.CompressedAirRecipeData;
import com.lw.modularmachine.api.recipe.ConstellationRecipeData;
import com.lw.modularmachine.api.recipe.EmberRecipeData;
import com.lw.modularmachine.api.recipe.EnvironmentRecipeData;
import com.lw.modularmachine.api.recipe.KeyedAmountRecipeData;
import com.lw.modularmachine.api.recipe.LaserRecipeData;
import com.lw.modularmachine.api.recipe.MeteorRecipeData;
import com.lw.modularmachine.api.recipe.WorldPoolRecipeData;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization.NativeResource;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceBuilderDeclarations;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class RecipeAdapterNativeResources {
  private static final Set<ResourceLocation> LEGACY_ROW_RESOURCES = Set.of(
      RecipeResourceDefinitions.ITEM.id(),
      RecipeResourceDefinitions.FLUID.id(),
      RecipeResourceDefinitions.ENERGY.id(),
      RecipeResourceDefinitions.CHEMICAL.id());

  private RecipeAdapterNativeResources() {
  }

  public static void requireCustomizable(ResourceLocation id) {
    if (LEGACY_ROW_RESOURCES.contains(id)) {
      throw new IllegalArgumentException("Use the adapter's row and energy methods for " + id);
    }
  }

  static void apply(JsonObject target, Map<ResourceLocation, NativeResource> declarations, HolderLookup.Provider registries) {
    for (var declaration : declarations.entrySet()) {
      requireCustomizable(declaration.getKey());
      var addition = RecipeResourceBuilderDeclarations.decode(
          declaration.getKey(),
          JsonParser.parseString(declaration.getValue().encoded()).getAsJsonObject(),
          registries);
      var result = declaration.getValue().replace() ? addition : merge(target, addition, registries);
      RecipeResourceBuilderDeclarations.apply(target, result, registries);
    }
  }

  private static <D> RecipeResources.Entry<D> merge(JsonObject target, RecipeResources.Entry<D> addition, HolderLookup.Provider registries) {
    var type = addition.type();
    var original = new JsonObject();
    for (String field : RecipeResourceDefinitions.fields(type)) {
      if (target.has(field)) {
        original.add(field, target.get(field));
      }
    }
    if (original.size() == 0) {
      return addition;
    }
    var ops = registries.createSerializationContext(JsonOps.INSTANCE);
    D source = type.nativeCodec().codec().parse(ops, original).getOrThrow();
    D combined = type.dataClass().cast(mergeData(type.id(), source, addition.data()));
    return RecipeResourceBuilderDeclarations.capture(type, combined);
  }

  private static Object mergeData(ResourceLocation id, Object source, Object addition) {
    return switch (addition) {
      case BufferedAmountRecipeData next -> {
        var previous = (BufferedAmountRecipeData) source;
        yield new BufferedAmountRecipeData(
            Math.addExact(previous.input(), next.input()),
            Math.addExact(previous.output(), next.output()));
      }
      case LaserRecipeData next -> new LaserRecipeData(Math.addExact(((LaserRecipeData) source).input(), next.input()));
      case EmberRecipeData next ->
        new EmberRecipeData(Math.addExact(((EmberRecipeData) source).micros(), next.micros()));
      case CompressedAirRecipeData next -> {
        var previous = (CompressedAirRecipeData) source;
        yield new CompressedAirRecipeData(
            mergeAir(previous.input(), next.input()),
            mergeAir(previous.output(), next.output()));
      }
      case WorldPoolRecipeData next -> {
        var previous = (WorldPoolRecipeData) source;
        yield new WorldPoolRecipeData(
            single(id, "input", previous.input(), next.input()),
            single(id, "output", previous.output(), next.output()));
      }
      case KeyedAmountRecipeData next -> {
        var previous = (KeyedAmountRecipeData) source;
        yield new KeyedAmountRecipeData(
            mergeAmounts(previous.input(), next.input()),
            mergeAmounts(previous.output(), next.output()));
      }
      case EnvironmentRecipeData next ->
        new EnvironmentRecipeData(append(((EnvironmentRecipeData) source).requirements(), next.requirements()));
      case ConstellationRecipeData next ->
        new ConstellationRecipeData(append(((ConstellationRecipeData) source).active(), next.active()));
      case MeteorRecipeData next ->
        new MeteorRecipeData(single(id, "output", ((MeteorRecipeData) source).output(), next.output()));
      default -> throw new IllegalArgumentException("No additive adapter rule for recipe resource " + id);
    };
  }

  private static Optional<CompressedAirRecipeData.Amount> mergeAir(Optional<CompressedAirRecipeData.Amount> source, Optional<CompressedAirRecipeData.Amount> addition) {
    if (source.isEmpty() || addition.isEmpty()) {
      return source.or(() -> addition);
    }
    var previous = source.orElseThrow();
    var next = addition.orElseThrow();
    if (Float.compare(previous.pressure(), next.pressure()) != 0) {
      throw new IllegalArgumentException(
          "Adapter compressed air declarations of one direction must use the same pressure");
    }
    return Optional
        .of(new CompressedAirRecipeData.Amount(previous.pressure(), Math.addExact(previous.air(), next.air())));
  }

  private static <T> Optional<T> single(ResourceLocation id, String direction, Optional<T> source, Optional<T> addition) {
    if (source.isPresent() && addition.isPresent()) {
      throw new IllegalArgumentException("Adapter conflicts with existing " + id + " " + direction);
    }
    return source.or(() -> addition);
  }

  private static Map<ResourceLocation, Long> mergeAmounts(Map<ResourceLocation, Long> source, Map<ResourceLocation, Long> addition) {
    var result = new LinkedHashMap<>(source);
    addition.forEach((key, amount) -> result.merge(key, amount, Math::addExact));
    return result;
  }

  private static <T> List<T> append(List<T> source, List<T> addition) {
    var result = new ArrayList<>(source);
    result.addAll(addition);
    return result;
  }
}
