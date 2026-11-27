package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonObject;
import com.lw.modularmachine.api.recipe.BufferedAmountRecipeData;
import com.lw.modularmachine.api.recipe.CompressedAirRecipeData;
import com.lw.modularmachine.api.recipe.ConstellationRecipeData;
import com.lw.modularmachine.api.recipe.EmberRecipeData;
import com.lw.modularmachine.api.recipe.EnvironmentRecipeData;
import com.lw.modularmachine.api.recipe.EnvironmentRequirement;
import com.lw.modularmachine.api.recipe.KeyedAmountRecipeData;
import com.lw.modularmachine.api.recipe.LaserRecipeData;
import com.lw.modularmachine.api.recipe.MeteorRecipeData;
import com.lw.modularmachine.api.recipe.WorldPoolRecipeData;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization.NativeResource;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceBuilderDeclarations;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@ApiStatus.Internal
public abstract class KubeJSResourceRecipeBuilder<B extends KubeJSResourceRecipeBuilder<B>> {
  private final Map<ResourceLocation, RecipeResources.Entry<?>> resourceDeclarations = new LinkedHashMap<>();
  private final Set<ResourceLocation> overrides = new LinkedHashSet<>();

  abstract void requireMutable();

  abstract HolderLookup.Provider resourceRegistries();

  abstract B clearLastResourceRow();

  void requireResourceOverride(ResourceLocation id) {
  }

  /**
   * Explicit resource declarations replace any dedicated rows owned by the same
   * descriptor.
   */
  final RecipeResources resources(RecipeResources.Entry<?>... ordinary) {
    var merged = new LinkedHashMap<ResourceLocation, RecipeResources.Entry<?>>();
    for (var entry : ordinary) {
      merged.put(entry.type().id(), entry);
    }
    merged.putAll(resourceDeclarations);
    return new RecipeResources(List.copyOf(merged.values()));
  }

  final Map<ResourceLocation, NativeResource> captureAdapterResources() {
    var captured = new LinkedHashMap<ResourceLocation, NativeResource>();
    resourceDeclarations.forEach((id, entry) -> captured.put(id, new NativeResource(entry, overrides.contains(id))));
    return captured;
  }

  /**
   * Uses only a Java-installed descriptor and its complete owned native field
   * declaration.
   */
  public B setResource(String resourceId, JsonObject declaration) {
    requireMutable();
    var id = KubeJSRecipeArguments.identifier(resourceId, "recipe resource");
    requireResourceOverride(id);
    var entry = RecipeResourceBuilderDeclarations.decode(id, declaration, resourceRegistries());
    overrides.add(id);
    return putResource(entry);
  }

  public B addLaserInput(Number amount) {
    requireMutable();
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            RecipeResourceDefinitions.LASER,
            new LaserRecipeData(KubeJSRecipeArguments.integer(amount, "laser", 1, Long.MAX_VALUE))));
  }

  public B addLaserInput(String amount) {
    requireMutable();
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            RecipeResourceDefinitions.LASER,
            new LaserRecipeData(KubeJSRecipeArguments.integer(amount, "laser", 1, Long.MAX_VALUE))));
  }

  public B addEmberInput(Number amount) {
    requireMutable();
    return putResource(
        RecipeResourceBuilderDeclarations.capture(RecipeResourceDefinitions.EMBER, EmberRecipeData.of(amount)));
  }

  public B addManaInput(Number amount) {
    requireMutable();
    var previous = bufferedDeclaration(RecipeResourceDefinitions.MANA);
    long input = KubeJSRecipeArguments.integer(amount, "mana input", 1, Long.MAX_VALUE);
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            RecipeResourceDefinitions.MANA,
            new BufferedAmountRecipeData(Math.addExact(previous.input(), input), previous.output())));
  }

  public B addManaOutput(Number amount) {
    requireMutable();
    var previous = bufferedDeclaration(RecipeResourceDefinitions.MANA);
    long output = KubeJSRecipeArguments.integer(amount, "mana output", 1, Long.MAX_VALUE);
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            RecipeResourceDefinitions.MANA,
            new BufferedAmountRecipeData(previous.input(), Math.addExact(previous.output(), output))));
  }

  public B addAuraInput(Number amount) {
    requireMutable();
    var previous = bufferedDeclaration(RecipeResourceDefinitions.AURA);
    long input = KubeJSRecipeArguments.integer(amount, "aura input", 1, Long.MAX_VALUE);
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            RecipeResourceDefinitions.AURA,
            new BufferedAmountRecipeData(Math.addExact(previous.input(), input), 0)));
  }

  public B addAuraOutput(String type, Number amount, Number maximum) {
    return chunkPool(RecipeResourceDefinitions.AURA_WORLD, true, type, amount, false, 0, maximum);
  }

  public B addAuraOutput(String type, Number amount) {
    return addAuraOutput(type, amount, Integer.MAX_VALUE);
  }

  public B addWillInput(Number amount) {
    return addWillInput("raw", amount);
  }

  public B addWillInput(String type, Number amount) {
    return addWillInput(type, amount, 0);
  }

  public B addWillInput(String type, Number amount, Number minimum) {
    return chunkPoolInUnits(RecipeResourceDefinitions.WILL, false, type, amount, false, 0, minimum);
  }

  public B addWillOutput(String type, Number amount) {
    return addWillOutput(type, amount, Integer.MAX_VALUE);
  }

  public B addWillOutput(String type, Number amount, Number maximum) {
    return chunkPoolInUnits(RecipeResourceDefinitions.WILL, true, type, amount, false, 0, maximum);
  }

  public B addMultiChunkWillInput(String type, int radius, Number amount, Number minimum) {
    return chunkPoolInUnits(RecipeResourceDefinitions.WILL, false, type, amount, true, radius, minimum);
  }

  public B addMultiChunkWillOutput(String type, int radius, Number amount, Number maximum) {
    return chunkPoolInUnits(RecipeResourceDefinitions.WILL, true, type, amount, true, radius, maximum);
  }

  public B addVisInput(Number amount) {
    return addVisInput(amount, 0);
  }

  public B addVisInput(Number amount, int radius) {
    return addVisInput(amount, radius, 0);
  }

  public B addVisInput(Number amount, int radius, Number minimum) {
    return chunkPoolInUnits(RecipeResourceDefinitions.VIS, false, "", amount, false, radius, minimum);
  }

  public B addVisOutput(Number amount) {
    return addVisOutput(amount, 0);
  }

  public B addVisOutput(Number amount, int radius) {
    return addVisOutput(amount, radius, 500);
  }

  public B addVisOutput(Number amount, int radius, Number maximum) {
    return chunkPoolInUnits(RecipeResourceDefinitions.VIS, true, "", amount, false, radius, maximum);
  }

  public B addFluxInput(Number amount) {
    return addFluxInput(amount, 0);
  }

  public B addFluxInput(Number amount, int radius) {
    return addFluxInput(amount, radius, 0);
  }

  public B addFluxInput(Number amount, int radius, Number minimum) {
    return chunkPoolInUnits(RecipeResourceDefinitions.FLUX, false, "", amount, false, radius, minimum);
  }

  public B addFluxOutput(Number amount) {
    return addFluxOutput(amount, 0);
  }

  public B addFluxOutput(Number amount, int radius) {
    return addFluxOutput(amount, radius, 500);
  }

  public B addFluxOutput(Number amount, int radius, Number maximum) {
    return chunkPoolInUnits(RecipeResourceDefinitions.FLUX, true, "", amount, false, radius, maximum);
  }

  private B chunkPoolInUnits(RecipeResourceType<WorldPoolRecipeData> type, boolean output, String kind, Number amount, boolean multichunk, int radius, Number limit) {
    return chunkPool(
        type,
        output,
        kind,
        toMilli(amount, "chunk amount"),
        multichunk,
        radius,
        nonNegativeMilli(limit, "chunk limit"));
  }

  private B chunkPool(RecipeResourceType<WorldPoolRecipeData> type, boolean output, String kind, Number amount, boolean multichunk, int radius, Number limit) {
    requireMutable();
    var entry = resourceDeclarations.get(type.id());
    var previous = entry == null ? WorldPoolRecipeData.NONE : type.dataClass().cast(entry.data());
    var prior = output ? previous.output() : previous.input();
    if (prior.isPresent()) {
      throw new IllegalArgumentException("Only one declaration per chunk resource direction: " + type.id());
    }
    var claim = Optional.of(
        new WorldPoolRecipeData.Claim(
            kind,
            KubeJSRecipeArguments.integer(amount, "chunk amount", 1, Long.MAX_VALUE),
            multichunk,
            radius,
            KubeJSRecipeArguments.integer(limit, "chunk limit", 0, Long.MAX_VALUE)));
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            type,
            output ? new WorldPoolRecipeData(previous.input(), claim) : new WorldPoolRecipeData(
                claim,
                previous.output())));
  }

  private BufferedAmountRecipeData bufferedDeclaration(RecipeResourceType<BufferedAmountRecipeData> type) {
    var previous = resourceDeclarations.get(type.id());
    return previous == null ? BufferedAmountRecipeData.NONE : type.dataClass().cast(previous.data());
  }

  public B addLifeEssenceInput(Number amount) {
    requireMutable();
    var type = RecipeResourceDefinitions.LIFE_ESSENCE;
    var previous = bufferedDeclaration(type);
    long quantity = KubeJSRecipeArguments.integer(amount, "life essence", 1, Long.MAX_VALUE);
    return putResource(
        RecipeResourceBuilderDeclarations
            .capture(type, new BufferedAmountRecipeData(Math.addExact(previous.input(), quantity), previous.output())));
  }

  public B addLifeEssenceOutput(Number amount) {
    requireMutable();
    var type = RecipeResourceDefinitions.LIFE_ESSENCE;
    var previous = bufferedDeclaration(type);
    long quantity = KubeJSRecipeArguments.integer(amount, "life essence", 1, Long.MAX_VALUE);
    return putResource(
        RecipeResourceBuilderDeclarations
            .capture(type, new BufferedAmountRecipeData(previous.input(), Math.addExact(previous.output(), quantity))));
  }

  public B addAspectInput(String aspect, Number amount) {
    return addKeyed(RecipeResourceDefinitions.ESSENTIA, aspect, amount, false);
  }

  public B addAspectOutput(String aspect, Number amount) {
    return addKeyed(RecipeResourceDefinitions.ESSENTIA, aspect, amount, true);
  }

  public B addEssentiaInput(String aspect, Number amount) {
    return addAspectInput(aspect, amount);
  }

  public B addEssentiaOutput(String aspect, Number amount) {
    return addAspectOutput(aspect, amount);
  }

  public B addStarlightInput(Number amount) {
    return addStarlightInput(amount, KeyedAmountRecipeData.ANY_STARLIGHT.toString());
  }

  public B addStarlightInput(Number amount, String source) {
    return addKeyed(RecipeResourceDefinitions.STARLIGHT, source, amount, false);
  }

  public B addStarlightOutput(Number amount) {
    return addStarlightOutput(amount, "mmcece:unattuned");
  }

  public B addStarlightOutput(Number amount, String constellation) {
    return addKeyed(RecipeResourceDefinitions.STARLIGHT, constellation, amount, true);
  }

  private B addKeyed(RecipeResourceType<KeyedAmountRecipeData> type, String id, Number amount, boolean output) {
    requireMutable();
    var captured = resourceDeclarations.get(type.id());
    var previous = captured == null ? KeyedAmountRecipeData.NONE : type.dataClass().cast(captured.data());
    var quantities = new LinkedHashMap<>(output ? previous.output() : previous.input());
    long units = type == RecipeResourceDefinitions.STARLIGHT ? toMilli(amount, "starlight") : KubeJSRecipeArguments
        .integer(amount, "essentia", 1, Long.MAX_VALUE);
    quantities.merge(ResourceLocation.parse(id), units, Math::addExact);
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            type,
            output ? new KeyedAmountRecipeData(previous.input(), quantities) : new KeyedAmountRecipeData(
                quantities,
                previous.output())));
  }

  public B addConstellationInput(String constellation) {
    requireMutable();
    var type = RecipeResourceDefinitions.CONSTELLATION;
    var captured = resourceDeclarations.get(type.id());
    var active = new ArrayList<>(
        captured == null ? List.<ResourceLocation>of() : type.dataClass().cast(captured.data()).active());
    active.add(ResourceLocation.parse(constellation));
    return putResource(RecipeResourceBuilderDeclarations.capture(type, new ConstellationRecipeData(active)));
  }

  public B addMeteorOutput(String catalyst) {
    requireMutable();
    var type = RecipeResourceDefinitions.METEOR;
    if (resourceDeclarations.containsKey(type.id())) {
      throw new IllegalArgumentException("Only one meteor catalyst is allowed per recipe");
    }
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            type,
            new MeteorRecipeData(Optional.of(new MeteorRecipeData.Launch(ResourceLocation.parse(catalyst), 1)))));
  }

  public B addHeatInput(Number amount) {
    requireMutable();
    var previous = bufferedDeclaration(RecipeResourceDefinitions.HEAT);
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            RecipeResourceDefinitions.HEAT,
            new BufferedAmountRecipeData(Math.addExact(previous.input(), milliheat(amount)), previous.output())));
  }

  public B addHeatOutput(Number amount) {
    requireMutable();
    var previous = bufferedDeclaration(RecipeResourceDefinitions.HEAT);
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            RecipeResourceDefinitions.HEAT,
            new BufferedAmountRecipeData(previous.input(), Math.addExact(previous.output(), milliheat(amount)))));
  }

  private static long milliheat(Number amount) {
    return toMilli(amount, "heat");
  }

  private static long toMilli(Number amount, String resource) {
    long exact = nonNegativeMilli(amount, resource);
    if (exact == 0) {
      throw new IllegalArgumentException(resource + " must be positive");
    }
    return exact;
  }

  private static long nonNegativeMilli(Number amount, String resource) {
    try {
      long exact = new BigDecimal(amount.toString()).movePointRight(3).longValueExact();
      if (exact < 0) {
        throw new IllegalArgumentException(resource + " must not be negative");
      }
      return exact;
    } catch (ArithmeticException | NumberFormatException invalid) {
      throw new IllegalArgumentException(
          resource + " requires at most three decimal places and a representable amount",
          invalid);
    }
  }

  public B addCompressedAirInput(float pressure, Number air) {
    requireMutable();
    var previous = compressedAirDeclaration();
    var amount = compressedAirAmount(pressure, air, previous.input());
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            RecipeResourceDefinitions.COMPRESSED_AIR,
            new CompressedAirRecipeData(Optional.of(amount), previous.output())));
  }

  public B addCompressedAirOutput(float pressure, Number air) {
    requireMutable();
    var previous = compressedAirDeclaration();
    var amount = compressedAirAmount(pressure, air, previous.output());
    return putResource(
        RecipeResourceBuilderDeclarations.capture(
            RecipeResourceDefinitions.COMPRESSED_AIR,
            new CompressedAirRecipeData(previous.input(), Optional.of(amount))));
  }

  private CompressedAirRecipeData compressedAirDeclaration() {
    var previous = resourceDeclarations.get(RecipeResourceDefinitions.COMPRESSED_AIR.id());
    return previous == null ? CompressedAirRecipeData.NONE : RecipeResourceDefinitions.COMPRESSED_AIR.dataClass()
        .cast(previous.data());
  }

  private static CompressedAirRecipeData.Amount compressedAirAmount(float pressure, Number air, Optional<CompressedAirRecipeData.Amount> previous) {
    long amount = KubeJSRecipeArguments.integer(air, "compressed air", 1, Long.MAX_VALUE);
    if (previous.isPresent()) {
      var prior = previous.orElseThrow();
      if (Float.compare(prior.pressure(), pressure) != 0) {
        throw new IllegalArgumentException("Compressed air declarations of one direction must use the same pressure");
      }
      amount = Math.addExact(amount, prior.air());
    }
    return new CompressedAirRecipeData.Amount(pressure, amount);
  }

  public B setDimension(String... dimensions) {
    return addEnvironment(
        new EnvironmentRequirement.Dimension(
            Arrays.stream(dimensions).map(id -> KubeJSRecipeArguments.identifier(id, "dimension")).toList()));
  }

  public B setBiome(String... biomes) {
    return addEnvironment(
        new EnvironmentRequirement.Biome(
            Arrays.stream(biomes).map(id -> KubeJSRecipeArguments.identifier(id, "biome")).toList()));
  }

  public B setTime(int begin, int end) {
    return addEnvironment(new EnvironmentRequirement.Time(begin, end));
  }

  public B setMoonPhase(int... phases) {
    return addEnvironment(new EnvironmentRequirement.MoonPhase(Arrays.stream(phases).boxed().toList()));
  }

  public B setAltitude(int begin, int end) {
    return addEnvironment(new EnvironmentRequirement.Altitude(begin, end));
  }

  public B setWeather(String weather) {
    return addEnvironment(
        new EnvironmentRequirement.Weather(
            EnvironmentRequirement.Weather.Kind.valueOf(weather.toUpperCase(Locale.ROOT))));
  }

  private B addEnvironment(EnvironmentRequirement requirement) {
    requireMutable();
    var type = RecipeResourceDefinitions.ENVIRONMENT;
    var previous = resourceDeclarations.get(type.id());
    var conditions = new ArrayList<EnvironmentRequirement>();
    if (previous != null) {
      conditions.addAll(type.dataClass().cast(previous.data()).requirements());
    }
    conditions.add(requirement);
    return putResource(RecipeResourceBuilderDeclarations.capture(type, new EnvironmentRecipeData(conditions)));
  }

  private B putResource(RecipeResources.Entry<?> entry) {
    resourceDeclarations.put(entry.type().id(), entry);
    return clearLastResourceRow();
  }
}
