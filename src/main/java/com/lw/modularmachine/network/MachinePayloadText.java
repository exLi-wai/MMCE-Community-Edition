package com.lw.modularmachine.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import static com.lw.modularmachine.network.MachinePayloads.MAX_TEXT_LENGTH;
import org.jetbrains.annotations.ApiStatus;

/** Shared bounded text encoding for machine payloads. */
@ApiStatus.Internal
final class MachinePayloadText {
  private MachinePayloadText() {
  }

  static void writeText(RegistryFriendlyByteBuf buffer, String value) {
    requireText(value);
    buffer.writeUtf(value, MAX_TEXT_LENGTH);
  }

  static String readText(RegistryFriendlyByteBuf buffer) {
    return buffer.readUtf(MAX_TEXT_LENGTH);
  }

  static void requireText(String value) {
    if (value == null || value.length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException("Invalid payload text");
    }
  }

}
