package com.lw.modularmachine.machine.selection;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Internal detached messages for the existing ranged tool's editor. */
@ApiStatus.Internal
public final class RangedSelectionData {
  public static final int MAX_SEGMENTS = 64;
  public static final int MAX_CAPTURE = 262_144;
  public static final int MAX_PART_EDITS = 4096;
  public static final UUID NO_SESSION = new UUID(0, 0);

  private RangedSelectionData() {
  }

  public enum PickTarget {
    NONE,
    MAIN,
    BODY,
    START,
    END,
    PART
  }

  public enum Action {
    OPEN,
    BEGIN_PICK,
    CANCEL_PICK,
    CLEAR_BOX,
    SET_MODE,
    LIST_FILES,
    SELECT_FILE,
    ADD_SEGMENT,
    SELECT_SEGMENT,
    REMOVE_SEGMENT,
    UPDATE,
    CLEAR,
    PREVIEW,
    UPDATE_PART,
    RESET_PART,
    CLOSE_PART
  }

  public record Box(BlockPos first, BlockPos second) {
    public Box {
      first = Objects.requireNonNull(first).immutable();
      second = Objects.requireNonNull(second).immutable();
    }
    public long volume() {
      long x = Math.abs((long) first.getX() - second.getX()) + 1;
      long y = Math.abs((long) first.getY() - second.getY()) + 1;
      long z = Math.abs((long) first.getZ() - second.getZ()) + 1;
      return Math.multiplyExact(Math.multiplyExact(x, y), z);
    }
  }

  public record Settings(
      String id,
      int minimum,
      int maximum,
      Direction direction,
      int gap,
      boolean customStep,
      BlockPos step,
      boolean customOrigin,
      BlockPos origin,
      int sampleLength,
      String selector,
      String interfaceType) {
    public Settings {
      Objects.requireNonNull(id);
      Objects.requireNonNull(direction);
      step = Objects.requireNonNull(step).immutable();
      origin = Objects.requireNonNull(origin).immutable();
      Objects.requireNonNull(selector);
      Objects.requireNonNull(interfaceType);
      if (!id.isEmpty() && !id.matches("[a-zA-Z0-9_.-]{1,64}")
          || minimum < 0
          || maximum < minimum
          || maximum > 4_194_304
          || gap < 0
          || gap > 1_000_000
          || sampleLength < 0
          || sampleLength > 4_194_304
          || customStep && step.equals(BlockPos.ZERO)
          || selector.length() > 64
          || interfaceType.length() > 64) {
        throw new IllegalArgumentException("Invalid dynamic selection parameters");
      }
    }
    public static Settings defaults() {
      return new Settings("", 1, 8, Direction.SOUTH, 0, false, new BlockPos(0, 0, 1), false, BlockPos.ZERO, 1, "", "");
    }
  }

  public record Segment(Settings settings, Box body, Box start, Box end) {
    public Segment {
      Objects.requireNonNull(settings);
    }
  }

  /**
   * Internal immutable design shared by preview and future export. File options
   * are draft text, not a loaded source or permission to write a file. An empty
   * color inherits the global default; explicit colors use six uppercase digits.
   */
  @ApiStatus.Internal
  public record Design(
      String fileName,
      boolean append,
      String targetFile,
      boolean deduplicate,
      boolean includeAir,
      @Nullable Box main,
      List<Segment> segments,
      List<String> keys,
      @Nullable BlockPos controller,
      MachineOrientation orientation,
      Map<BlockPos, PartEdit> partEdits,
      boolean requiresBlueprint,
      boolean hasFactory,
      boolean factoryOnly,
      String color) {
    public Design {
      Objects.requireNonNull(fileName);
      Objects.requireNonNull(targetFile);
      Objects.requireNonNull(orientation);
      Objects.requireNonNull(color);
      if (!color.isEmpty() && !color.matches("[0-9a-fA-F]{6}")) {
        throw new IllegalArgumentException("Selection color must be empty or six hexadecimal digits");
      }
      color = color.toUpperCase(Locale.ROOT);
      controller = controller == null ? null : controller.immutable();
      segments = List.copyOf(segments);
      keys = List.copyOf(keys);
      Objects.requireNonNull(partEdits);
      if (segments.isEmpty()
          || segments.size() > MAX_SEGMENTS
          || keys.size() != segments.size()
          || partEdits.size() > MAX_PART_EDITS) {
        throw new IllegalArgumentException("Invalid selection design size");
      }
      var uniqueKeys = new HashSet<String>();
      var normalized = new ArrayList<Segment>(segments.size());
      try {
        long volume = volume(main);
        for (int index = 0; index < segments.size(); index++) {
          String key = keys.get(index);
          if (!key.matches("[a-zA-Z0-9_.-]{1,64}") || !uniqueKeys.add(key)) {
            throw new IllegalArgumentException("Invalid or duplicate selection key");
          }
          var segment = segments.get(index);
          normalized
              .add(new Segment(designSettings(segment.settings()), segment.body(), segment.start(), segment.end()));
          volume = Math.addExact(volume, volume(segment.body()));
          volume = Math.addExact(volume, volume(segment.start()));
          volume = Math.addExact(volume, volume(segment.end()));
        }
        if (volume > MAX_CAPTURE) {
          throw new IllegalArgumentException("Selection exceeds the capture limit");
        }
      } catch (ArithmeticException oversized) {
        throw new IllegalArgumentException("Selection exceeds the capture limit", oversized);
      }
      segments = List.copyOf(normalized);
      var frozen = new LinkedHashMap<BlockPos, PartEdit>();
      partEdits.forEach((position, edit) -> frozen.put(position.immutable(), Objects.requireNonNull(edit)));
      partEdits = Map.copyOf(frozen);
    }

    public static Design defaults() {
      var settings = new Settings(
          "",
          1,
          1,
          Direction.SOUTH,
          0,
          false,
          new BlockPos(0, 0, 1),
          false,
          BlockPos.ZERO,
          1,
          "",
          "");

      return new Design(
          "",
          false,
          "",
          true,
          false,
          null,
          List.of(new Segment(settings, null, null, null)),
          List.of("selection_1"),
          null,
          MachineOrientation.IDENTITY,
          Map.of(),
          false,
          false,
          false,
          "");
    }

    public Design withMain(@Nullable Box value) {
      return new Design(
          fileName,
          append,
          targetFile,
          deduplicate,
          includeAir,
          value,
          segments,
          keys,
          controller,
          orientation,
          partEdits,
          requiresBlueprint,
          hasFactory,
          factoryOnly,
          color);
    }

    public Design withSegments(List<Segment> values, List<String> nextKeys) {
      return new Design(
          fileName,
          append,
          targetFile,
          deduplicate,
          includeAir,
          main,
          values,
          nextKeys,
          controller,
          orientation,
          partEdits,
          requiresBlueprint,
          hasFactory,
          factoryOnly,
          color);
    }

    public Design withSettings(int index, Settings value) {
      if (index < 0 || index >= segments.size()) {
        throw new IllegalArgumentException("Invalid selection segment index");
      }
      var next = new ArrayList<>(segments);
      var previous = next.get(index);
      next.set(index, new Segment(value, previous.body(), previous.start(), previous.end()));

      return withSegments(next, keys);
    }

    public Design withOptions(String nextFileName, boolean nextAppend, String nextTargetFile, boolean nextDeduplicate, boolean nextIncludeAir) {
      return new Design(
          nextFileName,
          nextAppend,
          nextTargetFile,
          nextDeduplicate,
          nextIncludeAir,
          main,
          segments,
          keys,
          controller,
          orientation,
          partEdits,
          requiresBlueprint,
          hasFactory,
          factoryOnly,
          color);
    }

    public Design withMachineFlags(boolean nextRequiresBlueprint, boolean nextHasFactory, boolean nextFactoryOnly) {
      return new Design(
          fileName,
          append,
          targetFile,
          deduplicate,
          includeAir,
          main,
          segments,
          keys,
          controller,
          orientation,
          partEdits,
          nextRequiresBlueprint,
          nextHasFactory,
          nextFactoryOnly,
          color);
    }

    public Design withColor(String nextColor) {
      return new Design(
          fileName,
          append,
          targetFile,
          deduplicate,
          includeAir,
          main,
          segments,
          keys,
          controller,
          orientation,
          partEdits,
          requiresBlueprint,
          hasFactory,
          factoryOnly,
          nextColor);
    }

    public Design withBasis(@Nullable BlockPos position, MachineOrientation nextOrientation) {
      return new Design(
          fileName,
          append,
          targetFile,
          deduplicate,
          includeAir,
          main,
          segments,
          keys,
          position,
          nextOrientation,
          partEdits,
          requiresBlueprint,
          hasFactory,
          factoryOnly,
          color);
    }

    public Design withPartEdit(BlockPos position, @Nullable PartEdit edit) {
      var next = new LinkedHashMap<>(partEdits);
      var key = Objects.requireNonNull(position).immutable();
      if (edit == null || edit.empty()) {
        next.remove(key);
      } else {
        next.put(key, edit);
      }

      return new Design(
          fileName,
          append,
          targetFile,
          deduplicate,
          includeAir,
          main,
          segments,
          keys,
          controller,
          orientation,
          next,
          requiresBlueprint,
          hasFactory,
          factoryOnly,
          color);
    }

    public Design withoutPartEdits() {
      if (partEdits.isEmpty()) {
        return this;
      }

      return new Design(
          fileName,
          append,
          targetFile,
          deduplicate,
          includeAir,
          main,
          segments,
          keys,
          controller,
          orientation,
          Map.of(),
          requiresBlueprint,
          hasFactory,
          factoryOnly,
          color);
    }

    private static Settings designSettings(Settings value) {
      if (value.maximum() < 1) {
        throw new IllegalArgumentException("A selection design needs at least one dynamic layer");
      }
      if (value.minimum() == 1) {
        return value;
      }

      return new Settings(
          value.id(),
          1,
          value.maximum(),
          value.direction(),
          value.gap(),
          value.customStep(),
          value.step(),
          value.customOrigin(),
          value.origin(),
          value.sampleLength(),
          value.selector(),
          value.interfaceType());
    }

    private static long volume(@Nullable Box box) {
      return box == null ? 0 : box.volume();
    }
  }

  public record FileOption(String token, ResourceLocation machine, String path, String label) {
    public FileOption {
      Objects.requireNonNull(token);
      Objects.requireNonNull(machine);
      Objects.requireNonNull(path);
      Objects.requireNonNull(label);
    }
  }

  /**
   * Empty block/property text inherits the source; '-' explicitly clears
   * properties.
   */
  public record PartEdit(
      String blocks,
      String properties,
      String propertiesSpace,
      boolean changeSelector,
      String selector,
      boolean changeInterface,
      String interfaceType) {
    public PartEdit {
      blocks = Objects.requireNonNull(blocks).strip();
      properties = Objects.requireNonNull(properties).strip();
      selector = Objects.requireNonNull(selector).strip();
      interfaceType = Objects.requireNonNull(interfaceType).strip();
      if (blocks.length() > 4096
          || properties.length() > 4096
          || selector.length() > 64
          || interfaceType.length() > 64
          || !("local".equals(propertiesSpace) || "world".equals(propertiesSpace))) {
        throw new IllegalArgumentException("Invalid part edit");
      }
    }

    public static PartEdit inherit() {
      return new PartEdit("", "", "world", false, "", false, "");
    }

    public boolean empty() {
      return blocks.isEmpty() && properties.isEmpty() && !changeSelector && !changeInterface;
    }
  }

  public record PartEditor(BlockPos position, String observedBlock, String observedProperties, PartEdit edit) {
    public PartEditor {
      position = Objects.requireNonNull(position).immutable();
      Objects.requireNonNull(observedBlock);
      Objects.requireNonNull(observedProperties);
      Objects.requireNonNull(edit);
      if (observedBlock.length() > 512 || observedProperties.length() > 4096) {
        throw new IllegalArgumentException("Oversized part description");
      }
    }
  }

  /** index selects a segment/page, value is a file token, filter, or filename. */
  public record Request(
      UUID session,
      long revision,
      Action action,
      PickTarget target,
      int index,
      String value,
      Settings settings,
      boolean deduplicate,
      boolean includeAir,
      PartEdit partEdit) {
    public Request(UUID session, long revision, Action action, PickTarget target, int index, String value, Settings settings, boolean deduplicate, boolean includeAir) {
      this(session, revision, action, target, index, value, settings, deduplicate, includeAir, null);
    }
    public Request {
      Objects.requireNonNull(session);
      Objects.requireNonNull(action);
      Objects.requireNonNull(target);
      Objects.requireNonNull(value);
      Objects.requireNonNull(settings);
      if (revision < 0 || value.length() > 256 || index < -1 || index > 4_194_304) {
        throw new IllegalArgumentException("Invalid ranged selection action");
      }
    }
  }

  public record Snapshot(
      UUID session,
      long revision,
      ResourceLocation dimension,
      boolean open,
      boolean close,
      boolean append,
      String fileToken,
      String fileLabel,
      String fileName,
      boolean deduplicate,
      boolean includeAir,
      Box main,
      List<Segment> segments,
      int selected,
      PickTarget picking,
      BlockPos first,
      Component status,
      boolean busy,
      List<FileOption> files,
      int filePage,
      int filePages,
      boolean hasResult,
      PartEditor partEditor,
      int partEdits) {
    public Snapshot {
      Objects.requireNonNull(session);
      Objects.requireNonNull(dimension);
      Objects.requireNonNull(fileToken);
      Objects.requireNonNull(fileLabel);
      Objects.requireNonNull(fileName);
      Objects.requireNonNull(picking);
      status = Objects.requireNonNull(status).copy();
      first = first == null ? null : first.immutable();
      segments = List.copyOf(segments);
      files = List.copyOf(files);
      if (revision < 0
          || segments.size() > MAX_SEGMENTS
          || files.size() > 32
          || filePage < 0
          || filePages < 1
          || partEdits < 0
          || partEdits > MAX_PART_EDITS
          || selected < 0
          || selected >= Math.max(1, segments.size())) {
        throw new IllegalArgumentException("Invalid ranged selection snapshot");
      }
    }
  }
}
