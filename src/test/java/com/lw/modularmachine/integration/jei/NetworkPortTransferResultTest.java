package com.lw.modularmachine.integration.jei;

import com.lw.modularmachine.network.NetworkPortPayloads.RecipeResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkPortTransferResultTest {
  @Test
  void eachServerReplyCompletesOnlyTheMatchingSessionAndRequestOnce() {
    var pending = new NetworkPortRecipeTransfer.Pending();
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    var oldResults = new ArrayList<RecipeTransferResult>();
    var results = new ArrayList<RecipeTransferResult>();
    pending.track(first, 1, oldResults::add);
    pending.track(second, 1, results::add);
    pending.track(second, 2, results::add);

    pending.receive(new RecipeResult(second, 2, false));
    pending.receive(new RecipeResult(second, 2, true));
    pending.receive(new RecipeResult(second, 1, true));
    pending.receive(new RecipeResult(first, 2, true));
    pending.receive(new RecipeResult(first, 1, true));

    assertEquals(List.of(RecipeTransferResult.REJECTED, RecipeTransferResult.SUCCESS), results);
    assertEquals(List.of(RecipeTransferResult.SUCCESS), oldResults);
  }

  @Test
  void unloadingRejectsEveryOutstandingTransferBeforeLateRepliesCanCompleteIt() {
    var pending = new NetworkPortRecipeTransfer.Pending();
    var session = UUID.randomUUID();
    var first = new ArrayList<RecipeTransferResult>();
    var second = new ArrayList<RecipeTransferResult>();
    var completed = new ArrayList<RecipeTransferResult>();
    pending.track(session, 1, result -> {
      first.add(result);
      pending.receive(new RecipeResult(session, 2, true));
    });
    pending.track(session, 2, result -> {
      second.add(result);
      pending.receive(new RecipeResult(session, 1, true));
    });
    pending.track(session, 3, completed::add);
    pending.receive(new RecipeResult(session, 3, true));

    pending.rejectAll();
    pending.receive(new RecipeResult(session, 1, true));
    pending.receive(new RecipeResult(session, 2, false));
    pending.rejectAll();

    assertEquals(List.of(RecipeTransferResult.REJECTED), first);
    assertEquals(List.of(RecipeTransferResult.REJECTED), second);
    assertEquals(List.of(RecipeTransferResult.SUCCESS), completed);
  }
}
