package com.lw.modularmachine.machine.structure;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.IntFunction;

/**
 * Main-thread coarse-group verifier. No world references, threads or random
 * sampling.
 */
public final class IncrementalVerifier {
  private final int[][] groups;
  private final BitSet dirty;
  private final BitSet mismatched;
  private final int[] failurePositions;
  private VerificationState state = VerificationState.PENDING;
  private VerificationFailure failure = VerificationFailure.UNVERIFIED;
  private long revision = 1;
  private long verifiedRevision = -1;
  private long roundStarted;
  private long checkedPredicates;
  private int activeGroup = -1;
  private int cursor;
  private int failurePosition = -1;

  public IncrementalVerifier(int[][] groups, long now) {
    if (groups.length == 0) {
      throw new IllegalArgumentException("Empty pattern");
    }
    this.groups = Arrays.stream(groups).map(int[]::clone).toArray(int[][]::new);
    BitSet indices = new BitSet();
    for (int[] group : this.groups) {
      if (group.length == 0) {
        throw new IllegalArgumentException("Empty dependency group");
      }
      for (int index : group) {
        if (index < 0 || indices.get(index)) {
          throw new IllegalArgumentException("Duplicate/negative check index");
        }
        indices.set(index);
      }
    }
    dirty = new BitSet(groups.length);
    dirty.set(0, groups.length);
    mismatched = new BitSet(groups.length);
    failurePositions = new int[groups.length];
    Arrays.fill(failurePositions, -1);
    roundStarted = now;
  }

  public void markDirty(int group, long now) {
    if (group < 0 || group >= groups.length) {
      throw new IndexOutOfBoundsException(group);
    }
    beginRound(now);
    revision = Math.incrementExact(revision);
    dirty.set(group);
    if (activeGroup == group) {
      cursor = 0;
    }
  }

  public void markAllDirty(long now) {
    beginRound(now);
    revision = Math.incrementExact(revision);
    dirty.set(0, groups.length);
    activeGroup = -1;
    cursor = 0;
  }

  private void beginRound(long now) {
    if (state != VerificationState.PENDING) {
      roundStarted = now;
    }
    state = VerificationState.PENDING;
    failure = VerificationFailure.UNVERIFIED;
    failurePosition = -1;
  }

  public void unavailable(long now) {
    markAllDirty(now);
    invalidate(VerificationFailure.CHUNK_UNAVAILABLE, -1);
  }

  public int advance(int budget, long now, int timeoutTicks, IntFunction<CheckResult> check) {
    if (state != VerificationState.PENDING || budget <= 0) {
      return 0;
    }
    if (now - roundStarted > timeoutTicks) {
      invalidate(VerificationFailure.VERIFICATION_TIMEOUT, -1);
      return 0;
    }
    int used = 0;
    while (used < budget && !dirty.isEmpty()) {
      if (activeGroup < 0) {
        activeGroup = dirty.nextSetBit(0);
        cursor = 0;
      }
      int index = groups[activeGroup][cursor];
      long attemptRevision = revision;
      used++;
      checkedPredicates++;
      CheckResult result;
      try {
        result = check.apply(index);
      } catch (RuntimeException exception) {
        activeGroup = -1;
        cursor = 0;
        invalidate(VerificationFailure.CHECK_ERROR, index);
        return used;
      }
      if (result == null) {
        activeGroup = -1;
        cursor = 0;
        invalidate(VerificationFailure.CHECK_ERROR, index);
        return used;
      }
      if (revision != attemptRevision) {
        return used;
      }
      if (result == CheckResult.UNSUPPORTED) {
        activeGroup = -1;
        cursor = 0;
        invalidate(VerificationFailure.UNSUPPORTED_SCHEMA, index);
        return used;
      }
      if (result == CheckResult.UNAVAILABLE) {
        activeGroup = -1;
        cursor = 0;
        invalidate(VerificationFailure.CHUNK_UNAVAILABLE, index);
        return used;
      }
      if (result == CheckResult.MISMATCH) {
        mismatched.set(activeGroup);
        failurePositions[activeGroup] = index;
        dirty.clear(activeGroup);
        activeGroup = -1;
        cursor = 0;
        invalidate(VerificationFailure.MISMATCH, index);
        return used;
      }
      if (++cursor == groups[activeGroup].length) {
        dirty.clear(activeGroup);
        mismatched.clear(activeGroup);
        failurePositions[activeGroup] = -1;
        activeGroup = -1;
        cursor = 0;
      }
    }
    if (dirty.isEmpty()) {
      if (mismatched.isEmpty()) {
        state = VerificationState.CONFIRMED;
        failure = VerificationFailure.NONE;
        failurePosition = -1;
        verifiedRevision = revision;
      } else {
        invalidate(VerificationFailure.MISMATCH, failurePositions[mismatched.nextSetBit(0)]);
      }
    }
    return used;
  }

  private void invalidate(VerificationFailure reason, int position) {
    state = VerificationState.INVALID;
    failure = reason;
    failurePosition = position;
  }

  public void rejectProof(VerificationFailure reason, int position, long now) {
    if (reason == VerificationFailure.NONE || reason == VerificationFailure.UNVERIFIED) {
      throw new IllegalArgumentException("Not a failure proof");
    }
    markAllDirty(now);
    invalidate(reason, position);
  }

  public boolean canCommit() {
    return state == VerificationState.CONFIRMED && verifiedRevision == revision;
  }

  public VerificationState state() {
    return state;
  }

  public VerificationFailure failure() {
    return failure;
  }

  public int failurePosition() {
    return failurePosition;
  }

  public long revision() {
    return revision;
  }

  public long checkedPredicates() {
    return checkedPredicates;
  }

  public int groupCount() {
    return groups.length;
  }
}
