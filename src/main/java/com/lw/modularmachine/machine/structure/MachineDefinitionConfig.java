package com.lw.modularmachine.machine.structure;

import com.google.gson.JsonParseException;
import com.lw.modularmachine.ModularMachine;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/** Config directory and path-to-ID mapping for machine definitions. */
public final class MachineDefinitionConfig {
  private MachineDefinitionConfig() {
  }

  public static Path directory() {
    return FMLPaths.CONFIGDIR.get().resolve("modularmachinery/machinery").toAbsolutePath().normalize();
  }

  public static ResourceLocation id(Path relative) {
    var segments = new ArrayList<String>();
    for (Path segment : relative) {
      segments.add(segment.toString());
    }
    String fileName = segments.remove(segments.size() - 1);
    segments.add(fileName.substring(0, fileName.length() - ".json".length()));
    String namespace = ModularMachine.MODID;
    if (segments.size() > 1 && ResourceLocation.isValidNamespace(segments.get(0))) {
      namespace = segments.remove(0);
    }
    var id = ResourceLocation.tryBuild(namespace, String.join("/", segments));
    if (id == null) {
      throw new JsonParseException(
          relative + ": Invalid machine definition path; file and directory names become the machine ID and must use " + "lowercase a-z, 0-9, '_', '-' or '.' only (rename the file, e.g. AE.json -> ae.json)");
    }
    return id;
  }

}
