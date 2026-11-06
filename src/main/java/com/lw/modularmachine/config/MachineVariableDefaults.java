package com.lw.modularmachine.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

public final class MachineVariableDefaults {
  private static final String DEFAULT_CASINGS = "/assets/mmcece/default_variables/casings.var.json";

  private MachineVariableDefaults() {
  }

  public static void ensure() {
    Path variables = FMLPaths.CONFIGDIR.get().resolve("modularmachinery/machinery/variables");
    Path casings = variables.resolve("casings.var.json");
    if (Files.exists(casings)) {
      return;
    }
    try (InputStream source = MachineVariableDefaults.class.getResourceAsStream(DEFAULT_CASINGS)) {
      if (source == null) {
        throw new IOException("Missing bundled default variable resource " + DEFAULT_CASINGS);
      }
      Files.createDirectories(variables);
      Files.copy(source, casings);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot create default machine variable file " + casings, failure);
    }
  }
}
