package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.api.controller.ControllerScriptData;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal immutable persistent state; replacement never edits run or upgrade
 * data.
 */
@ApiStatus.Internal
public final class ControllerScriptState {
  public static final String NBT_KEY = "controller_script_data";
  private final ControllerScriptData snapshot;

  private ControllerScriptState(ControllerScriptData snapshot) {
    this.snapshot = snapshot;
  }

  public static ControllerScriptState empty() {
    return new ControllerScriptState(new ControllerScriptData(0, "{}", Map.of()));
  }

  public ControllerScriptData snapshot() {
    return snapshot;
  }

  public long revision() {
    return snapshot.revision();
  }

  public List<RecipeModifier> modifiers() {
    return List.copyOf(snapshot.customModifiers().values());
  }

  public boolean isEmpty() {
    return snapshot.customData().equals("{}") && snapshot.customModifiers().isEmpty();
  }

  public ControllerScriptState with(String data, Map<ResourceLocation, RecipeModifier> modifiers) {
    var candidate = new ControllerScriptData(revision(), data, modifiers);
    if (candidate.customData().equals(snapshot.customData())
        && candidate.customModifiers().equals(snapshot.customModifiers())) {
      return this;
    }

    return new ControllerScriptState(
        new ControllerScriptData(Math.incrementExact(revision()), candidate.customData(), candidate.customModifiers()));
  }

  public ControllerScriptState cleared() {
    return isEmpty() ? this : with("{}", Map.of());
  }

  public CompoundTag save() {
    var data = new CompoundTag();
    data.putLong("revision", revision());
    data.putString("custom_data", snapshot.customData());
    var modifiers = new CompoundTag();
    snapshot.customModifiers().forEach(
        (id, modifier) -> modifiers
            .put(id.toString(), RecipeModifier.CODEC.encodeStart(NbtOps.INSTANCE, modifier).getOrThrow()));
    data.put("custom_modifiers", modifiers);

    return data;
  }

  public static ControllerScriptState load(Tag source) {
    if (source == null) {
      return empty();
    }
    if (!(source instanceof CompoundTag data)
        || !Set.of("revision", "custom_data", "custom_modifiers").equals(data.getAllKeys())
        || !data.contains("revision", Tag.TAG_LONG)
        || data.getLong("revision") < 0
        || !data.contains("custom_data", Tag.TAG_STRING)
        || !data.contains("custom_modifiers", Tag.TAG_COMPOUND)) {
      throw new IllegalArgumentException("Invalid controller script state");
    }
    var values = data.getCompound("custom_modifiers");
    if (values.size() > ControllerScriptDataValues.MAX_MODIFIERS) {
      throw new IllegalArgumentException("Too many controller script modifiers");
    }
    var modifiers = new LinkedHashMap<ResourceLocation, RecipeModifier>();
    for (String key : values.getAllKeys()) {
      var id = ResourceLocation.parse(key);
      if (!id.toString().equals(key) || !values.contains(key, Tag.TAG_COMPOUND)) {
        throw new IllegalArgumentException("Invalid controller script modifier ID");
      }
      var encoded = values.getCompound(key);
      if (!Set.of("target", "io", "operation", "value", "affect_chance").containsAll(encoded.getAllKeys())) {
        throw new IllegalArgumentException("Unknown controller script modifier field");
      }
      modifiers.put(id, RecipeModifier.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
    }

    return new ControllerScriptState(
        new ControllerScriptData(data.getLong("revision"), data.getString("custom_data"), modifiers));
  }
}
