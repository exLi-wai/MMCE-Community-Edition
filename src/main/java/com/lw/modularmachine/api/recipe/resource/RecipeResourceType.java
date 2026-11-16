package com.lw.modularmachine.api.recipe.resource;

import com.mojang.serialization.MapCodec;
import com.lw.modularmachine.api.component.ResourceType;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/**
 * Typed, immutable native recipe resource contract, installed before recipe
 * loading.
 */
public final class RecipeResourceType<D> {
  private final ResourceLocation id;
  private final Class<D> dataClass;
  private final MapCodec<D> nativeCodec;
  private final D empty;
  private final Predicate<D> hasInputs;
  private final Predicate<D> hasOutputs;

  public RecipeResourceType(ResourceLocation id, Class<D> dataClass, MapCodec<D> nativeCodec, D empty, Predicate<D> hasInputs, Predicate<D> hasOutputs) {
    this.id = Objects.requireNonNull(id, "id");
    this.dataClass = Objects.requireNonNull(dataClass, "dataClass");
    this.nativeCodec = Objects.requireNonNull(nativeCodec, "nativeCodec");
    this.empty = dataClass.cast(Objects.requireNonNull(empty, "empty"));
    this.hasInputs = Objects.requireNonNull(hasInputs, "hasInputs");
    this.hasOutputs = Objects.requireNonNull(hasOutputs, "hasOutputs");
    if (!isEmpty(empty)) {
      throw new IllegalArgumentException("Empty resource declaration contains input or output");
    }
  }

  public ResourceLocation id() {
    return id;
  }

  /**
   * Resolve after Java resource identity registration, before definitions are
   * loaded.
   */
  public ResourceType resourceType() {
    return ResourceType.valueOf(id.toString());
  }

  public Class<D> dataClass() {
    return dataClass;
  }

  public MapCodec<D> nativeCodec() {
    return nativeCodec;
  }

  public D empty() {
    return empty;
  }

  public boolean hasInputs(D data) {
    return hasInputs.test(dataClass.cast(data));
  }

  public boolean hasOutputs(D data) {
    return hasOutputs.test(dataClass.cast(data));
  }

  public boolean isEmpty(D data) {
    return !hasInputs(data) && !hasOutputs(data);
  }
}
