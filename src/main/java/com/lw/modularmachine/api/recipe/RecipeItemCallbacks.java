package com.lw.modularmachine.api.recipe;

import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.machine.recipe.RecipeItemCallbackService;
import java.util.Objects;
import java.util.Optional;
import java.math.BigDecimal;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Main-thread preparation callbacks over detached item copies and immutable
 * controller information.
 */
public final class RecipeItemCallbacks {
  @FunctionalInterface
  public interface Checker {
    boolean check(Context context, ItemStack item);
  }

  @FunctionalInterface
  public interface Modifier {
    ItemStack modify(Context context, ItemStack item);
  }

  @FunctionalInterface
  public interface InputModifier {
    ItemRequirement modify(Context context, ItemStack prototype);
  }

  /** Detached single-item prototype and exact replacement demand amount. */
  public record ItemRequirement(ItemStack stack, long amount) {
    public ItemRequirement {
      Objects.requireNonNull(stack, "stack");
      if (stack.isEmpty() || stack.getCount() != 1 || amount < 1) {
        throw new IllegalArgumentException("Item requirements need one nonempty prototype and a positive amount");
      }
      stack = stack.copy();
    }

    @Override
    public ItemStack stack() {
      return stack.copy();
    }
  }

  public record Context(
      ResourceLocation dimension,
      BlockPos controllerPosition,
      ResourceLocation machine,
      ResourceLocation recipe,
      boolean factory,
      Optional<String> threadId,
      long tick,
      int parallelInstances,
      RecipeModifier.Io io,
      int row,
      long amount,
      ControllerOwnership ownership) {
    public Context {
      Objects.requireNonNull(dimension, "dimension");
      controllerPosition = Objects.requireNonNull(controllerPosition, "controllerPosition").immutable();
      Objects.requireNonNull(machine, "machine");
      Objects.requireNonNull(recipe, "recipe");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(io, "io");
      Objects.requireNonNull(ownership, "ownership");
      if (tick < 0 || parallelInstances < 1 || row < 0 || row >= 256 || amount < 1 || factory != threadId.isPresent()) {
        throw new IllegalArgumentException("Invalid recipe item callback context");
      }
    }

    public Context withRow(RecipeModifier.Io rowIo, int rowIndex, long rowAmount) {
      return new Context(
          dimension,
          controllerPosition,
          machine,
          recipe,
          factory,
          threadId,
          tick,
          parallelInstances,
          rowIo,
          rowIndex,
          rowAmount,
          ownership);
    }
  }

  private RecipeItemCallbacks() {
  }

  public static void registerChecker(ResourceLocation id, Checker checker) {
    RecipeItemCallbackService.registerChecker(id, checker);
  }

  public static void registerModifier(ResourceLocation id, Modifier modifier) {
    RecipeItemCallbackService.registerModifier(id, modifier);
  }

  public static void registerInputModifier(ResourceLocation id, InputModifier modifier) {
    RecipeItemCallbackService.registerInputModifier(id, modifier);
  }

  public static ItemRequirement requirement(ItemStack stack, Number amount) {
    Objects.requireNonNull(amount, "amount");
    if ((amount instanceof Double || amount instanceof Float) && Math.abs(amount.doubleValue()) > 9007199254740991D) {
      throw new IllegalArgumentException("Large item requirement amounts need an exact decimal string");
    }
    return new ItemRequirement(stack, new BigDecimal(amount.toString()).longValueExact());
  }

  public static ItemRequirement requirement(ItemStack stack, String amount) {
    if (amount == null || !amount.matches("[0-9]+")) {
      throw new IllegalArgumentException("Item requirement amounts need a positive decimal integer string");
    }
    return new ItemRequirement(stack, new BigDecimal(amount).longValueExact());
  }

  public static void removeChecker(ResourceLocation id) {
    RecipeItemCallbackService.removeChecker(id);
  }

  public static void removeModifier(ResourceLocation id) {
    RecipeItemCallbackService.removeModifier(id);
  }

  public static void removeInputModifier(ResourceLocation id) {
    RecipeItemCallbackService.removeInputModifier(id);
  }

  public static void invalidateChecker(ResourceLocation id) {
    RecipeItemCallbackService.invalidateChecker(id);
  }

  public static void invalidateModifier(ResourceLocation id) {
    RecipeItemCallbackService.invalidateModifier(id);
  }

  public static void invalidateInputModifier(ResourceLocation id) {
    RecipeItemCallbackService.invalidateInputModifier(id);
  }
}
