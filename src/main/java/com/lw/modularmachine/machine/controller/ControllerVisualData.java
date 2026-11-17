package com.lw.modularmachine.machine.controller;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import java.nio.charset.CharacterCodingException;

/** Bounded client presentation copy; it never replaces server script state. */
@ApiStatus.Internal
public record ControllerVisualData(ResourceLocation machine, long revision, String customData) {
  private static final String KEY = "controller_visual_data";
  private static final int SCHEMA = 1;

  public ControllerVisualData {
    if (machine == null || revision < 0) {
      throw new IllegalArgumentException("Invalid controller visual data");
    }
    customData = ControllerScriptDataValues.canonicalData(customData);
  }

  public static void save(CompoundTag tag, ControllerScriptStateOwner owner) {
    var data = new CompoundTag();
    data.putInt("schema", SCHEMA);
    data.putString("machine", owner.structureMachineId().toString());
    var state = owner.scriptState().snapshot();
    data.putLong("revision", state.revision());
    data.putByteArray(
        "custom_data",
        (owner.scriptStateAvailable() ? state.customData() : "{}").getBytes(StandardCharsets.UTF_8));
    tag.put(KEY, data);
  }

  public static ControllerVisualData load(CompoundTag tag) {
    if (!tag.contains(KEY, Tag.TAG_COMPOUND)) {
      return null;
    }
    var data = tag.getCompound(KEY);
    if (data.getInt("schema") != SCHEMA
        || !data.contains("machine", Tag.TAG_STRING)
        || !data.contains("revision", Tag.TAG_LONG)
        || !data.contains("custom_data", Tag.TAG_BYTE_ARRAY)) {
      return null;
    }
    try {
      byte[] bytes = data.getByteArray("custom_data");
      if (bytes.length > ControllerScriptDataValues.MAX_BYTES) {
        return null;
      }
      String customData = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();

      return new ControllerVisualData(
          ResourceLocation.parse(data.getString("machine")),
          data.getLong("revision"),
          customData);
    } catch (RuntimeException | CharacterCodingException invalid) {
      return null;
    }
  }
}
