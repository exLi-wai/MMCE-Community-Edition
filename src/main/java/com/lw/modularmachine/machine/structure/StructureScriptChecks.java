package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.structure.StructureBlockCheck;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.function.Supplier;

/**
 * Frozen predicates travel with compiled parts; failed reloads retain old
 * callbacks.
 */
@ApiStatus.Internal
public final class StructureScriptChecks {
  public static final class Condition {
    private final CompoundTag required;
    private final CompoundTag preview;
    private final StructureBlockCheck checker;
    private boolean failed;

    public Condition(CompoundTag required, CompoundTag preview, StructureBlockCheck checker) {
      if (required != null && required.sizeInBytes() > 65_536 || preview != null && preview.sizeInBytes() > 65_536) {
        throw new IllegalArgumentException("Structure matching/preview NBT exceeds 64 KiB");
      }
      this.required = required == null ? null : required.copy();
      this.preview = preview == null ? null : preview.copy();
      this.checker = checker;
    }

    public CompoundTag preview() {
      return preview == null ? new CompoundTag() : preview.copy();
    }

    public boolean needsData() {
      return required != null || checker != null;
    }

    public boolean matches(ServerLevel level, BlockPos pos, BlockState state) {
      ServerThreadGuard.require(level, "Structure script predicate");
      var entity = level.getBlockEntity(pos);
      var data = entity == null ? new CompoundTag() : entity.saveWithFullMetadata(level.registryAccess());
      if (required != null && !NbtUtils.compareNbt(required, data, true)) {
        return false;
      }
      if (checker == null) {
        return true;
      }
      if (checking) {
        throw new IllegalStateException("Recursive structure block predicate");
      }
      checking = true;
      try {
        return checker.matches(new StructureBlockCheck.Context(level.dimension().location(), pos, state, data));
      } catch (RuntimeException invalid) {
        if (!failed) {
          failed = true;
          ModularMachine.LOGGER.warn(
              "Structure block predicate rejected after an exception at {} in {}",
              pos,
              level.dimension().location(),
              invalid);
        }
        return false;
      } finally {
        checking = false;
      }
    }
  }

  private static final ThreadLocal<Map<String, Condition>> CAPTURE = new ThreadLocal<>();
  private static boolean checking;

  private StructureScriptChecks() {
  }

  public static <T> T capture(Map<String, Condition> conditions, Supplier<T> work) {
    var previous = CAPTURE.get();
    CAPTURE.set(conditions);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        CAPTURE.remove();
      } else {
        CAPTURE.set(previous);
      }
    }
  }

  public static Condition find(String name) {
    var conditions = CAPTURE.get();
    var result = conditions == null ? null : conditions.get(name);
    if (result == null) {
      throw new IllegalArgumentException("Unknown script block predicate " + name);
    }
    return result;
  }

  public static boolean isChecking() {
    return checking;
  }
}
