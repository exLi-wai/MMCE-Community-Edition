package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.neoforged.neoforge.common.conditions.ICondition;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.resource.ContextAwareReloadListener;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * IO/validation -> server-owned descriptor capture -> pure compilation ->
 * barrier -> atomic apply.
 */
public final class MachineDefinitionReloadListener extends ContextAwareReloadListener implements PreparableReloadListener {
  private static final Logger LOGGER = LogUtils.getLogger();
  private final RecipeManager target;
  private final Supplier<Path> configurationDirectory;

  MachineDefinitionReloadListener(RecipeManager target) {
    this(target, MachineDefinitionConfig::ensureDirectory);
  }

  MachineDefinitionReloadListener(RecipeManager target, Supplier<Path> configurationDirectory) {
    this.target = target;
    this.configurationDirectory = configurationDirectory;
  }

  private record Fixture<T>(JsonElement root, T descriptors) {
  }

  private record Inputs<T>(
      Map<ResourceLocation, JsonElement> roots,
      T descriptors,
      Map<ResourceLocation, Fixture<T>> fixtures) {
  }

  private record Captured(
      Inputs<StateOrientationAdapters.RegistrySnapshot> inputs,
      MachineDefinitionDefaults defaults) {
  }

  private record Candidate(
      Map<ResourceLocation, MachineDefinition> definitions,
      MachineDefinitionDefaults defaults,
      MachineDefinitionScripts.Snapshot scripts) {
  }

  private record Prepared(Candidate candidate, Throwable failure) {
  }

  @Override
  public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources, ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler, Executor backgroundExecutor, Executor gameExecutor) {
    var server = ServerLifecycleHooks.getCurrentServer();
    var scripts = MachineDefinitionScripts.snapshot();
    return CompletableFuture
        .supplyAsync(
            () -> MachineDefinitionScripts.compile(scripts, () -> readInputs(resources, scripts)),
            backgroundExecutor)
        .thenApplyAsync(MachineDefinitionReloadListener::capture, gameExecutor)
        .thenApplyAsync(
            captured -> new Candidate(
                MachineDefinitionScripts.compile(scripts, () -> compile(captured)),
                captured.defaults(),
                scripts),
            backgroundExecutor)
        .handle((candidate, failure) -> new Prepared(candidate, unwrap(failure))).thenCompose(barrier::wait)
        .thenAcceptAsync(prepared -> apply(server, prepared), gameExecutor);
  }

  private void apply(MinecraftServer server, Prepared prepared) {
    if (server != ServerLifecycleHooks.getCurrentServer() || (server != null && server.isStopped())) {
      throw new IllegalStateException("Server changed during machine definition reload");
    }
    var candidate = prepared.candidate();
    var failure = prepared.failure();
    if (failure == null
        && (!candidate.defaults().equals(MachineDefinitionDefaults.capture())
            || !MachineDefinitionScripts.current(candidate.scripts()))) {
      failure = new IllegalStateException(
          "Machine defaults or script declarations changed during reload; reload again to publish a consistent generation");
    }
    if (failure != null) {
      if (failure instanceof Error || !StructureService.keepDefinitionsAfterRejection(server, target, failure)) {
        throw new CompletionException(failure);
      }
      LOGGER.warn("Keeping the previous machine definition generation after reload rejected the candidate", failure);
      return;
    }
    StructureService.replaceDefinitions(server, target, candidate.definitions());
  }

  private static Throwable unwrap(Throwable failure) {
    var cause = failure;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  /**
   * No partial publication: callers receive a complete immutable candidate or an
   * exception.
   */
  public static Map<ResourceLocation, MachineDefinition> compileCandidate(Map<ResourceLocation, JsonElement> data, StateOrientationAdapters.RegistrySnapshot snapshot) {
    return compileCandidate(data, snapshot, MachineDefinitionDefaults.capture());
  }

  public static Map<ResourceLocation, MachineDefinition> compileCandidate(Map<ResourceLocation, JsonElement> data, StateOrientationAdapters.RegistrySnapshot snapshot, MachineDefinitionDefaults defaults) {
    Map<ResourceLocation, MachineDefinition> candidate = new LinkedHashMap<>();
    data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
      if (!entry.getValue().isJsonObject()) {
        throw new JsonParseException(entry.getKey() + ": $: Expected object");
      }
      MachineDefinition definition = MachineDefinitionParser
          .parse(entry.getKey(), entry.getValue().getAsJsonObject(), snapshot, defaults);
      candidate.put(entry.getKey(), definition);
      var compilation = definition.orientationCompilation();
      if (!compilation.diagnostics().isEmpty()) {
        LOGGER.warn(
            "Machine {} supports {}/{} requested orientations; {} diagnostics retained (truncated={})",
            entry.getKey(),
            Integer.bitCount(compilation.allowedMask()),
            Integer.bitCount(definition.orientationPolicy().requestedMask()),
            compilation.diagnostics().size(),
            compilation.diagnosticsTruncated());
        for (var diagnostic : compilation.diagnostics()) {
          LOGGER.debug("Machine orientation detail: {}", diagnostic);
        }
      }
    });
    return Map.copyOf(candidate);
  }

  /**
   * A malformed file aborts the candidate instead of silently deleting its old
   * definition.
   */
  private Inputs<List<StateOrientationAdapters.BlockReference>> readInputs(ResourceManager resources, MachineDefinitionScripts.Snapshot scripts) {
    Map<ResourceLocation, JsonElement> roots;
    try {
      roots = MachineDefinitionFiles.readDefinitions(configurationDirectory.get(), null).roots();
    } catch (IOException | RuntimeException exception) {
      throw new JsonParseException("Failed reading machine definitions: " + exception.getMessage(), exception);
    }
    var fixtures = readGameTestFixtures(resources);
    if (fixtures.isEmpty()) {
      MachineDefinitionScripts.applyInPlace(roots, scripts);
    } else {
      var scriptRoots = new LinkedHashMap<>(fixtures);
      scriptRoots.putAll(roots);
      MachineDefinitionScripts.applyInPlace(scriptRoots, scripts);
    }
    var configuration = MachineDefinitionParser.collectReferences(roots);
    var budget = configuration.cost();
    var fixtureInputs = new LinkedHashMap<ResourceLocation, Fixture<List<StateOrientationAdapters.BlockReference>>>();
    for (var entry : fixtures.entrySet()) {
      var id = entry.getKey();
      var root = entry.getValue();
      try {
        var collected = MachineDefinitionParser.collectReferences(Map.of(id, root));
        budget = budget.plus(collected.cost(), id + ": $");
        fixtureInputs.put(id, new Fixture<>(root, collected.references()));
      } catch (RuntimeException invalid) {
        skipFixture(id, invalid);
      }
    }
    return new Inputs<>(roots, configuration.references(), fixtureInputs);
  }

  private static Captured capture(Inputs<List<StateOrientationAdapters.BlockReference>> inputs) {
    var registry = StateOrientationAdapters.capture(inputs.descriptors());
    var fixtures = new LinkedHashMap<ResourceLocation, Fixture<StateOrientationAdapters.RegistrySnapshot>>();
    // A bad fixture property must not invalidate a block descriptor shared by real
    // machines.
    inputs.fixtures().forEach((id, fixture) -> {
      try {
        fixtures.put(id, new Fixture<>(fixture.root(), StateOrientationAdapters.capture(fixture.descriptors())));
      } catch (JsonParseException | IllegalArgumentException invalid) {
        skipFixture(id, invalid);
      }
    });
    return new Captured(new Inputs<>(inputs.roots(), registry, fixtures), MachineDefinitionDefaults.capture());
  }

  private static Map<ResourceLocation, MachineDefinition> compile(Captured captured) {
    var inputs = captured.inputs();
    var configuration = compileCandidate(inputs.roots(), inputs.descriptors(), captured.defaults());
    if (inputs.fixtures().isEmpty()) {
      return configuration;
    }
    var definitions = new LinkedHashMap<>(configuration);
    inputs.fixtures().forEach((id, fixture) -> {
      Map<ResourceLocation, MachineDefinition> compiled;
      try {
        compiled = compileCandidate(Map.of(id, fixture.root()), fixture.descriptors(), captured.defaults());
      } catch (RuntimeException invalid) {
        skipFixture(id, invalid);
        return;
      }
      if (definitions.putIfAbsent(id, compiled.get(id)) != null) {
        throw new JsonParseException(id + ": $: Duplicate machine definition from GameTest fixtures");
      }
    });
    return Map.copyOf(definitions);
  }

  private static void skipFixture(ResourceLocation id, RuntimeException invalid) {
    if (invalid instanceof CancellationException) {
      throw invalid;
    }
    LOGGER.warn("Skipping invalid GameTest machine definition fixture {}", id, invalid);
  }

  private static Map<ResourceLocation, JsonElement> readGameTestFixtures(ResourceManager resources) {
    boolean gameTestEnabled = Arrays.stream(System.getProperty("neoforge.enabledGameTestNamespaces", "").split(","))
        .map(String::trim).anyMatch(ModularMachine.MODID::equals);
    if (!gameTestEnabled) {
      return Map.of();
    }
    var converter = new FileToIdConverter("mmcece/machines", ".json");
    Map<ResourceLocation, JsonElement> result = new TreeMap<>();
    var fixtures = converter.listMatchingResources(resources);
    for (var entry : fixtures.entrySet()) {
      ResourceLocation id = converter.fileToId(entry.getKey());
      if (!id.getPath().startsWith("test_") && !id.getPath().equals("transformer")) {
        continue;
      }
      try (var reader = entry.getValue().openAsReader()) {
        var fixture = JsonParser.parseReader(reader).getAsJsonObject();
        var conditions = fixture.remove("neoforge:conditions");
        if (conditions == null
            || ICondition.LIST_CODEC.parse(JsonOps.INSTANCE, conditions).getOrThrow().stream()
                .allMatch(condition -> condition.test(ICondition.IContext.EMPTY))) {
          result.put(id, fixture);
        }
      } catch (IOException | RuntimeException exception) {
        LOGGER.warn("Skipping invalid GameTest machine definition fixture {}", entry.getKey(), exception);
      }
    }
    return result;
  }

}
