package com.lw.modularmachine.machine.factory;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.resource.ContextAwareReloadListener;

/** Strict data/<namespace>/mmcece/upgrades/*.json resource loader. */
public final class FactoryUpgradeReloadListener extends ContextAwareReloadListener {
  @Override
  public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier barrier, ResourceManager resources, ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler, Executor background, Executor game) {
    return CompletableFuture.supplyAsync(() -> read(resources), background).thenCompose(barrier::wait).thenAcceptAsync(
        definitions -> FactoryUpgradeService.replaceDefinitions(definitions, getRegistryLookup()),
        game);
  }

  private static List<FactoryUpgradeDefinition> read(ResourceManager resources) {
    var converter = new FileToIdConverter("mmcece/upgrades", ".json");
    var selected = converter.listMatchingResources(resources);
    if (selected.size() > FactoryUpgradeService.MAX_DEFINITIONS) {
      throw new JsonParseException("Too many fixed upgrade files");
    }
    var definitions = new ArrayList<FactoryUpgradeDefinition>();
    selected.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
      var id = converter.fileToId(entry.getKey());
      try (var reader = entry.getValue().openAsReader()) {
        definitions.add(FactoryUpgradeDefinition.parse(id, JsonParser.parseReader(reader)));
      } catch (IOException | RuntimeException invalid) {
        throw new JsonParseException(id + ": Invalid fixed upgrade file", invalid);
      }
    });

    return List.copyOf(definitions);
  }
}
