package com.lw.modularmachine.machine.selection;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.MachineDefinitionConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns a bounded world selection into a schema 2 machine definition, relative
 * to the controller and already in its local frame. Ports keep their resource
 * role; other blocks are exported as plain block parts.
 */
public final class RangedSelectionService {
  public record Result(Path file, ResourceLocation machine, int parts, int missingChunks) {
  }

  private RangedSelectionService() {
  }

  public static BlockPos min(BlockPos first, BlockPos second) {
    return new BlockPos(
        Math.min(first.getX(), second.getX()),
        Math.min(first.getY(), second.getY()),
        Math.min(first.getZ(), second.getZ()));
  }

  public static BlockPos max(BlockPos first, BlockPos second) {
    return new BlockPos(
        Math.max(first.getX(), second.getX()),
        Math.max(first.getY(), second.getY()),
        Math.max(first.getZ(), second.getZ()));
  }

  /**
   * Writes a definition into {@code config/modularmachinery/machinery/mmcece/}.
   */
  public static Result export(ServerLevel level, BlockPos controller, MachineOrientation orientation, BlockPos first, BlockPos second, String playerName)
      throws IOException {
    BlockPos min = min(first, second);
    BlockPos max = max(first, second);
    record Part(BlockPos offset, String json) {
    }
    List<Part> parts = new ArrayList<>();
    int missingChunks = 0;
    for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
      if (!level.hasChunkAt(pos)) {
        missingChunks++;
        continue;
      }
      BlockState state = level.getBlockState(pos);
      if (state.isAir()) {
        continue;
      }
      BlockPos offset = orientation.toLocalOffset(pos.subtract(controller));
      if (offset.equals(BlockPos.ZERO)) {
        continue;
      }
      JsonObject part = new JsonObject();
      JsonArray xyz = new JsonArray();
      xyz.add(offset.getX());
      xyz.add(offset.getY());
      xyz.add(offset.getZ());
      part.add("offset", xyz);
      if (state.getBlock() instanceof MachinePortBlock port) {
        part.addProperty("port", port.kind().name().toLowerCase(Locale.ROOT));
      } else {
        part.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
      }
      parts.add(new Part(offset, part.toString()));
    }
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("选区里除控制器外没有方块");
    }
    parts.sort(
        Comparator.comparingInt((Part part) -> part.offset().getY()).thenComparingInt(part -> part.offset().getX())
            .thenComparingInt(part -> part.offset().getZ()));
    String name = fileName(playerName);
    Path root = MachineDefinitionConfig.directory().resolve("mmcece");
    Files.createDirectories(root);
    Path file = unique(root.resolve(name + ".json"));
    String stem = file.getFileName().toString();
    stem = stem.substring(0, stem.length() - ".json".length());
    JsonArray array = new JsonArray();
    parts.forEach(part -> array.add(JsonParser.parseString(part.json())));
    JsonObject rootJson = new JsonObject();
    rootJson.addProperty("translation_key", "machine.mmcece." + stem);
    JsonObject orientationJson = new JsonObject();
    orientationJson.addProperty("policy", "all_24");
    rootJson.add("orientation", orientationJson);
    rootJson.add("parts", array);
    Files.writeString(
        file,
        new GsonBuilder().setPrettyPrinting().create().toJson(rootJson) + "\n",
        StandardCharsets.UTF_8);
    ResourceLocation machine = MachineDefinitionConfig.id(root.relativize(file));
    return new Result(file, machine, parts.size(), missingChunks);
  }

  private static Path unique(Path file) {
    if (!Files.exists(file)) {
      return file;
    }
    String fileName = file.getFileName().toString();
    String stem = fileName.substring(0, fileName.length() - ".json".length());
    for (int index = 1; index < 1000; index++) {
      Path candidate = file.resolveSibling(stem + "_" + index + ".json");
      if (!Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Too many exported definitions");
  }

  private static String fileName(String playerName) {
    StringBuilder builder = new StringBuilder();
    String lower = playerName == null ? "player" : playerName.toLowerCase(Locale.ROOT);
    for (int index = 0; index < lower.length() && builder.length() < 24; index++) {
      char character = lower.charAt(index);
      builder.append(character >= 'a' && character <= 'z' || character >= '0' && character <= '9' ? character : '_');
    }
    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
    return "exported_" + (builder.isEmpty() ? "player" : builder) + "_" + stamp;
  }
}
