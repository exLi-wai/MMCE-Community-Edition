package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonParseException;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.config.MachineVariableDefaults;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/** Config directory and path-to-ID mapping for machine definitions. */
public final class MachineDefinitionConfig {
  private MachineDefinitionConfig() {
  }

  public static Path directory() {
    return FMLPaths.CONFIGDIR.get().resolve("modularmachinery/machinery").toAbsolutePath().normalize();
  }

  /**
   * Fresh instances have no machine directory; the directory itself is the
   * production entry point, so it is created here and an empty directory simply
   * means "no machines".
   */
  public static Path ensureDirectory() {
    Path root = directory();
    try {
      Files.createDirectories(root);
      MachineVariableDefaults.ensure();
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot create machine definition directory " + root, failure);
    }
    return root;
  }

  public static ResourceLocation id(Path relative) {
    var segments = new ArrayList<String>();
    for (Path segment : relative) {
      segments.add(segment.toString().toLowerCase(Locale.ROOT));
    }
    String fileName = segments.remove(segments.size() - 1);
    if (!fileName.endsWith(".json") || fileName.length() == ".json".length()) {
      throw new JsonParseException(relative + ": Machine definition files must end in .json");
    }
    segments.add(fileName.substring(0, fileName.length() - ".json".length()));
    String namespace = ModularMachine.MODID;
    if (segments.size() > 1 && ResourceLocation.isValidNamespace(segments.get(0))) {
      namespace = segments.remove(0);
    }
    var id = ResourceLocation.tryBuild(namespace, String.join("/", segments));
    if (id == null) {
      throw new JsonParseException(
          relative + ": Invalid machine definition path; file and directory names become the machine ID and may only " + "use a-z, 0-9, '_', '-' or '.' (names are lowercased automatically)");
    }
    return id;
  }

}
