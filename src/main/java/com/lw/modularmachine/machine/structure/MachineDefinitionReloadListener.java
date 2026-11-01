package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.resource.ContextAwareReloadListener;
import org.slf4j.Logger;

/**
 * IO/validation -> server-owned descriptor capture -> pure compilation ->
 * barrier -> atomic apply.
 */
public final class MachineDefinitionReloadListener extends ContextAwareReloadListener implements PreparableReloadListener {
  private static final Logger LOGGER = LogUtils.getLogger();

  private record Input(
      Map<ResourceLocation, JsonElement> roots,
      List<StateOrientationAdapters.BlockReference> references) {
  }

  private record Captured(
      Map<ResourceLocation, JsonElement> roots,
      StateOrientationAdapters.RegistrySnapshot registry) {
  }

  @Override
  public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources, ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler, Executor backgroundExecutor, Executor gameExecutor) {
    return CompletableFuture.supplyAsync(() -> readResources(resources), backgroundExecutor)
        .thenApplyAsync(roots -> new Input(roots, MachineDefinition.blockReferences(roots)), backgroundExecutor)
        .thenCompose(
            input -> CompletableFuture.supplyAsync(
                () -> new Captured(input.roots(), StateOrientationAdapters.capture(input.references())),
                gameExecutor))
        .thenApplyAsync(captured -> compileCandidate(captured.roots(), captured.registry()), backgroundExecutor)
        .thenCompose(barrier::wait).thenAcceptAsync(StructureService::replaceDefinitions, gameExecutor);
  }

  /**
   * No partial publication: callers receive a complete immutable candidate or an
   * exception.
   */
  public static Map<ResourceLocation, MachineDefinition> compileCandidate(Map<ResourceLocation, JsonElement> data, StateOrientationAdapters.RegistrySnapshot snapshot) {
    Map<ResourceLocation, MachineDefinition> candidate = new LinkedHashMap<>();
    data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
      if (!entry.getValue().isJsonObject()) {
        throw new JsonParseException(entry.getKey() + ": $: Expected object");
      }
      MachineDefinition definition = MachineDefinition
          .parse(entry.getKey(), entry.getValue().getAsJsonObject(), snapshot);
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
  private static Map<ResourceLocation, JsonElement> readResources(ResourceManager resources) {
    var converter = new FileToIdConverter("mmcece/machines", ".json");
    Map<ResourceLocation, JsonElement> result = new LinkedHashMap<>();
    readGameTestFixtures(resources, converter, result);
    Path root = MachineDefinitionConfig.directory();
    if (!Files.isDirectory(root)) {
      throw new JsonParseException("Machine definition directory is missing: " + root);
    }
    try (var paths = Files.walk(root)) {
      var files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .filter(path -> !path.getFileName().toString().endsWith(".var.json")).limit(1025)
          .sorted(Comparator.comparing(path -> root.relativize(path).toString())).toList();
      if (files.size() > 1024) {
        throw new JsonParseException("At most 1024 machine definitions per reload");
      }
      long totalBytes = 0;
      for (Path file : files) {
        long size = Files.size(file);
        if (size > 1_048_576L || (totalBytes = Math.addExact(totalBytes, size)) > 32_000_000L) {
          throw new JsonParseException(file + ": Machine definition size limit exceeded");
        }
        ResourceLocation id = MachineDefinitionConfig.id(root.relativize(file));
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
          put(result, id, JsonParser.parseReader(reader), file.toString());
        } catch (IOException | IllegalArgumentException | JsonParseException exception) {
          throw new JsonParseException(id + ": $: Failed reading " + file, exception);
        }
      }
    } catch (IOException exception) {
      throw new JsonParseException("Failed scanning machine definition directory " + root, exception);
    }
    return Map.copyOf(result);
  }

  private static void readGameTestFixtures(ResourceManager resources, FileToIdConverter converter, Map<ResourceLocation, JsonElement> result) {
    boolean gameTestEnabled = Arrays.stream(System.getProperty("neoforge.enabledGameTestNamespaces", "").split(","))
        .map(String::trim).anyMatch(ModularMachine.MODID::equals);
    if (!gameTestEnabled) {
      return;
    }
    var fixtures = converter.listMatchingResources(resources);
    for (var entry : fixtures.entrySet()) {
      ResourceLocation id = converter.fileToId(entry.getKey());
      if (!id.getPath().startsWith("test_")) {
        continue;
      }
      try (var reader = entry.getValue().openAsReader()) {
        put(result, id, JsonParser.parseReader(reader), entry.getKey().toString());
      } catch (IOException | IllegalArgumentException | JsonParseException exception) {
        throw new JsonParseException(id + ": $: Failed reading GameTest fixture " + entry.getKey(), exception);
      }
    }
  }

  private static void put(Map<ResourceLocation, JsonElement> target, ResourceLocation id, JsonElement value, String source) {
    if (target.putIfAbsent(id, value) != null) {
      throw new JsonParseException(id + ": $: Duplicate machine definition from " + source);
    }
  }
}
