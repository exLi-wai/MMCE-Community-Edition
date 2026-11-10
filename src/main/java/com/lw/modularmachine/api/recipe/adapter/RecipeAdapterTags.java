package com.lw.modularmachine.api.recipe.adapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** Immutable item-tag membership for one adapter conversion generation. */
public final class RecipeAdapterTags {
  private final Map<ResourceLocation, List<Item>> tags;

  public RecipeAdapterTags(Map<ResourceLocation, List<Item>> tags) {
    Objects.requireNonNull(tags, "tags");
    var snapshot = new LinkedHashMap<ResourceLocation, List<Item>>();
    tags.forEach((id, items) -> snapshot.put(Objects.requireNonNull(id, "tag"), List.copyOf(items)));
    this.tags = Map.copyOf(snapshot);
  }

  /**
   * Missing or empty tags have no candidates; no placeholder item is inserted.
   */
  public List<Item> items(ResourceLocation tag) {
    Objects.requireNonNull(tag, "tag");

    return tags.getOrDefault(tag, List.of());
  }

  /**
   * Captures tags presently visible through a standalone registry lookup. Active
   * resource reloads supply their staged tag snapshot explicitly instead of using
   * this convenience fallback before registry tags have been rebound.
   */
  public static RecipeAdapterTags from(HolderLookup.Provider registries) {
    Objects.requireNonNull(registries, "registries");
    var tags = new LinkedHashMap<ResourceLocation, List<Item>>();
    registries.lookupOrThrow(Registries.ITEM).listTags()
        .forEach(tag -> tags.put(tag.key().location(), tag.stream().map(Holder::value).toList()));

    return new RecipeAdapterTags(tags);
  }
}
