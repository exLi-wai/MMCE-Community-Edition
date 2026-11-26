package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeScriptCallbacksTest {
  private static final ResourceLocation ADAPTER = ResourceLocation.parse("kubejs:adapter_checker");
  private static final ResourceLocation INLINE = ResourceLocation.parse("kubejs:recipe_checker");
  private static final ResourceLocation RECIPE = ResourceLocation.parse("mmcece:recipe");
  private static final ResourceLocation SKIPPED = ResourceLocation.parse("mmcece:skipped");
  private static final ResourceLocation MACHINE = ResourceLocation.parse("mmcece:machine");
  private static final RecipeItemCallbacks.Checker CHECKER = (context, item) -> true;

  private final Map<ResourceLocation, JsonObject> selected = new HashMap<>();

  @BeforeEach
  @AfterEach
  void reset() {
    RecipeScriptCallbacks.reset();
  }

  @Test
  void adapterOnlySnapshotIsEligibleForCatalogInstallation() {
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);

    var snapshot = withoutRecipeCapture();
    assertTrue(snapshot.active());
    assertTrue(RecipeScriptCallbacks.isCurrent(snapshot));
    assertSame(CHECKER, snapshot.checkers().get(ADAPTER));
  }

  @Test
  void finishingRecipeCaptureRetainsTheStagedCallbacksAndReleasesCapture() {
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);
    long generation = RecipeScriptCallbacks.begin(selected::get);
    captureSelected(RECIPE, checkerBindings(CHECKER));
    var staged = RecipeScriptCallbacks.snapshot(generation, Set.of(RECIPE));
    RecipeScriptCallbacks.end(generation);

    assertTrue(staged.active());
    assertTrue(RecipeScriptCallbacks.isCurrent(staged));
    assertSame(CHECKER, staged.checkers().get(ADAPTER));
    assertSame(CHECKER, staged.checkers().get(INLINE));
    assertThrows(IllegalStateException.class, () -> RecipeScriptCallbacks.snapshot(generation, Set.of(RECIPE)));
    assertEquals(Set.of(ADAPTER), withoutRecipeCapture().checkers().keySet());
  }

  @Test
  void newAdapterGenerationCanRemoveCallbacksWithoutARecipeEvent() {
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);
    long generation = RecipeScriptCallbacks.begin(selected::get);
    captureSelected(RECIPE, checkerBindings(CHECKER));
    var previous = RecipeScriptCallbacks.snapshot(generation, Set.of(RECIPE));
    RecipeScriptCallbacks.end(generation);
    RecipeScriptCallbacks.beginAdapters();

    var next = withoutRecipeCapture();
    assertTrue(next.active());
    assertTrue(next.checkers().isEmpty());
    assertFalse(RecipeScriptCallbacks.isCurrent(previous));
  }

  @Test
  void resetInvalidatesStagedCallbacksAndClearsAdapters() {
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);
    var previous = withoutRecipeCapture();
    RecipeScriptCallbacks.reset();

    assertFalse(RecipeScriptCallbacks.isCurrent(previous));
    assertFalse(withoutRecipeCapture().active());
    assertTrue(withoutRecipeCapture().checkers().isEmpty());
  }

  @Test
  void snapshotKeepsOnlyInlineCallbacksFromAcceptedScriptDeclarations() {
    var shared = ResourceLocation.parse("kubejs:shared_checker");
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);
    long generation = RecipeScriptCallbacks.begin(selected::get);
    var retained = new RecipePreparationHandlers.Handler(
        shared,
        MACHINE,
        RECIPE,
        RecipePreparationEvents.Type.PRE_CHECK,
        false,
        event -> {
        });
    captureSelected(
        RECIPE,
        new RecipeScriptCallbacks.Bindings(Map.of(shared, CHECKER), Map.of(), Map.of(), List.of(), List.of(retained)));
    captureSelected(
        SKIPPED,
        new RecipeScriptCallbacks.Bindings(
            Map.of(INLINE, CHECKER, shared, (context, item) -> false),
            Map.of(INLINE, (context, item) -> item),
            Map.of(INLINE, (context, item) -> null),
            List.of(lifecycleHandler(SKIPPED)),
            List.of(preparationHandler(SKIPPED))));

    var snapshot = RecipeScriptCallbacks.snapshot(generation, Set.of(RECIPE));

    assertEquals(Set.of(ADAPTER, shared), snapshot.checkers().keySet());
    assertSame(CHECKER, snapshot.checkers().get(shared));
    assertTrue(snapshot.modifiers().isEmpty());
    assertTrue(snapshot.inputModifiers().isEmpty());
    assertTrue(snapshot.lifecycle().isEmpty());
    assertEquals(List.of(retained), snapshot.preparation());
  }

  @Test
  void endingAnOlderCaptureKeepsTheCurrentGenerationOpen() {
    long older = RecipeScriptCallbacks.begin(selected::get);
    long current = RecipeScriptCallbacks.begin(selected::get);
    RecipeScriptCallbacks.end(older);
    captureSelected(RECIPE, checkerBindings(CHECKER));

    assertThrows(IllegalStateException.class, () -> RecipeScriptCallbacks.snapshot(older, Set.of(RECIPE)));
    assertSame(CHECKER, RecipeScriptCallbacks.snapshot(current, Set.of(RECIPE)).checkers().get(INLINE));
    assertTrue(withoutRecipeCapture().checkers().isEmpty());
  }

  @Test
  void countsEachDeclarationUntilKubeJsHasSelectedTheWinner() {
    long generation = RecipeScriptCallbacks.begin(selected::get);
    for (int index = 0; index < ServerCallbackRegistry.MAX_HANDLERS; index++) {
      captureSelected(recipe(index), checkerBindings(CHECKER));
    }
    var overflow = recipe(ServerCallbackRegistry.MAX_HANDLERS);
    assertThrows(IllegalStateException.class, () -> captureSelected(overflow, checkerBindings(CHECKER)));

    RecipeItemCallbacks.Checker replacement = (context, item) -> false;
    assertThrows(IllegalStateException.class, () -> captureSelected(recipe(0), checkerBindings(replacement)));
    var staged = RecipeScriptCallbacks.snapshot(generation, Set.of(recipe(0), recipe(1), overflow));
    assertSame(CHECKER, staged.checkers().get(INLINE));

    RecipeScriptCallbacks.end(generation);
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);
    assertSame(CHECKER, staged.checkers().get(INLINE));
    assertSame(CHECKER, withoutRecipeCapture().checkers().get(ADAPTER));
  }

  @Test
  void onlyTheWinningSameIdDeclarationContributesCallbacksEvenWhenTheJsonIsEqual() {
    long generation = RecipeScriptCallbacks.begin(selected::get);
    var guarded = new JsonObject();
    var other = guarded.deepCopy();
    var unguarded = guarded.deepCopy();
    var guard = preparationHandler(RECIPE);
    RecipeScriptCallbacks.capture(
        RECIPE,
        guarded,
        new RecipeScriptCallbacks.Bindings(Map.of(), Map.of(), Map.of(), List.of(), List.of(guard)));
    RecipeScriptCallbacks.capture(RECIPE, other, checkerBindings(CHECKER));
    RecipeScriptCallbacks.capture(
        RECIPE,
        unguarded,
        new RecipeScriptCallbacks.Bindings(Map.of(), Map.of(), Map.of(), List.of(), List.of()));

    selected.put(RECIPE, guarded);
    var first = RecipeScriptCallbacks.snapshot(generation, Set.of(RECIPE));
    assertEquals(List.of(guard), first.preparation());
    assertTrue(first.checkers().isEmpty());

    selected.put(RECIPE, other);
    var second = RecipeScriptCallbacks.snapshot(generation, Set.of(RECIPE));
    assertTrue(second.preparation().isEmpty());
    assertSame(CHECKER, second.checkers().get(INLINE));

    selected.put(RECIPE, unguarded);
    var third = RecipeScriptCallbacks.snapshot(generation, Set.of(RECIPE));
    assertTrue(third.preparation().isEmpty());
    assertTrue(third.checkers().isEmpty());
  }

  @Test
  void adapterLifecycleAndPreparationCallbacksShareTheInlineBindingLimit() {
    RecipeScriptCallbacks.beginAdapters();
    RecipeScriptCallbacks.adapterChecker(ADAPTER, CHECKER);
    RecipeScriptCallbacks.adapterModifier(ADAPTER, (context, item) -> item);
    RecipeScriptCallbacks.adapterInputModifier(ADAPTER, (context, item) -> null);
    long generation = RecipeScriptCallbacks.begin(selected::get);
    captureSelected(
        RECIPE,
        new RecipeScriptCallbacks.Bindings(
            Map.of(),
            Map.of(INLINE, (context, item) -> item),
            Map.of(INLINE, (context, item) -> null),
            List.of(lifecycleHandler(RECIPE)),
            List.of(preparationHandler(RECIPE))));
    for (int index = 0; index < ServerCallbackRegistry.MAX_HANDLERS - 7; index++) {
      captureSelected(recipe(index), checkerBindings(CHECKER));
    }
    assertThrows(IllegalStateException.class, () -> captureSelected(SKIPPED, checkerBindings(CHECKER)));
    assertThrows(IllegalStateException.class, () -> RecipeScriptCallbacks.adapterChecker(INLINE, CHECKER));

    RecipeScriptCallbacks.end(generation);
    long nextGeneration = RecipeScriptCallbacks.begin(selected::get);
    captureSelected(RECIPE, checkerBindings(CHECKER));
    assertEquals(
        Set.of(ADAPTER, INLINE),
        RecipeScriptCallbacks.snapshot(nextGeneration, Set.of(RECIPE)).checkers().keySet());
  }

  @Test
  void failedDeclarationKeepsThePreviousCallbacksAndLeavesRoomForTheNextRecipe() {
    long generation = RecipeScriptCallbacks.begin(selected::get);
    var original = checkerBindings(CHECKER);
    for (int index = 0; index < ServerCallbackRegistry.MAX_HANDLERS - 2; index++) {
      captureSelected(recipe(index), original);
    }
    captureSelected(RECIPE, original);
    var rejected = new RecipeScriptCallbacks.Bindings(
        Map.of(INLINE, (context, item) -> false),
        Map.of(),
        Map.of(),
        List.of(lifecycleHandler(RECIPE)),
        List.of(preparationHandler(RECIPE)));
    assertThrows(IllegalStateException.class, () -> captureSelected(RECIPE, rejected));
    captureSelected(SKIPPED, original);

    var staged = RecipeScriptCallbacks.snapshot(generation, Set.of(RECIPE));
    assertSame(CHECKER, staged.checkers().get(INLINE));
    assertTrue(staged.lifecycle().isEmpty());
    assertTrue(staged.preparation().isEmpty());
    assertSame(CHECKER, RecipeScriptCallbacks.snapshot(generation, Set.of(SKIPPED)).checkers().get(INLINE));
  }

  private void captureSelected(ResourceLocation id, RecipeScriptCallbacks.Bindings bindings) {
    var source = new JsonObject();
    RecipeScriptCallbacks.capture(id, source, bindings);
    selected.put(id, source);
  }

  private static RecipeScriptCallbacks.Bindings checkerBindings(RecipeItemCallbacks.Checker checker) {
    return new RecipeScriptCallbacks.Bindings(Map.of(INLINE, checker), Map.of(), Map.of(), List.of(), List.of());
  }

  private static RecipeDeclaredObservers.Handler lifecycleHandler(ResourceLocation recipe) {
    return new RecipeDeclaredObservers.Handler(
        INLINE,
        MACHINE,
        recipe,
        RecipeLifecycleEvents.Type.STARTED,
        false,
        event -> {
        });
  }

  private static RecipePreparationHandlers.Handler preparationHandler(ResourceLocation recipe) {
    return new RecipePreparationHandlers.Handler(
        INLINE,
        MACHINE,
        recipe,
        RecipePreparationEvents.Type.PRE_CHECK,
        false,
        event -> {
        });
  }

  private static ResourceLocation recipe(int index) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", "recipe_" + index);
  }

  private static RecipeScriptCallbacks.Snapshot withoutRecipeCapture() {
    return RecipeScriptCallbacks.snapshot(RecipeScriptSources.NO_CALLBACK_GENERATION, Set.of());
  }
}
