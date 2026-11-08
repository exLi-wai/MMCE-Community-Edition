package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal shared disk discovery and variable interpretation for reload and
 * editing.
 */
@ApiStatus.Internal
public final class MachineDefinitionFiles {
  public static final long MAX_FILE_BYTES = 67_108_864L;
  public static final long MAX_TOTAL_BYTES = 1_073_741_824L;

  public record Variables(Map<String, List<String>> values, String hash) {
    public Variables {
      var copy = new LinkedHashMap<String, List<String>>();
      values.forEach((key, value) -> copy.put(key, List.copyOf(value)));
      values = Map.copyOf(copy);
    }

    public JsonObject expand(JsonObject root, String source) {
      return MachineDefinitionVariables.from(values).expandParts(root, source);
    }
  }

  private MachineDefinitionFiles() {
  }

  public static List<Path> jsonFiles(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .sorted(Comparator.comparing(path -> root.relativize(path).toString())).toList();
    }
  }

  public static boolean variable(Path path) {
    return path.getFileName().toString().endsWith(".var.json");
  }

  public static Variables readVariables(Path root, List<Path> files) throws IOException {
    var digest = digest();
    var values = new LinkedHashMap<String, List<String>>();
    long total = 0;
    for (var file : files) {
      if (!variable(file)) {
        continue;
      }
      var bytes = read(file);
      total = Math.addExact(total, bytes.length);
      if (total > MAX_TOTAL_BYTES) {
        throw new JsonParseException("Machine variables exceed the directory size budget");
      }
      digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(bytes);
      digest.update((byte) 0);
      var parsed = MachineDefinitionVariables.parse(parse(bytes, file.toString()), file.toString());
      values.putAll(parsed.values());
    }
    return new Variables(values, HexFormat.of().formatHex(digest.digest()));
  }

  public static byte[] read(Path path) throws IOException {
    if (Files.size(path) > MAX_FILE_BYTES) {
      throw new JsonParseException(path + ": Machine definition size limit exceeded");
    }
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(Math.toIntExact(MAX_FILE_BYTES + 1));
    }
    if (bytes.length > MAX_FILE_BYTES) {
      throw new JsonParseException(path + ": Machine definition size limit exceeded");
    }
    return bytes;
  }

  public static JsonObject parse(byte[] bytes, String source) {
    var parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
    if (!parsed.isJsonObject()) {
      throw new JsonParseException(source + ": Expected machine/variable JSON object");
    }
    return parsed.getAsJsonObject();
  }

  public static String hash(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
