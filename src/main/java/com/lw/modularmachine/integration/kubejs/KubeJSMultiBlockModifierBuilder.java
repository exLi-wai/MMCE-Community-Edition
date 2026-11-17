package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.recipe.RecipeModifiers;
import com.lw.modularmachine.machine.structure.StructureModifierDefinition;

/**
 * Fixed structure all-of modifier declaration, captured by its machine owner.
 */
@ApiStatus.Internal
public final class KubeJSMultiBlockModifierBuilder {
  private final Thread owner = Thread.currentThread();
  private final String name;
  private final List<RecipeModifier> modifiers = new ArrayList<>();
  private KubeJSBlockArrayBuilder blocks;
  private ItemStack descriptiveStack = ItemStack.EMPTY;
  private String description = "";
  private boolean built;

  private KubeJSMultiBlockModifierBuilder(String name) {
    if (name != null && (name.isBlank() || name.length() > 256)) {
      throw new IllegalArgumentException("Invalid modifier name");
    }
    this.name = name;
  }

  public static KubeJSMultiBlockModifierBuilder newBuilder() {
    return new KubeJSMultiBlockModifierBuilder(null);
  }

  public static KubeJSMultiBlockModifierBuilder newBuilder(String name) {
    return new KubeJSMultiBlockModifierBuilder(name);
  }

  public KubeJSMultiBlockModifierBuilder setBlockArray(KubeJSBlockArrayBuilder blocks) {
    mutable();
    this.blocks = KubeJSBlockArrayBuilder.newBuilder(Objects.requireNonNull(blocks, "block array"));
    return this;
  }

  public KubeJSMultiBlockModifierBuilder addModifier(RecipeModifier... values) {
    mutable();
    modifiers.addAll(List.of(values));
    new RecipeModifiers(modifiers);
    return this;
  }

  public KubeJSMultiBlockModifierBuilder setDescriptiveStack(ItemStack stack) {
    mutable();
    descriptiveStack = Objects.requireNonNull(stack, "descriptive stack").copy();
    return this;
  }

  KubeJSMultiBlockModifierBuilder description(String text) {
    mutable();
    if (text == null || text.length() > 512) {
      throw new IllegalArgumentException("Modifier description exceeds 512 characters");
    }
    description = text;
    return this;
  }

  public KubeJSMultiBlockModifierBuilder build() {
    mutable();
    Objects.requireNonNull(blocks, "block array");
    built = true;
    return this;
  }

  private void mutable() {
    if (built || Thread.currentThread() != owner) {
      throw new IllegalStateException("Modifier builder is built or used outside its creating thread");
    }
  }

  KubeJSBlockArrayBuilder blocks() {
    return blocks;
  }

  JsonObject capture(KubeJSMachineBuilder machine, KubeJSMachineBuilder.Session session, int index) {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("Modifier capture outside creating thread");
    }
    var result = new JsonObject();
    result.addProperty("id", "script_modifier_" + index);
    result.addProperty(
        "script_display",
        session
            .display(new StructureModifierDefinition.Display(name == null ? "" : name, description, descriptiveStack)));
    var parts = new JsonArray();
    for (var value : Objects.requireNonNull(blocks, "block array").capture(session)) {
      var part = value.getAsJsonObject();
      var pos = part.getAsJsonArray("offset");
      part.addProperty(
          "part_id",
          machine.partId(new BlockPos(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt())));
      part.remove("offset");
      if (part.has("selector") || part.has("interface") || part.has("field")) {
        throw new IllegalArgumentException("Modifier conditions require ordinary block parts");
      }
      parts.add(part);
    }
    result.add("parts", parts);
    var rules = new JsonArray();
    modifiers.forEach(rule -> rules.add(RecipeModifier.CODEC.encodeStart(JsonOps.INSTANCE, rule).getOrThrow()));
    result.add("modifiers", rules);
    return result;
  }
}
