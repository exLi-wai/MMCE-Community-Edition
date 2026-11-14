package com.lw.modularmachine.client.selection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.structure.MachineDefinitionFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal worker-only publication of client preview snapshots as new export
 * files.
 */
@ApiStatus.Internal
final class RangedSelectionExportFile {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);
  private static final int MAX_COLLISIONS = 10_000;

  record Saved(Path file, String name) {
  }

  private RangedSelectionExportFile() {
  }

  static String fileName(String requestedName) {
    String name = Objects.requireNonNull(requestedName).strip().toLowerCase(Locale.ROOT);
    if (name.isEmpty()) {
      return "selection_" + STAMP.format(LocalDateTime.now());
    }
    if (name.endsWith(".json")) {
      name = name.substring(0, name.length() - 5);
    }
    validateName(name);

    return name;
  }

  static Saved save(Path definitionDirectory, String name, JsonObject root) throws IOException {
    String baseName = Objects.requireNonNull(name);
    validateName(baseName);
    byte[] bytes = (JSON.toJson(Objects.requireNonNull(root)) + "\n").getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MachineDefinitionFiles.MAX_FILE_BYTES) {
      throw new IOException("The exported preview exceeds the machine definition size limit");
    }
    Path directory = directory(Objects.requireNonNull(definitionDirectory));
    Path temporary = Files.createTempFile(directory, ".mmcece-selection-", ".tmp");
    boolean published = false;
    try {
      try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          output.write(buffer);
        }
        output.force(true);
      }
      for (int collision = 0; collision <= MAX_COLLISIONS; collision++) {
        String candidate = collision == 0 ? baseName : baseName + "_" + collision;
        Path file = directory.resolve(candidate + ".json");
        try {
          // Publish only complete JSON to the reload directory, without replacing a file.
          Files.createLink(file, temporary);
        } catch (FileAlreadyExistsException occupied) {
          continue;
        }
        published = true;

        return new Saved(file, candidate);
      }
      throw new IOException("Too many exported previews already use this filename");
    } finally {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanupFailure) {
        if (!published) {
          throw cleanupFailure;
        }
        // A completed export remains successful if its private temporary file remains.
      }
    }
  }

  private static void validateName(String name) {
    if (!name.matches("[a-z0-9][a-z0-9_.-]{0,95}")
        || name.contains("..")
        || name.endsWith(".")
        || name.endsWith(".var")
        || name.split("\\.", 2)[0].matches("con|prn|aux|nul|com[0-9]|lpt[0-9]")) {
      throw new IllegalArgumentException(
          "Use a filename of 1..96 letters, numbers, dots, underscores or hyphens; paths and reserved names are not allowed");
    }
  }

  private static Path directory(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Files.createDirectories(absolute);
    if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || !absolute.toRealPath().equals(absolute)) {
      throw new IOException("The export path must be a directory without symbolic links: " + absolute);
    }

    return absolute;
  }
}
