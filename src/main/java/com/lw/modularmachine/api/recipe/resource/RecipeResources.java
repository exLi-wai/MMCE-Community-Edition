package com.lw.modularmachine.api.recipe.resource;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable typed resource declarations; entries own no live inventory or world
 * objects.
 */
public final class RecipeResources {
  public record Entry<D>(RecipeResourceType<D> type, D data) {
    public Entry {
      Objects.requireNonNull(type, "type");
      data = type.dataClass().cast(Objects.requireNonNull(data, "data"));
    }

    public boolean hasInputs() {
      return type.hasInputs(data);
    }

    public boolean hasOutputs() {
      return type.hasOutputs(data);
    }
  }

  private final List<Entry<?>> entries;

  public RecipeResources(List<Entry<?>> entries) {
    this.entries = List.copyOf(entries);
    var ids = new HashSet<ResourceLocation>();
    for (var entry : this.entries) {
      if (!ids.add(entry.type().id())) {
        throw new IllegalArgumentException("Duplicate recipe resource: " + entry.type().id());
      }
    }
  }

  public List<Entry<?>> entries() {
    return entries;
  }

  public <D> D get(RecipeResourceType<D> type) {
    Objects.requireNonNull(type, "type");
    for (var entry : entries) {
      if (entry.type() == type) {
        return type.dataClass().cast(entry.data());
      }
      if (entry.type().id().equals(type.id())) {
        throw new IllegalArgumentException("Resource descriptor identity differs: " + type.id());
      }
    }

    return type.empty();
  }

  public boolean hasInputs() {
    return entries.stream().anyMatch(Entry::hasInputs);
  }

  public boolean hasOutputs() {
    return entries.stream().anyMatch(Entry::hasOutputs);
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof RecipeResources resources && entries.equals(resources.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }
}
