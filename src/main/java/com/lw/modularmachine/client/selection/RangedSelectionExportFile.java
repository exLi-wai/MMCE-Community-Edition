package com.lw.modularmachine.client.selection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lw.modularmachine.machine.orientation.StateOrientationAdapters;
import com.lw.modularmachine.machine.selection.DynamicDefinitionEditor;
import com.lw.modularmachine.machine.selection.MachineDefinitionFileStore;
import com.lw.modularmachine.machine.selection.RangedSelectionData.Design;
import com.lw.modularmachine.machine.structure.DynamicLayoutValidator;
import com.lw.modularmachine.machine.structure.DynamicPatternDefinition;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.MachineDefinitionConfig;
import com.lw.modularmachine.machine.structure.MachineDefinitionDefaults;
import com.lw.modularmachine.machine.structure.MachineDefinitionFiles;
import com.lw.modularmachine.machine.structure.MachineDefinitionParser;
import com.lw.modularmachine.machine.structure.MachineDefinitionReloadListener;
import com.lw.modularmachine.machine.structure.MachineDefinitionScripts;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Worker-only publication of client preview snapshots and merges into existing
 * exports.
 */
@ApiStatus.Internal
final class RangedSelectionExportFile {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);
  private static final int MAX_COLLISIONS = 10_000;
  private static final int MAX_BASE_NAME_LENGTH = 96;
  private static final int MAX_TARGET_NAME_LENGTH = MAX_BASE_NAME_LENGTH + 1 + Integer.toString(MAX_COLLISIONS)
      .length();

  record Saved(Path file, String name) {
  }

  static final class Target {
    private final Path machineryRoot, file;
    private final String name;
    private final String hash;
    private final JsonObject original;

    private Target(Path machineryRoot, Path file, String name, byte[] original) {
      this.machineryRoot = machineryRoot;
      this.file = file;
      this.name = name;
      this.hash = MachineDefinitionFiles.hash(original);
      this.original = MachineDefinitionFiles.parse(original, file.toString());
    }
  }

  private record Prepared(
      ResourceLocation machine,
      MachineDefinitionFiles.DirectoryDefinitions directory,
      byte[] bytes,
      List<DynamicPatternDefinition.Declaration> additions,
      MachineDefinitionScripts.Snapshot scripts,
      List<StateOrientationAdapters.BlockReference> references) {
  }

  private RangedSelectionExportFile() {
  }

  static String baseName(String requestedName) {
    if (requestedName.isBlank()) {
      return "selection_" + STAMP.format(LocalDateTime.now());
    }
    return normalizeName(requestedName, MAX_BASE_NAME_LENGTH);
  }

  static String targetName(String requestedName) {
    return normalizeName(requestedName, MAX_TARGET_NAME_LENGTH);
  }

  private static String normalizeName(String requestedName, int maxLength) {
    String name = requestedName.strip().toLowerCase(Locale.ROOT);
    if (name.endsWith(".json")) {
      name = name.substring(0, name.length() - 5);
    }
    validateName(name, maxLength);

    return name;
  }

  static Saved save(Path definitionDirectory, String baseName, JsonObject root) throws IOException {
    validateName(baseName, MAX_BASE_NAME_LENGTH);
    byte[] bytes = encode(root);
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

  static Target capture(Path machineryRoot, String baseName) throws IOException {
    validateName(baseName, MAX_TARGET_NAME_LENGTH);
    Path root = machineryRoot.toAbsolutePath().normalize();
    Path directory = root.resolve("mmcece");
    Path file = directory.resolve(baseName + ".json");
    return new Target(root, file, baseName, MachineDefinitionFileStore.readExistingExport(directory, file));
  }

  static CompletableFuture<Saved> merge(Target target, RangedSelectionExportSnapshot snapshot, Design design, Executor registryExecutor, Executor worker) {
    return CompletableFuture.supplyAsync(() -> prepare(target, snapshot, design), worker).thenCompose(
        prepared -> CompletableFuture
            .supplyAsync(() -> StateOrientationAdapters.capture(prepared.references()), registryExecutor)
            .thenApplyAsync(registry -> validateAndSave(target, design, prepared, registry), worker));
  }

  private static Saved validateAndSave(Target target, Design design, Prepared prepared, StateOrientationAdapters.RegistrySnapshot registry) {
    var defaults = MachineDefinitionDefaults.capture();
    var definition = MachineDefinitionScripts.compile(
        prepared.scripts(),
        () -> validateCandidate(
            prepared.machine(),
            prepared.directory().roots(),
            registry,
            defaults,
            prepared.additions()));
    if (!definition.allows(design.orientation())) {
      throw new IllegalArgumentException(
          "The complete definition cannot represent the selected controller orientation");
    }
    try {
      MachineDefinitionFileStore
          .replaceExistingExport(target.file.getParent(), target.file, target.hash, prepared.bytes(), () -> {
            prepared.directory().verifySiblings(target.machineryRoot, target.file);
            if (!MachineDefinitionScripts.current(prepared.scripts())
                || !defaults.equals(MachineDefinitionDefaults.capture())) {
              throw new IOException("Machine scripts or defaults changed during validation; retry the export");
            }
          });
      return new Saved(target.file, target.name);
    } catch (IOException failure) {
      throw new CompletionException(failure);
    }
  }

  static MachineDefinition validateCandidate(ResourceLocation machine, Map<ResourceLocation, JsonElement> effectiveRoots, StateOrientationAdapters.RegistrySnapshot registry, MachineDefinitionDefaults defaults, List<DynamicPatternDefinition.Declaration> additions) {
    var definition = MachineDefinitionReloadListener.compileCandidate(effectiveRoots, registry, defaults).get(machine);
    DynamicDefinitionEditor.requireLoadedSegments(definition, additions);
    DynamicLayoutValidator.validate(definition);
    return definition;
  }

  private static Prepared prepare(Target target, RangedSelectionExportSnapshot snapshot, Design design) {
    var machine = MachineDefinitionConfig.id(target.machineryRoot.relativize(target.file));
    var merged = snapshot.appendTo(target.original, design);
    int existing = DynamicPatternDefinition.declarations(target.original, "$").size();
    var declarations = DynamicPatternDefinition.declarations(merged, "$");
    var additions = declarations.subList(existing, declarations.size());
    try {
      // Preserve the editable declarations on disk; the compilation now owns the
      // merged tree.
      byte[] bytes = encode(merged);
      var replacement = new MachineDefinitionFiles.Replacement(target.file, merged, bytes.length);
      var directory = MachineDefinitionFiles.readDefinitions(target.machineryRoot, replacement);
      var scripts = MachineDefinitionScripts.snapshot();
      MachineDefinitionScripts.applyInPlace(directory.roots(), scripts);
      var references = MachineDefinitionScripts
          .compile(scripts, () -> MachineDefinitionParser.blockReferences(directory.roots()));
      return new Prepared(
          machine,
          directory,
          bytes,
          additions,
          scripts,
          StateOrientationAdapters.distinctReferences(references));
    } catch (IOException failure) {
      throw new CompletionException(failure);
    }
  }

  private static byte[] encode(JsonObject root) throws IOException {
    byte[] bytes = (JSON.toJson(Objects.requireNonNull(root)) + "\n").getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MachineDefinitionFiles.MAX_FILE_BYTES) {
      throw new IOException("The exported preview exceeds the machine definition size limit");
    }
    return bytes;
  }

  private static void validateName(String name, int maxLength) {
    if (name.length() > maxLength
        || !name.matches("[a-z0-9][a-z0-9_.-]*")
        || name.contains("..")
        || name.endsWith(".")
        || name.endsWith(".var")
        || name.split("\\.", 2)[0].matches("con|prn|aux|nul|com[0-9]|lpt[0-9]")) {
      throw new IllegalArgumentException(
          "Use a filename of 1.." + maxLength + " letters, numbers, dots, underscores or hyphens; paths and reserved names are not allowed");
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
