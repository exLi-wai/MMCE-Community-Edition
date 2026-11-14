package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubeJSRecipeItemCallbacksTest {
  private static final ResourceLocation ADAPTER = ResourceLocation.parse("kubejs:adapter_checker");
  private static final ResourceLocation RECIPE = ResourceLocation.parse("kubejs:recipe_checker");
  private static final RecipeItemCallbacks.Checker CHECKER = (context, item) -> true;

  @BeforeEach
  @AfterEach
  void reset() {
    KubeJSRecipeItemCallbacks.reset();
  }

  @Test
  void adapterOnlySnapshotIsEligibleForCatalogInstallation() {
    KubeJSRecipeItemCallbacks.beginAdapters();
    KubeJSRecipeItemCallbacks.adapterChecker(ADAPTER, CHECKER);

    var snapshot = KubeJSRecipeItemCallbacks.snapshot();
    assertTrue(snapshot.active());
    assertTrue(KubeJSRecipeItemCallbacks.isCurrent(snapshot));
    assertSame(CHECKER, snapshot.checkers().get(ADAPTER));
  }

  @Test
  void finishingRecipeCaptureRetainsTheCombinedCallbacks() {
    KubeJSRecipeItemCallbacks.beginAdapters();
    KubeJSRecipeItemCallbacks.adapterChecker(ADAPTER, CHECKER);
    KubeJSRecipeItemCallbacks.begin();
    KubeJSRecipeItemCallbacks.checker(RECIPE, CHECKER);
    var staged = KubeJSRecipeItemCallbacks.snapshot();
    KubeJSRecipeItemCallbacks.end();

    var retained = KubeJSRecipeItemCallbacks.snapshot();
    assertTrue(retained.active());
    assertTrue(KubeJSRecipeItemCallbacks.isCurrent(staged));
    assertSame(CHECKER, retained.checkers().get(ADAPTER));
    assertSame(CHECKER, retained.checkers().get(RECIPE));
  }

  @Test
  void newAdapterGenerationCanRemoveCallbacksWithoutARecipeEvent() {
    KubeJSRecipeItemCallbacks.beginAdapters();
    KubeJSRecipeItemCallbacks.adapterChecker(ADAPTER, CHECKER);
    KubeJSRecipeItemCallbacks.begin();
    KubeJSRecipeItemCallbacks.checker(RECIPE, CHECKER);
    KubeJSRecipeItemCallbacks.end();
    var previous = KubeJSRecipeItemCallbacks.snapshot();
    KubeJSRecipeItemCallbacks.beginAdapters();

    var next = KubeJSRecipeItemCallbacks.snapshot();
    assertTrue(next.active());
    assertTrue(next.checkers().isEmpty());
    assertFalse(KubeJSRecipeItemCallbacks.isCurrent(previous));
  }

  @Test
  void resetInvalidatesStagedCallbacksAndClearsAdapters() {
    KubeJSRecipeItemCallbacks.beginAdapters();
    KubeJSRecipeItemCallbacks.adapterChecker(ADAPTER, CHECKER);
    var previous = KubeJSRecipeItemCallbacks.snapshot();
    KubeJSRecipeItemCallbacks.reset();

    assertFalse(KubeJSRecipeItemCallbacks.isCurrent(previous));
    assertFalse(KubeJSRecipeItemCallbacks.snapshot().active());
    assertTrue(KubeJSRecipeItemCallbacks.snapshot().checkers().isEmpty());
  }
}
