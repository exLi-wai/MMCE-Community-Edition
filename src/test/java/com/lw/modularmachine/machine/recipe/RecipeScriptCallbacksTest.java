package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeScriptCallbacksTest {
  private static final ResourceLocation ADAPTER = ResourceLocation.parse("kubejs:adapter_checker");
  private static final ResourceLocation RECIPE = ResourceLocation.parse("kubejs:recipe_checker");
  private static final RecipeItemCallbacks.Checker CHECKER = (context, item) -> true;

  @BeforeEach
  @AfterEach
  void reset() {
    RecipeScriptCallbacks.reset();
  }

  @Test
  void adapterOnlySnapshotIsEligibleForCatalogInstallation() {
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);

    var snapshot = RecipeScriptCallbacks.snapshot();
    assertTrue(snapshot.active());
    assertTrue(RecipeScriptCallbacks.isCurrent(snapshot));
    assertSame(CHECKER, snapshot.checkers().get(ADAPTER));
  }

  @Test
  void finishingRecipeCaptureRetainsTheCombinedCallbacks() {
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);
    RecipeScriptCallbacks.begin();
    RecipeScriptCallbacks.checker(RECIPE, CHECKER);
    var staged = RecipeScriptCallbacks.snapshot();
    RecipeScriptCallbacks.end();

    var retained = RecipeScriptCallbacks.snapshot();
    assertTrue(retained.active());
    assertTrue(RecipeScriptCallbacks.isCurrent(staged));
    assertSame(CHECKER, retained.checkers().get(ADAPTER));
    assertSame(CHECKER, retained.checkers().get(RECIPE));
  }

  @Test
  void newAdapterGenerationCanRemoveCallbacksWithoutARecipeEvent() {
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);
    RecipeScriptCallbacks.begin();
    RecipeScriptCallbacks.checker(RECIPE, CHECKER);
    RecipeScriptCallbacks.end();
    var previous = RecipeScriptCallbacks.snapshot();
    RecipeScriptCallbacks.beginAdapters();

    var next = RecipeScriptCallbacks.snapshot();
    assertTrue(next.active());
    assertTrue(next.checkers().isEmpty());
    assertFalse(RecipeScriptCallbacks.isCurrent(previous));
  }

  @Test
  void resetInvalidatesStagedCallbacksAndClearsAdapters() {
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);
    var previous = RecipeScriptCallbacks.snapshot();
    RecipeScriptCallbacks.reset();

    assertFalse(RecipeScriptCallbacks.isCurrent(previous));
    assertFalse(RecipeScriptCallbacks.snapshot().active());
    assertTrue(RecipeScriptCallbacks.snapshot().checkers().isEmpty());
  }
}
