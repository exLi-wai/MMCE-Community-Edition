package com.lw.modularmachine.machine.recipe.resource;

import com.google.gson.JsonObject;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.mojang.serialization.JsonOps;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal registry-aware builder declarations, confined to one descriptor's
 * native fields.
 */
@ApiStatus.Internal
public final class RecipeResourceBuilderDeclarations {
  private RecipeResourceBuilderDeclarations() {
  }

  public static <D> RecipeResources.Entry<D> capture(RecipeResourceType<D> type, D data) {
    RecipeResourceDefinitions.requireRegistered(Objects.requireNonNull(type, "type"));
    var entry = new RecipeResources.Entry<>(type, data);
    if (type.isEmpty(entry.data())) {
      throw new IllegalArgumentException("Resource declaration must contain an input or output: " + type.id());
    }

    return entry;
  }

  public static RecipeResources.Entry<?> decode(ResourceLocation id, JsonObject declaration, HolderLookup.Provider registries) {
    var type = RecipeResourceDefinitions.find(Objects.requireNonNull(id, "id"))
        .orElseThrow(() -> new IllegalArgumentException("Unknown recipe resource definition: " + id));

    return decodeType(type, Objects.requireNonNull(declaration, "declaration").deepCopy(), registries);
  }

  private static <D> RecipeResources.Entry<D> decodeType(RecipeResourceType<D> type, JsonObject declaration, HolderLookup.Provider registries) {
    requireFields(type, declaration);
    var ops = Objects.requireNonNull(registries, "registries").createSerializationContext(JsonOps.INSTANCE);
    var parsed = capture(type, type.nativeCodec().codec().parse(ops, declaration).getOrThrow());
    return snapshot(parsed, registries);
  }

  /**
   * A custom descriptor owns its field validation and detached data
   * representation.
   */
  public static <D> RecipeResources.Entry<D> snapshot(RecipeResources.Entry<D> entry, HolderLookup.Provider registries) {
    return roundTrip(entry, registries).entry();
  }

  public static <D> JsonObject encode(RecipeResources.Entry<D> entry, HolderLookup.Provider registries) {
    return roundTrip(entry, registries).fields();
  }

  private record Validated<D>(RecipeResources.Entry<D> entry, JsonObject fields) {
  }

  private static <D> Validated<D> roundTrip(RecipeResources.Entry<D> entry, HolderLookup.Provider registries) {
    var type = entry.type();
    capture(type, entry.data());
    var ops = Objects.requireNonNull(registries, "registries").createSerializationContext(JsonOps.INSTANCE);
    var declaration = type.nativeCodec().codec().encodeStart(ops, entry.data()).getOrThrow().getAsJsonObject();
    requireFields(type, declaration);
    var restored = capture(type, type.nativeCodec().codec().parse(ops, declaration.deepCopy()).getOrThrow());
    var normalized = type.nativeCodec().codec().encodeStart(ops, restored.data()).getOrThrow().getAsJsonObject();
    requireFields(type, normalized);
    if (!declaration.equals(normalized)) {
      throw new IllegalArgumentException("Lossy resource declaration codec: " + type.id());
    }

    return new Validated<>(restored, normalized);
  }

  private static void requireFields(RecipeResourceType<?> type, JsonObject declaration) {
    Set<String> owned = RecipeResourceDefinitions.fields(type);
    if (declaration.size() == 0) {
      throw new IllegalArgumentException("Resource declaration cannot be empty: " + type.id());
    }
    for (var field : declaration.keySet()) {
      if (!owned.contains(field)) {
        throw new IllegalArgumentException("Field is not owned by recipe resource " + type.id() + ": " + field);
      }
    }
  }
}
