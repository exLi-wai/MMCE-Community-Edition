package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.machine.recipe.command.RecipeCommandService;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-owned command declarations at committed recipe lifecycle boundaries.
 */
public final class RecipeCommandActions {
  public static final int MAX_COMMAND_LENGTH = 2048;

  public enum Source {
    CONTROLLER,
    OWNER
  }

  public record Action(
      ResourceLocation id,
      ResourceLocation machine,
      Optional<ResourceLocation> recipe,
      RecipeLifecycleEvents.Type type,
      int interval,
      String command,
      Source source,
      int permissionLevel) {
    public Action {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(recipe, "recipe");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(command, "command");
      Objects.requireNonNull(source, "source");
      if (id.toString().length() > 256
          || machine.toString().length() > 256
          || recipe.isPresent() && recipe.get().toString().length() > 256
          || type != RecipeLifecycleEvents.Type.STARTED
              && type != RecipeLifecycleEvents.Type.TICK
              && type != RecipeLifecycleEvents.Type.FINISHED
          || interval < 1
          || type != RecipeLifecycleEvents.Type.TICK && interval != 1
          || permissionLevel < 0
          || permissionLevel > 2) {
        throw new IllegalArgumentException("Invalid recipe command declaration");
      }
      command = command.strip();
      if (command.isEmpty()
          || command.length() > MAX_COMMAND_LENGTH
          || command.chars().anyMatch(value -> value < 32 || value == 127)) {
        throw new IllegalArgumentException("Recipe commands require one nonempty bounded line");
      }
    }
  }

  public record Status(
      boolean valid,
      int pending,
      int tracked,
      long claimed,
      long rejected,
      long skipped,
      long failed) {
  }

  private RecipeCommandActions() {
  }

  public static void register(Action action) {
    RecipeCommandService.register(action);
  }

  public static void remove(ResourceLocation id) {
    RecipeCommandService.remove(id);
  }

  public static Optional<Status> inspect(ServerLevel level, BlockPos position) {
    return RecipeCommandService.inspect(level, position);
  }
}
