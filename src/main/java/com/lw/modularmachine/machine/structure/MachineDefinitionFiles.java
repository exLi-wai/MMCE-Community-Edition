package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Expands an exclusively owned compilation input after freezing the raw output.
     */
    public void expandInPlace(JsonObject root, String source) {
      MachineDefinitionVariables.from(values).expandPartsInPlace(root, source);
    }
  }

  private MachineDefinitionFiles() {
  }

  /**
   * The caller transfers the raw replacement to the variable expander after
   * encoding it for publication.
   */
  public record Replacement(Path file, JsonObject root, int encodedSize) {
    public Replacement {
      if (encodedSize < 0 || encodedSize > MAX_FILE_BYTES) {
        throw new JsonParseException(file + ": Machine definition size limit exceeded");
      }
    }
  }

  public record DirectoryDefinitions(
      Map<ResourceLocation, JsonElement> roots,
      Map<Path, String> siblingHashes,
      String variablesHash) {
    /** The publisher verifies the target itself under its write lock. */
    public void verifySiblings(Path root, Path target) throws IOException {
      var files = jsonFiles(root);
      var siblings = files.stream().filter(file -> !variable(file) && !file.equals(target)).toList();
      if (siblings.size() != siblingHashes.size()
          || !siblingHashes.keySet().containsAll(siblings)
          || !readVariables(root, files).hash().equals(variablesHash)) {
        throw new IOException("Machine definitions or variables changed during validation; retry the export");
      }
      for (var file : siblings) {
        if (!hash(read(file, LinkOption.NOFOLLOW_LINKS)).equals(siblingHashes.get(file))) {
          throw new IOException("Machine definition changed during validation: " + file);
        }
      }
    }
  }

  /**
   * Reads the production configuration with its directory-wide budgets and
   * optional pending edit.
   */
  public static DirectoryDefinitions readDefinitions(Path root, @Nullable Replacement replacement) throws IOException {
    var files = jsonFiles(root);
    var machines = files.stream().filter(file -> !variable(file)).toList();
    if (machines.size() > 1024) {
      throw new JsonParseException("At most 1024 machine definitions per reload");
    }
    if (replacement != null && !machines.contains(replacement.file())) {
      throw new IOException("The selected machine file no longer exists: " + replacement.file());
    }
    var variables = readVariables(root, files);
    var roots = new LinkedHashMap<ResourceLocation, JsonElement>();
    var siblingHashes = new LinkedHashMap<Path, String>();
    long total = 0;
    for (var file : machines) {
      var id = MachineDefinitionConfig.id(root.relativize(file));
      JsonObject json;
      if (replacement != null && file.equals(replacement.file())) {
        total += replacement.encodedSize();
        json = replacement.root();
      } else {
        var bytes = read(file, MAX_TOTAL_BYTES - total, LinkOption.NOFOLLOW_LINKS);
        total += bytes.length;
        json = parse(bytes, file.toString());
        if (replacement != null) {
          siblingHashes.put(file, hash(bytes));
        }
      }
      if (total > MAX_TOTAL_BYTES) {
        throw new JsonParseException(file + ": Machine definition size limit exceeded");
      }
      variables.expandInPlace(json, file.toString());
      if (roots.putIfAbsent(id, json) != null) {
        throw new JsonParseException(id + ": Duplicate machine definition from " + file);
      }
    }
    return new DirectoryDefinitions(Map.copyOf(roots), Map.copyOf(siblingHashes), variables.hash());
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
    return read(path, new LinkOption[0]);
  }

  public static byte[] read(Path path, LinkOption... options) throws IOException {
    return read(path, MAX_FILE_BYTES, options);
  }

  private static byte[] read(Path path, long remainingBytes, LinkOption... options) throws IOException {
    long limit = Math.min(MAX_FILE_BYTES, remainingBytes);
    if (Files.size(path) > limit) {
      throw new JsonParseException(path + ": Machine definition size limit exceeded");
    }
    byte[] bytes;
    try (var input = Files.newInputStream(path, options)) {
      bytes = input.readNBytes(Math.toIntExact(limit + 1));
    }
    if (bytes.length > limit) {
      throw new JsonParseException(path + ": Machine definition size limit exceeded");
    }
    return bytes;
  }

  public static JsonObject parse(byte[] bytes, String source) {
    JsonElement parsed;
    try {
      parsed = JsonParser
          .parseReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder()));
    } catch (JsonParseException invalid) {
      throw new JsonParseException(source + ": Failed reading JSON", invalid);
    }
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
