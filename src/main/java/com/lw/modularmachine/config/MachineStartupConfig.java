package com.lw.modularmachine.config;

import com.electronwill.nightconfig.toml.TomlFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal startup registration policy; live edits only affect restart
 * diagnostics.
 */
@ApiStatus.Internal
public final class MachineStartupConfig {
  public static final String FILE_NAME = "modularmachinery/mmcece-startup.toml";
  public static final String ENABLED_PATH = "factory-system.enable-factory-controller-bydefault";
  public static final String COLOR_PATH = "general.general-casing-color";
  public static final ModConfigSpec SPEC;
  public static final ModConfigSpec.BooleanValue ENABLE_FACTORY_CONTROLLER_BY_DEFAULT;
  public static final ModConfigSpec.ConfigValue<String> DEFAULT_CASING_COLOR;
  private static Snapshot startupValue;

  static {
    var builder = new ModConfigSpec.Builder();
    builder.translation("mmcece.config.general").push("general");
    DEFAULT_CASING_COLOR = builder.translation("mmcece.config.general.defaultCasingColor")
        .comment(
            "Default casing RGB color when a machine has no explicit color; use six hexadecimal digits.",
            "Requires a full client and server restart, with matching startup configuration on both sides.")
        .gameRestart().define(
            "general-casing-color",
            "FFFFFF",
            value -> value instanceof String color && color.matches("[0-9a-fA-F]{6}"));
    builder.pop();
    builder.translation("mmcece.config.factory_system").push("factory-system");
    ENABLE_FACTORY_CONTROLLER_BY_DEFAULT = builder
        .translation("mmcece.config.factory_system.enableFactoryControllerByDefault")
        .comment(
            "Register an integration controller for every machine at startup, regardless of its has-factory flag.",
            "Requires a full client and server restart. Both sides must use the same registration configuration.")
        .gameRestart().define("enable-factory-controller-bydefault", false);
    builder.pop();
    SPEC = builder.build();
  }

  private MachineStartupConfig() {
  }

  private static synchronized Snapshot snapshot() {
    if (startupValue != null) {
      return startupValue;
    }
    if (!SPEC.isLoaded()) {
      throw new IllegalStateException("Machine startup config must be registered before machine registrations");
    }
    startupValue = new Snapshot(
        ENABLE_FACTORY_CONTROLLER_BY_DEFAULT.get(),
        Integer.parseInt(DEFAULT_CASING_COLOR.get(), 16));

    return startupValue;
  }

  public static boolean enabledAtStartup() {
    return snapshot().factoryEnabled();
  }

  public static int defaultCasingColorAtStartup() {
    return snapshot().color();
  }

  public static Path path() {
    return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
  }

  /**
   * Reads only for restart diagnostics, independently of the file watcher delay.
   */
  public static boolean currentConfiguredValue() {
    return currentSnapshot().factoryEnabled();
  }

  private static Snapshot currentSnapshot() {
    Path path = path();
    if (Files.notExists(path)) {
      return new Snapshot(false, 0xFFFFFF);
    }
    try {
      var config = TomlFormat.instance().createParser().parse(Files.readString(path, StandardCharsets.UTF_8));
      Object value = config.get(ENABLED_PATH);
      if (value != null && !(value instanceof Boolean)) {
        throw new IllegalArgumentException("Expected a boolean startup config value: " + ENABLED_PATH);
      }
      Object colorValue = config.get(COLOR_PATH);
      String color = colorValue == null ? "FFFFFF" : colorValue.toString();
      if (colorValue != null && !(colorValue instanceof String) || !color.matches("[0-9a-fA-F]{6}")) {
        throw new IllegalArgumentException("Expected six hexadecimal digits: " + COLOR_PATH);
      }

      return new Snapshot(Boolean.TRUE.equals(value), Integer.parseInt(color, 16));
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot read machine startup configuration " + path, failure);
    }
  }

  public static boolean restartRequired() {
    try {
      return !snapshot().equals(currentSnapshot());
    } catch (RuntimeException invalid) {
      return true;
    }
  }

  private record Snapshot(boolean factoryEnabled, int color) {
  }
}
