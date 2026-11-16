package com.lw.modularmachine.machine.upgrade;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.api.upgrade.UpgradeRecipeChecks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpgradeRecipeChecksTest {
  private static final ResourceLocation ID = ResourceLocation.parse("test:check");

  @Test
  void contextOwnsControllerPositionAndNestedRecipeData() {
    var position = new BlockPos.MutableBlockPos(1, 2, 3);
    var recipe = new CompoundTag();
    var nested = new CompoundTag();
    nested.putInt("value", 7);
    recipe.put("nested", nested);
    var context = new UpgradeRecipeChecks.Context(
        ID,
        ID,
        ID,
        position,
        "main",
        Long.MAX_VALUE,
        UpgradeSnapshot.identity(0),
        recipe);
    position.set(7, 8, 9);
    nested.putInt("value", 99);
    context.recipeData().getCompound("nested").putInt("value", 9);

    assertEquals(new BlockPos(1, 2, 3), context.controllerPosition());
    assertEquals(7, context.recipeData().getCompound("nested").getInt("value"));
    assertEquals(Long.MAX_VALUE, context.epoch());
  }

  @Test
  void rejectsInvalidThreadNamesAndNegativeEpochs() {
    for (String thread : List.of("", "bad thread", "a".repeat(65))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new UpgradeRecipeChecks.Context(
              ID,
              ID,
              ID,
              BlockPos.ZERO,
              thread,
              0,
              UpgradeSnapshot.identity(0),
              new CompoundTag()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeRecipeChecks.Context(
            ID,
            ID,
            ID,
            BlockPos.ZERO,
            "main",
            -1,
            UpgradeSnapshot.identity(0),
            new CompoundTag()));
  }

  @Test
  void rejectsInvalidWorkReports() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpgradeRecipeCheckHandlers.Result(UpgradeRecipeCheckHandlers.Status.PENDING, -1));
    assertThrows(NullPointerException.class, () -> new UpgradeRecipeCheckHandlers.Result(null, 0));
  }
}
