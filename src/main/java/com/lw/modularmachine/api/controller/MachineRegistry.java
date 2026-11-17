package com.lw.modularmachine.api.controller;

import com.lw.modularmachine.machine.structure.StructureService;
import java.util.Comparator;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;

/**
 * Published machine definition directory, without live builders or registries.
 */
public final class MachineRegistry {
  public record Machine(
      ResourceLocation registryName,
      String localizedName,
      int maxParallelism,
      int internalParallelism,
      int maxThreads,
      boolean hasFactory,
      List<String> dynamicPatterns) {
    public Machine {
      dynamicPatterns = List.copyOf(dynamicPatterns);
    }

    public ResourceLocation getRegistryName() {
      return registryName;
    }

    public String getLocalizedName() {
      return Component.translatable(localizedName).getString();
    }

    public String getTranslationKey() {
      return localizedName;
    }

    public int getMaxThreads() {
      return maxThreads;
    }
  }

  private MachineRegistry() {
  }

  public static List<Machine> getAllRegisteredMachinery() {
    return StructureService.definitionsSnapshot().values().stream()
        .sorted(Comparator.comparing(definition -> definition.id().toString()))
        .map(
            definition -> new Machine(
                definition.id(),
                definition.translationKey(),
                definition.maxParallelism(),
                definition.internalParallelism(),
                definition.factoryPreset().maximumExtraThreads() >= 0 ? definition.factoryPreset()
                    .maximumExtraThreads() : Math.max(
                        0,
                        definition.maximumFactoryThreads() - Math.toIntExact(
                            definition.factoryPreset().threads().stream().filter(thread -> thread.core()).count())),
                definition.hasFactory(),
                definition.dynamicPatterns().stream().map(pattern -> pattern.id()).toList()))
        .toList();
  }
}
