package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

final class RecipeInputPlanning implements RecipeResourcePlanning {
  private final String recipeId;
  private final MachineRecipe definition;
  private final List<RecipeSearchResource.Captured> resources;
  private final List<RecipeSearchResource.Replenishment> requests = new ArrayList<>();
  private final List<VerifiedInput> verified = new ArrayList<>();
  private Status status = Status.PENDING;
  private int cursor;
  private int requestCursor;
  private RecipeResourcePlanning plan;

  private record VerifiedInput(RecipeSearchResource.Captured resource, RecipeResourcePlanning plan) {
  }

  RecipeInputPlanning(String recipeId, MachineRecipe definition, List<RecipeSearchResource.Captured> resources) {
    this.recipeId = recipeId;
    this.definition = definition;
    this.resources = resources;
  }

  @Override
  public Status status() {
    return status;
  }

  @Override
  public Progress advance(int budget) {
    int used = 0;
    while (status == Status.PENDING && cursor < resources.size() && used < budget) {
      var resource = resources.get(cursor);
      if (plan == null) {
        plan = resource.planInput(recipeId, definition);
      }
      var result = plan.advance(budget - used);
      used += result.workUsed();
      if (result.status() == Status.PENDING) {
        break;
      }
      if (result.status() == Status.REJECTED) {
        status = Status.REJECTED;
        break;
      }
      if (plan instanceof RecipeSearchResource.Replenishment replenishment && replenishment.waiting()) {
        requests.add(replenishment);
      }
      verified.add(new VerifiedInput(resource, plan));
      cursor++;
      plan = null;
    }
    if (cursor == resources.size()) {
      status = Status.READY;
    }
    return new Progress(status, used);
  }

  boolean waiting() {
    return status == Status.READY && !requests.isEmpty();
  }

  boolean current() {
    return verified.stream().allMatch(
        input -> input.plan() instanceof RecipeSearchResource.Recheckable
            || input.resource().inputCurrent(recipeId, definition, input.plan()));
  }

  void addRechecks(List<Supplier<RecipeResourcePlanning>> steps, RecipeSearchResource.ReplenishmentProof proof) {
    for (var input : verified) {
      if (input.plan() instanceof RecipeSearchResource.Recheckable recheckable) {
        steps.add(() -> recheckable.recheck(proof));
      }
    }
  }

  boolean requested() {
    return requests.stream().anyMatch(RecipeSearchResource.Replenishment::requested);
  }

  Progress advanceRequests(int budget, BooleanSupplier current) {
    int used = 0;
    while (requestCursor < requests.size() && used < budget) {
      var result = requests.get(requestCursor).advanceRequests(budget - used, current);
      used += result.workUsed();
      if (result.status() == Status.REJECTED) {
        return new Progress(Status.REJECTED, used);
      }
      if (result.status() == Status.PENDING) {
        break;
      }
      requestCursor++;
    }
    return new Progress(requestCursor == requests.size() ? Status.READY : Status.PENDING, used);
  }
}
