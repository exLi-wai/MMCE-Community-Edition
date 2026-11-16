package com.lw.modularmachine.api.recipe.resource;

/**
 * A prepared participant in one resource/run commit decision. Resolution must
 * not invoke external callbacks; change notifications follow resolution of
 * every participant. Implementations and acquisition remain owned by the
 * resource domain, and this contract grants no script access to storage or
 * transactions.
 */
@FunctionalInterface
public interface RecipeResourceParticipant {
  void resolve(boolean commit);

  default void publish() {
  }
}
