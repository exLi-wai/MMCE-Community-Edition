package com.lw.modularmachine.machine.selection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.selection.RangedSelectionData.FileOption;
import com.lw.modularmachine.machine.structure.MachineDefinitionConfig;
import com.lw.modularmachine.machine.structure.MachineDefinitionFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal worker-only disk snapshots and conditional publication for the
 * ranged tool.
 */
@ApiStatus.Internal
public final class MachineDefinitionFileStore {
  private static final int PAGE_SIZE = 4;
  private static final int MAX_FILES = 1024;
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Object WRITES = new Object();
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
      .withZone(ZoneOffset.UTC);

  public record Entry(String token, Path path, ResourceLocation machine, String label, String hash) {
  }

  public record Catalog(
      Path root,
      List<Entry> entries,
      MachineDefinitionFiles.Variables variables,
      String directoryHash) {
    public Catalog {
      entries = List.copyOf(entries);
    }

    public int pages(String filter) {
      return Math.max(1, (filtered(filter).size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public List<FileOption> page(String filter, int index) {
      var matches = filtered(filter);
      int page = Math.max(0, Math.min(index, Math.max(0, (matches.size() - 1) / PAGE_SIZE)));
      int start = Math.min(matches.size(), page * PAGE_SIZE);
      return matches.subList(start, Math.min(matches.size(), start + PAGE_SIZE)).stream()
          .map(entry -> new FileOption(entry.token(), entry.machine(), relative(root, entry.path()), entry.label()))
          .toList();
    }

    private List<Entry> filtered(String filter) {
      String query = Objects.requireNonNull(filter).strip().toLowerCase(Locale.ROOT);
      return entries.stream()
          .filter(
              entry -> query.isEmpty()
                  || entry.machine().toString().contains(query)
                  || entry.label().toLowerCase(Locale.ROOT).contains(query)
                  || relative(root, entry.path()).toLowerCase(Locale.ROOT).contains(query))
          .toList();
    }
  }

  public record Source(
      Path file,
      ResourceLocation machine,
      JsonObject root,
      String hash,
      MachineDefinitionFiles.Variables variables,
      String directoryHash,
      boolean existing) {
    public Source {
      root = root == null ? null : root.deepCopy();
    }

    @Override
    public JsonObject root() {
      return root == null ? null : root.deepCopy();
    }
  }

  public record SaveResult(Path file, ResourceLocation machine, String sourceHash, String targetHash, Path backup) {
  }

  private MachineDefinitionFileStore() {
  }

  /** Each scan owns its tokens; tokens never encode or accept client paths. */
  public static Catalog scan() throws IOException {
    Path root = MachineDefinitionConfig.ensureDirectory();
    verifyRoot(root);
    var files = MachineDefinitionFiles.jsonFiles(root);
    var variables = MachineDefinitionFiles.readVariables(root, files);
    var directoryDigest = digest();
    var variableDigest = digest();
    var ids = new HashSet<ResourceLocation>();
    var entries = new ArrayList<Entry>();
    long total = 0;
    for (var file : files) {
      checkActive(() -> true);
      verifyPath(root, file, true);
      byte[] bytes = MachineDefinitionFiles.read(file);
      total = Math.addExact(total, bytes.length);
      if (total > MachineDefinitionFiles.MAX_TOTAL_BYTES) {
        throw new IOException("Machine directory exceeds its size budget");
      }
      String hash = MachineDefinitionFiles.hash(bytes);
      update(directoryDigest, relative(root, file));
      update(directoryDigest, hash);
      if (MachineDefinitionFiles.variable(file)) {
        // Same bytes/order as the production variable reader; reject an edit between
        // reads.
        variableDigest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
        variableDigest.update((byte) 0);
        variableDigest.update(bytes);
        variableDigest.update((byte) 0);
        continue;
      }
      if (entries.size() >= MAX_FILES) {
        throw new IOException("At most 1024 machine definitions may be edited");
      }
      var id = MachineDefinitionConfig.id(root.relativize(file));
      if (!ids.add(id)) {
        throw new IOException("Duplicate machine definition ID: " + id);
      }
      var json = MachineDefinitionFiles.parse(bytes, file.toString());
      String label = id.toString();
      if (json.has("translation_key")) {
        var value = json.get("translation_key");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
          throw new JsonParseException(file + ": translation_key must be a string");
        }
        label = value.getAsString();
      }
      String relative = relative(root, file);
      if (relative.length() > 1024 || label.length() > 512) {
        throw new IOException("Machine file metadata exceeds the editor message budget: " + file);
      }
      entries.add(new Entry(UUID.randomUUID().toString(), file, id, label, hash));
    }
    if (!HexFormat.of().formatHex(variableDigest.digest()).equals(variables.hash())) {
      throw new IOException("Machine variables changed while reading; open the file list again");
    }

    return new Catalog(root, entries, variables, HexFormat.of().formatHex(directoryDigest.digest()));
  }

  public static Source read(Catalog catalog, String token) throws IOException {
    var entry = catalog.entries().stream().filter(value -> value.token().equals(token)).findFirst()
        .orElseThrow(() -> new IOException("The selected file token has expired; open the file list again"));
    verifyPath(catalog.root(), entry.path(), true);
    byte[] bytes = MachineDefinitionFiles.read(entry.path());
    if (!MachineDefinitionFiles.hash(bytes).equals(entry.hash())) {
      throw new IOException("The selected machine file changed; select it again");
    }

    return new Source(
        entry.path(),
        entry.machine(),
        MachineDefinitionFiles.parse(bytes, entry.path().toString()),
        entry.hash(),
        catalog.variables(),
        catalog.directoryHash(),
        true);
  }

  public static Source create(Catalog catalog, String requestedName, String playerName) throws IOException {
    if (catalog.entries().size() >= MAX_FILES) {
      throw new IOException("At most 1024 machine definitions may be edited");
    }
    String name = Objects.requireNonNull(requestedName).strip();
    boolean automatic = name.isEmpty();
    if (automatic) {
      String player = playerName == null ? "player" : playerName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
      if (player.length() > 24) {
        player = player.substring(0, 24);
      }
      name = "exported_" + (player.isEmpty() ? "player" : player) + "_" + STAMP.format(Instant.now());
    } else if (name.endsWith(".json")) {
      name = name.substring(0, name.length() - 5);
    }
    if (!name.matches("[a-z0-9][a-z0-9_.-]{0,95}") || name.endsWith(".var") || name.endsWith(".")) {
      throw new IOException("Use a filename of 1..96 lowercase letters, numbers, dots, underscores or hyphens");
    }
    Path parent = catalog.root().resolve("mmcece");
    verifyPath(catalog.root(), parent, false);
    Files.createDirectories(parent);
    verifyPath(catalog.root(), parent, true);
    Path file = parent.resolve(name + ".json");
    if (automatic) {
      int number = 1;
      while (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && number <= MAX_FILES) {
        file = parent.resolve(name + "_" + number++ + ".json");
      }
    }
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("The target file already exists: " + file.getFileName());
    }
    verifyPath(catalog.root(), file, false);
    var machine = MachineDefinitionConfig.id(catalog.root().relativize(file));
    if (catalog.entries().stream().anyMatch(entry -> entry.machine().equals(machine))) {
      throw new IOException("The target machine ID already exists: " + machine);
    }

    return new Source(file, machine, null, "", catalog.variables(), catalog.directoryHash(), false);
  }

  /**
   * The supplier must read only a thread-safe authorization flag, never a live
   * Level or BE.
   */
  public static SaveResult save(Source source, JsonObject root, BooleanSupplier active) throws IOException {
    byte[] output = (JSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8);
    if (output.length > MachineDefinitionFiles.MAX_FILE_BYTES) {
      throw new IOException("The exported machine exceeds the file size budget");
    }
    synchronized (WRITES) {
      checkActive(active);
      verifySource(source);
      byte[] original = source.existing() ? MachineDefinitionFiles.read(source.file()) : null;
      if (original != null && !MachineDefinitionFiles.hash(original).equals(source.hash())) {
        throw new IOException("The source machine changed before backup");
      }
      Path backup = original == null ? null : backup(source, original);
      Path temporary = Files.createTempFile(source.file().getParent(), ".mmcece-selection-", ".tmp");
      boolean published = false;
      try {
        writeFlushed(temporary, output, false);
        checkActive(active);
        verifySource(source);
        checkActive(active);
        if (source.existing()) {
          Files.move(temporary, source.file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
          Files.createLink(source.file(), temporary);
        }
        published = true;

        return new SaveResult(
            source.file(),
            source.machine(),
            source.hash(),
            MachineDefinitionFiles.hash(output),
            backup);
      } finally {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
          if (!published) {
            throw cleanupFailure;
          }
          // A completed publication must not be reported as failed because its private
          // tmp remains.
        }
      }
    }
  }

  private static void verifySource(Source source) throws IOException {
    Path root = MachineDefinitionConfig.directory();
    verifyPath(root, source.file(), source.existing());
    var current = scan();
    if (!current.directoryHash().equals(source.directoryHash())
        || !current.variables().hash().equals(source.variables().hash())) {
      throw new IOException("Machine files or variables changed; refresh the target and retry");
    }
    if (source.existing()) {
      if (!MachineDefinitionFiles.hash(MachineDefinitionFiles.read(source.file())).equals(source.hash())) {
        throw new IOException("The source machine changed; select it again");
      }
    } else if (Files.exists(source.file(), LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("The target file was created by another action");
    }
  }

  private static Path backup(Source source, byte[] bytes) throws IOException {
    Path configRoot = MachineDefinitionConfig.directory().getParent();
    verifyRoot(configRoot);
    Path directory = configRoot.resolve("backups/dynamic_structure");
    verifyPath(configRoot, directory, false);
    Files.createDirectories(directory);
    verifyPath(configRoot, directory, true);
    String name = source.machine().getNamespace() + "_" + source.machine().getPath().replace('/', '_');
    if (name.length() > 80) {
      name = name.substring(0, 80);
    }
    Path backup = directory.resolve(name + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID() + ".bak");
    writeFlushed(backup, bytes, true);

    return backup;
  }

  private static void writeFlushed(Path path, byte[] bytes, boolean create) throws IOException {
    var options = create ? new StandardOpenOption[]{StandardOpenOption.WRITE,
        StandardOpenOption.CREATE_NEW} : new StandardOpenOption[]{StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING};
    try (var channel = FileChannel.open(path, options)) {
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private static void verifyRoot(Path root) throws IOException {
    Path absolute = root.toAbsolutePath().normalize();
    if (!absolute.toRealPath().equals(absolute)) {
      throw new IOException("Machine editing does not follow linked configuration directories: " + root);
    }
  }

  private static void verifyPath(Path root, Path path, boolean existing) throws IOException {
    verifyRoot(root);
    Path absolute = path.toAbsolutePath().normalize();
    if (!absolute.startsWith(root) || absolute.equals(root)) {
      throw new IOException("Machine file is outside the configuration directory");
    }
    Path checked = root;
    for (Path segment : root.relativize(absolute)) {
      checked = checked.resolve(segment);
      if (Files.isSymbolicLink(checked)) {
        throw new IOException("Machine editing does not follow symbolic links: " + checked);
      }
      if (Files.exists(checked, LinkOption.NOFOLLOW_LINKS) && !checked.toRealPath().startsWith(root)) {
        throw new IOException("Machine file escapes the configuration directory");
      }
    }
    if (existing && !Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Machine file no longer exists: " + path);
    }
  }

  private static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  private static void checkActive(BooleanSupplier active) {
    if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) {
      throw new CancellationException("Ranged selection export was cancelled");
    }
  }

  private static void update(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
