package com.lw.modularmachine.machine.controller;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal bounded, pure serialization validation for controller script values.
 */
@ApiStatus.Internal
public final class ControllerScriptDataValues {
  public static final int MAX_BYTES = 65_536;
  public static final int MAX_NODES = 4096;
  public static final int MAX_DEPTH = 32;
  public static final int MAX_MODIFIERS = 128;

  private record Frame(boolean object, Set<String> keys) {
  }

  private ControllerScriptDataValues() {
  }

  public static String canonicalData(String source) {
    Objects.requireNonNull(source, "customData");
    checkBytes(source);
    var frames = new ArrayDeque<Frame>();
    int nodes = 0;
    try (var reader = new JsonReader(new StringReader(source))) {
      reader.setLenient(false);
      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        throw new IllegalArgumentException("Controller custom data must be a JSON object");
      }
      while (reader.peek() != JsonToken.END_DOCUMENT) {
        switch (reader.peek()) {
          case BEGIN_OBJECT -> {
            reader.beginObject();
            frames.push(new Frame(true, new HashSet<>()));
            nodes++;
          }
          case BEGIN_ARRAY -> {
            reader.beginArray();
            frames.push(new Frame(false, Set.of()));
            nodes++;
          }
          case END_OBJECT -> {
            reader.endObject();
            frames.pop();
          }
          case END_ARRAY -> {
            reader.endArray();
            frames.pop();
          }
          case NAME -> {
            String key = reader.nextName();
            if (frames.isEmpty() || !frames.peek().object() || !frames.peek().keys().add(key)) {
              throw new IllegalArgumentException("Duplicate controller custom data key");
            }
            nodes++;
          }
          case STRING, NUMBER, BOOLEAN, NULL -> {
            reader.skipValue();
            nodes++;
          }
          default -> throw new IllegalArgumentException("Invalid controller custom data JSON");
        }
        if (frames.size() > MAX_DEPTH || nodes > MAX_NODES) {
          throw new IllegalArgumentException("Controller custom data exceeds its depth or node budget");
        }
      }
    } catch (IOException | IllegalStateException invalid) {
      throw new IllegalArgumentException("Invalid controller custom data JSON", invalid);
    }
    String result = JsonParser.parseString(source).getAsJsonObject().toString();
    checkBytes(result);

    return result;
  }

  private static void checkBytes(String source) {
    if (source.length() > MAX_BYTES || source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IllegalArgumentException("Controller custom data exceeds its byte budget");
    }
  }

  public static Map<ResourceLocation, RecipeModifier> modifiers(Map<ResourceLocation, RecipeModifier> values) {
    Objects.requireNonNull(values, "customModifiers");
    if (values.size() > MAX_MODIFIERS) {
      throw new IllegalArgumentException("Too many controller script modifiers");
    }
    var result = new TreeMap<ResourceLocation, RecipeModifier>();
    for (var entry : values.entrySet()) {
      ResourceLocation id = Objects.requireNonNull(entry.getKey(), "modifier ID");
      if (id.toString().length() > 256) {
        throw new IllegalArgumentException("Controller modifier ID is too long");
      }
      result.put(id, Objects.requireNonNull(entry.getValue(), "modifier"));
    }

    return Collections.unmodifiableMap(result);
  }
}
