package com.lw.modularmachine.api.recipe.adapter;

import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Read-only source and registry view from the prospective resource generation.
 * No server, level, controller or inventory authority is provided.
 */
public record RecipeAdapterContext(
    ResourceLocation machine,
    RecipeAdapterImport settings,
    HolderLookup.Provider registries,
    List<RecipeHolder<?>> sources,
    RecipeAdapterTags tags) {
  public RecipeAdapterContext {
    Objects.requireNonNull(machine, "machine");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(registries, "registries");
    sources = List.copyOf(sources);
    Objects.requireNonNull(tags, "tags");
  }

  /**
   * Standalone compatibility path; resource reloads pass staged tags explicitly.
   */
  public RecipeAdapterContext(ResourceLocation machine, RecipeAdapterImport settings, HolderLookup.Provider registries, List<RecipeHolder<?>> sources) {
    this(machine, settings, registries, sources, RecipeAdapterTags.from(registries));
  }

  /**
   * Creates a native builder with a content-derived import ID and configured
   * costs. Duration zero in the import uses {@code sourceDuration}; otherwise the
   * configured duration replaces it. A one-to-one adapter may use an empty
   * variant. Expanded variants use stable content keys, never list positions.
   */
  public MachineRecipeBuilder builder(ResourceLocation source, String variant, int sourceDuration) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(variant, "variant");
    int duration = settings.duration() == 0 ? sourceDuration : settings.duration();
    var id = importedId(source, variant);

    return MachineRecipeBuilder.create(id, machine, duration).energyPerTick(settings.energyPerTick())
        .priority(settings.priority());
  }

  private ResourceLocation importedId(ResourceLocation source, String variant) {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
    for (String field : List
        .of(machine.toString(), settings.id(), settings.adapter().toString(), source.toString(), variant)) {
      var bytes = field.getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
    }

    return ResourceLocation
        .fromNamespaceAndPath("mmcece", "mmcece/adapted/" + HexFormat.of().formatHex(digest.digest()));
  }
}
