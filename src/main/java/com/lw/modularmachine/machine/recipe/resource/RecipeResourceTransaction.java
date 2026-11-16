package com.lw.modularmachine.machine.recipe.resource;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceParticipant;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal coordination only: participants retain their own prepared writes and
 * rollback state. One decision reaches every acquired participant before any
 * caller publishes notifications, including when an earlier resolution fails.
 */
@ApiStatus.Internal
public final class RecipeResourceTransaction {
  private final List<Phase> phases = new ArrayList<>();
  private boolean resolved;

  @FunctionalInterface
  public interface Resolver<T> {
    void resolve(T participant, boolean commit);
  }

  /**
   * Configures resolution order independently of each resource's acquisition
   * order.
   */
  public Phase phase() {
    if (resolved) {
      throw new IllegalStateException("Resolved resource transaction");
    }
    var phase = new Phase();
    phases.add(phase);

    return phase;
  }

  public final class Phase {
    private final List<RecipeResourceParticipant> participants = new ArrayList<>();

    private Phase() {
    }

    /** Enlist a prepared batch before it acquires any of its nested resources. */
    public <T extends RecipeResourceParticipant> T enlist(T participant) {
      if (resolved) {
        throw new IllegalStateException("Resolved resource transaction");
      }
      if (participant != null) {
        participants.add(participant);
      }

      return participant;
    }

    /** Enlist immediately after acquisition, retaining the caller's narrow type. */
    public <T> T enlist(T participant, Resolver<T> resolver) {
      Objects.requireNonNull(participant, "participant");
      Objects.requireNonNull(resolver, "resolver");
      enlist((RecipeResourceParticipant) commit -> resolver.resolve(participant, commit));

      return participant;
    }

    /**
     * Tracks an acquisition API that appends each lease to its caller's list before
     * continuing, so a partially acquired group still rolls back.
     */
    public <T> void track(Collection<T> participants, Resolver<T> resolver) {
      Objects.requireNonNull(participants, "participants");
      Objects.requireNonNull(resolver, "resolver");
      enlist((RecipeResourceParticipant) commit -> resolveAll(commit, participants, resolver));
    }
  }

  public void resolve(boolean commit) {
    if (resolved) {
      throw new IllegalStateException("Resolved resource transaction");
    }
    resolved = true;
    RuntimeException failed = null;
    for (var phase : phases) {
      for (var participant : phase.participants) {
        try {
          participant.resolve(commit);
        } catch (RuntimeException failure) {
          failed = accumulate(failed, failure);
        }
      }
    }
    if (failed != null) {
      throw failed;
    }
  }

  public static <T> void resolveAll(boolean commit, Collection<T> participants, Resolver<T> resolver) {
    RuntimeException failed = null;
    for (var participant : participants) {
      try {
        resolver.resolve(participant, commit);
      } catch (RuntimeException failure) {
        failed = accumulate(failed, failure);
      }
    }
    if (failed != null) {
      throw failed;
    }
  }

  /**
   * Reports failures without skipping notifications for later committed ports.
   */
  public static <T> boolean publishAll(Collection<T> participants, Consumer<T> publisher, Consumer<RuntimeException> onFailure) {
    boolean warning = false;
    for (var participant : participants) {
      try {
        publisher.accept(participant);
      } catch (RuntimeException failure) {
        warning = true;
        onFailure.accept(failure);
      }
    }

    return warning;
  }

  private static RuntimeException accumulate(RuntimeException previous, RuntimeException failure) {
    if (previous == null) {
      return failure;
    }
    if (previous != failure) {
      previous.addSuppressed(failure);
    }

    return previous;
  }
}
