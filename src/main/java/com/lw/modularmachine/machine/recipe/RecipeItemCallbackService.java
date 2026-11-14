package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal item preparation, callback generations and reentry guard; no
 * callback is saved with a paid run.
 */
@ApiStatus.Internal
public final class RecipeItemCallbackService {
  private static long epoch;
  private static long scriptGeneration = -1;
  private static boolean preparing;
  private static final ServerCallbackRegistry<RecipeItemCallbacks.Checker> CHECKERS = new ServerCallbackRegistry<>(
      RecipeItemCallbackService::changed);
  private static final ServerCallbackRegistry<RecipeItemCallbacks.Modifier> MODIFIERS = new ServerCallbackRegistry<>(
      RecipeItemCallbackService::changed);
  private static final ServerCallbackRegistry<RecipeItemCallbacks.InputModifier> INPUT_MODIFIERS = new ServerCallbackRegistry<>(
      RecipeItemCallbackService::changed);
  private static Map<ResourceLocation, RecipeItemCallbacks.Checker> scriptCheckers = Map.of();
  private static Map<ResourceLocation, RecipeItemCallbacks.Modifier> scriptModifiers = Map.of();
  private static Map<ResourceLocation, RecipeItemCallbacks.InputModifier> scriptInputModifiers = Map.of();
  private static final Set<String> reported = new HashSet<>();

  public record Prepared(JsonObject declaration, long epoch) {
  }

  /** Owner-budgeted input transformation over one frozen declaration. */
  public static final class InputPreparation {
    private final JsonObject result;
    private final RecipeItemCallbacks.Context base;
    private final HolderLookup.Provider registries;
    private final List<RecipeScriptOptions.ItemCallbacks> bindings;
    private final long capturedEpoch;
    private int rowCursor, modifierCursor, workUsed;
    private ItemStack item;
    private long amount;
    private boolean rejected, complete;

    private InputPreparation(JsonObject original, RecipeScriptOptions options, RecipeItemCallbacks.Context base, HolderLookup.Provider registries) {
      result = original.deepCopy();
      this.base = base;
      this.registries = registries;
      capturedEpoch = epoch;
      options.validateRows(result.getAsJsonArray("inputs").size(), result.getAsJsonArray("outputs").size());
      bindings = options.itemCallbacks().stream()
          .filter(binding -> binding.io() == RecipeModifier.Io.INPUT && !binding.modifiers().isEmpty()).toList();
    }

    public int workUsed() {
      return workUsed;
    }

    public boolean rejected() {
      return rejected;
    }

    public Optional<Prepared> prepared() {
      return complete && !rejected && capturedEpoch == epoch ? Optional
          .of(new Prepared(result, capturedEpoch)) : Optional.empty();
    }

    public void advance(int budget) {
      ServerCallbackRegistry.requireServer();
      workUsed = 0;
      if (budget < 0) {
        throw new IllegalArgumentException("Negative input preparation budget");
      }
      if (rejected || complete || budget == 0) {
        return;
      }
      if (capturedEpoch != epoch || RecipeLifecyclePublisher.isDispatching()) {
        rejected = true;
        return;
      }
      ResourceLocation activeHandler = null;
      var ops = registries.createSerializationContext(JsonOps.INSTANCE);
      preparing = true;
      try {
        while (rowCursor < bindings.size() && workUsed < budget) {
          var binding = bindings.get(rowCursor);
          var row = result.getAsJsonArray("inputs").get(binding.row()).getAsJsonObject();
          if (item == null) {
            amount = row.get("amount").getAsBigDecimal().longValueExact();
            item = ItemStack.SINGLE_ITEM_CODEC.parse(ops, row.get("stack")).getOrThrow();
          }
          if (modifierCursor < binding.modifiers().size()) {
            activeHandler = binding.modifiers().get(modifierCursor++);
            workUsed++;
            var context = base.withRow(RecipeModifier.Io.INPUT, binding.row(), amount);
            var exact = inputModifier(activeHandler);
            if (exact != null) {
              var proposal = Objects.requireNonNull(
                  exact.modify(context, ItemStackSnapshots.detached(item, registries)),
                  "Input requirement proposal");
              item = ItemStackSnapshots.detached(proposal.stack(), registries);
              amount = proposal.amount();
            } else {
              var legacy = modifier(activeHandler);
              if (legacy == null || amount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                    "Input modifiers with long requirements need an InputModifier result");
              }
              var proposal = Objects.requireNonNull(
                  legacy.modify(context, ItemStackSnapshots.detached(item, registries).copyWithCount((int) amount)),
                  "Input item modifier result");
              if (proposal.isEmpty() || proposal.getCount() < 1) {
                throw new IllegalArgumentException("Input item modifiers must return a positive demand");
              }
              amount = proposal.getCount();
              item = ItemStackSnapshots.detached(proposal.copyWithCount(1), registries);
            }
            continue;
          }
          workUsed++;
          row.add("stack", ItemStack.SINGLE_ITEM_CODEC.encodeStart(ops, item).getOrThrow());
          row.addProperty("amount", amount);
          rowCursor++;
          modifierCursor = 0;
          item = null;
        }
        if (rowCursor == bindings.size() && workUsed < budget) {
          workUsed++;
          if (result.has("item_callbacks")) {
            var retained = new JsonArray();
            for (var element : result.getAsJsonArray("item_callbacks")) {
              var binding = element.getAsJsonObject().deepCopy();
              if (binding.get("io").getAsString().equals("input")) {
                binding.remove("modifiers");
                if (!binding.has("checker")) {
                  continue;
                }
              }
              retained.add(binding);
            }
            if (retained.isEmpty()) {
              result.remove("item_callbacks");
            } else {
              result.add("item_callbacks", retained);
            }
          }
          MachineRecipeJson.validate(result);
          MachineRecipe.CODEC.codec().parse(ops, result).getOrThrow();
          complete = capturedEpoch == epoch;
          rejected = !complete;
        }
      } catch (RuntimeException invalid) {
        report(activeHandler, base, invalid);
        rejected = true;
      } finally {
        preparing = false;
      }
    }
  }

  public static InputPreparation beginInputs(JsonObject declaration, RecipeScriptOptions options, RecipeItemCallbacks.Context context, HolderLookup.Provider registries) {
    ServerCallbackRegistry.requireServer();
    return new InputPreparation(declaration, options, context, registries);
  }

  public static RecipeCandidate<ItemResourceKey> compilePrepared(MachineRecipe definition, ResourceLocation id, HolderLookup.Provider registries) {
    return definition.compile(id, registries);
  }

  /**
   * The caller supplies every physically selected input slice, including
   * nondeducted catalysts.
   */
  public record InputItem(int row, ItemStack item, long amount) {
    public InputItem {
      Objects.requireNonNull(item, "item");
      if (row < 0 || row >= 256 || item.isEmpty() || amount < 1) {
        throw new IllegalArgumentException("Invalid item callback input slice");
      }
    }
  }

  private RecipeItemCallbackService() {
  }

  private static void changed() {
    if (preparing) {
      throw new IllegalStateException("Item callbacks cannot change their registry while preparing a recipe");
    }
    epoch = Math.incrementExact(epoch);
    reported.clear();
  }

  public static boolean isPreparing() {
    return preparing;
  }

  public static long epoch() {
    return epoch;
  }

  public static boolean isCurrent(long expectedEpoch) {
    return epoch == expectedEpoch;
  }

  public static void registerChecker(ResourceLocation id, RecipeItemCallbacks.Checker checker) {
    CHECKERS.register(id, checker);
  }

  public static void registerModifier(ResourceLocation id, RecipeItemCallbacks.Modifier modifier) {
    MODIFIERS.register(id, modifier);
  }

  public static void registerInputModifier(ResourceLocation id, RecipeItemCallbacks.InputModifier modifier) {
    INPUT_MODIFIERS.register(id, modifier);
  }

  public static void removeChecker(ResourceLocation id) {
    CHECKERS.remove(id);
  }

  public static void removeModifier(ResourceLocation id) {
    MODIFIERS.remove(id);
  }

  public static void removeInputModifier(ResourceLocation id) {
    INPUT_MODIFIERS.remove(id);
  }

  public static void invalidateChecker(ResourceLocation id) {
    CHECKERS.invalidate(id);
  }

  public static void invalidateModifier(ResourceLocation id) {
    MODIFIERS.invalidate(id);
  }

  public static void invalidateInputModifier(ResourceLocation id) {
    INPUT_MODIFIERS.invalidate(id);
  }

  /**
   * One sealed KubeJS generation; Java host handlers retain precedence over equal
   * script IDs.
   */
  public static void installKubeJS(long generation, Map<ResourceLocation, RecipeItemCallbacks.Checker> checkers, Map<ResourceLocation, RecipeItemCallbacks.Modifier> modifiers) {
    installKubeJS(generation, checkers, modifiers, Map.of());
  }

  public static void installKubeJS(long generation, Map<ResourceLocation, RecipeItemCallbacks.Checker> checkers, Map<ResourceLocation, RecipeItemCallbacks.Modifier> modifiers, Map<ResourceLocation, RecipeItemCallbacks.InputModifier> inputModifiers) {
    ServerCallbackRegistry.requireServer();
    if (generation < scriptGeneration) {
      return;
    }
    var nextCheckers = Map.copyOf(checkers);
    var nextModifiers = Map.copyOf(modifiers);
    var nextInputModifiers = Map.copyOf(inputModifiers);
    if (nextCheckers.size() + nextModifiers.size() + nextInputModifiers.size() > ServerCallbackRegistry.MAX_HANDLERS) {
      throw new IllegalArgumentException("Too many KubeJS recipe item callbacks");
    }
    for (var id : nextCheckers.keySet()) {
      requireScriptId(id);
    }
    for (var id : nextModifiers.keySet()) {
      requireScriptId(id);
    }
    for (var id : nextInputModifiers.keySet()) {
      requireScriptId(id);
    }
    if (generation != scriptGeneration
        || !nextCheckers.equals(scriptCheckers)
        || !nextModifiers.equals(scriptModifiers)
        || !nextInputModifiers.equals(scriptInputModifiers)) {
      changed();
      scriptGeneration = generation;
      scriptCheckers = nextCheckers;
      scriptModifiers = nextModifiers;
      scriptInputModifiers = nextInputModifiers;
    }
  }

  private static void requireScriptId(ResourceLocation id) {
    if (!id.getNamespace().equals("kubejs") || id.toString().length() > 256) {
      throw new IllegalArgumentException("KubeJS item callbacks require bounded kubejs IDs");
    }
  }

  /**
   * Converts required inputs before any planner sees the candidate; inventories
   * remain untouched.
   */
  public static Optional<Prepared> prepareInputs(JsonObject original, RecipeScriptOptions options, RecipeItemCallbacks.Context base, HolderLookup.Provider registries) {
    var preparation = beginInputs(original, options, base, registries);
    preparation.advance(Integer.MAX_VALUE);
    return preparation.prepared();
  }
  public static Optional<Prepared> prepareOutputs(JsonObject original, RecipeScriptOptions options, RecipeItemCallbacks.Context base, HolderLookup.Provider registries) {
    ServerCallbackRegistry.requireServer();
    Objects.requireNonNull(original, "original");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(registries, "registries");
    if (RecipeLifecyclePublisher.isDispatching()) {
      return Optional.empty();
    }
    long capturedEpoch = epoch;
    var result = original.deepCopy();
    ResourceLocation activeHandler = null;
    try {
      options.validateRows(result.getAsJsonArray("inputs").size(), result.getAsJsonArray("outputs").size());
      // Even input-only checks must be resolvable before output preparation can
      // produce a candidate with a frozen callback generation.
      for (var binding : options.itemCallbacks()) {
        if (binding.checker().isPresent() && checker(binding.checker().orElseThrow()) == null) {
          return Optional.empty();
        }
        for (var id : binding.modifiers()) {
          if (modifier(id) == null && (binding.io() != RecipeModifier.Io.INPUT || inputModifier(id) == null)) {
            return Optional.empty();
          }
        }
      }
      preparing = true;
      var ops = registries.createSerializationContext(JsonOps.INSTANCE);
      for (var binding : options.itemCallbacks()) {
        if (binding.io() != RecipeModifier.Io.OUTPUT) {
          continue;
        }
        var row = result.getAsJsonArray("outputs").get(binding.row()).getAsJsonObject();
        long amount = row.get("amount").getAsBigDecimal().longValueExact();
        var context = base.withRow(RecipeModifier.Io.OUTPUT, binding.row(), amount);
        var item = ItemStack.SINGLE_ITEM_CODEC.parse(ops, row.get("stack")).getOrThrow();
        for (var id : binding.modifiers()) {
          activeHandler = id;
          var proposal = Objects.requireNonNull(
              modifier(id).modify(context, ItemStackSnapshots.detached(item, registries)),
              "Item modifier result");
          if (proposal.isEmpty() || proposal.getCount() != 1) {
            throw new IllegalArgumentException(
                "Item modifiers must return one nonempty prototype; the original long amount is preserved");
          }
          item = ItemStackSnapshots.detached(proposal, registries);
        }
        row.add("stack", ItemStack.SINGLE_ITEM_CODEC.encodeStart(ops, item).getOrThrow());
      }
      // A paid run stores prepared values. Functions and row IDs are used only
      // by the unstarted candidate and may never execute again on restore.
      result.remove("item_callbacks");
      MachineRecipeJson.validate(result);
      MachineRecipe.CODEC.codec().parse(ops, result).getOrThrow();

      return capturedEpoch == epoch ? Optional.of(new Prepared(result, capturedEpoch)) : Optional.empty();
    } catch (RuntimeException invalid) {
      report(activeHandler, base, invalid);

      return Optional.empty();
    } finally {
      preparing = false;
    }
  }

  public enum InputCheck {
    ALLOWED,
    REJECTED,
    STALE,
    ERROR
  }

  public static InputCheck checkInputs(RecipeScriptOptions options, RecipeItemCallbacks.Context base, List<InputItem> inputs, HolderLookup.Provider registries, long expectedEpoch) {
    ServerCallbackRegistry.requireServer();
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(registries, "registries");
    if (expectedEpoch != epoch || RecipeLifecyclePublisher.isDispatching()) {
      return InputCheck.STALE;
    }
    ResourceLocation activeHandler = null;
    try {
      preparing = true;
      for (var binding : options.itemCallbacks()) {
        if (binding.io() != RecipeModifier.Io.INPUT || binding.checker().isEmpty()) {
          continue;
        }
        activeHandler = binding.checker().orElseThrow();
        var handler = checker(activeHandler);
        if (handler == null) {
          return InputCheck.ERROR;
        }
        boolean selected = false;
        for (var slice : inputs) {
          if (slice.row() != binding.row()) {
            continue;
          }
          selected = true;
          var context = base.withRow(RecipeModifier.Io.INPUT, binding.row(), slice.amount());
          if (!handler.check(context, ItemStackSnapshots.detached(slice.item(), registries))) {
            return epoch == expectedEpoch ? InputCheck.REJECTED : InputCheck.STALE;
          }
        }
        if (!selected) {
          return InputCheck.STALE;
        }
      }

      return epoch == expectedEpoch ? InputCheck.ALLOWED : InputCheck.STALE;
    } catch (RuntimeException invalid) {
      report(activeHandler, base, invalid);

      return epoch == expectedEpoch ? InputCheck.ERROR : InputCheck.STALE;
    } finally {
      preparing = false;
    }
  }

  private static RecipeItemCallbacks.Checker checker(ResourceLocation id) {
    var host = CHECKERS.find(id);

    return host == null ? scriptCheckers.get(id) : host;
  }

  private static RecipeItemCallbacks.Modifier modifier(ResourceLocation id) {
    var host = MODIFIERS.find(id);

    return host == null ? scriptModifiers.get(id) : host;
  }

  private static RecipeItemCallbacks.InputModifier inputModifier(ResourceLocation id) {
    var host = INPUT_MODIFIERS.find(id);
    return host == null ? scriptInputModifiers.get(id) : host;
  }

  private static void report(ResourceLocation id, RecipeItemCallbacks.Context context, RuntimeException invalid) {
    String key = id == null ? "declaration" : id.toString();
    if (reported.add(key)) {
      ModularMachine.LOGGER.warn(
          "Recipe item callback {} rejected preparation for {} at {}",
          key,
          context.recipe(),
          context.controllerPosition(),
          invalid);
    }
  }

  public static void clear() {
    CHECKERS.clear();
    MODIFIERS.clear();
    INPUT_MODIFIERS.clear();
    scriptCheckers = Map.of();
    scriptModifiers = Map.of();
    scriptInputModifiers = Map.of();
    reported.clear();
    scriptGeneration = -1;
    epoch = 0;
    preparing = false;
  }
}
