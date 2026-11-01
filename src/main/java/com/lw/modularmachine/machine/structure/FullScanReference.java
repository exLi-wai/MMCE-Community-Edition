package com.lw.modularmachine.machine.structure;

import java.util.function.IntFunction;

/**
 * Deliberately independent traversal: never reads incremental dirty/mismatch
 * caches.
 */
public final class FullScanReference {
  private FullScanReference() {
  }

  public static Result scan(int count, IntFunction<CheckResult> check) {
    for (int i = 0; i < count; i++) {
      CheckResult result;
      try {
        result = check.apply(i);
      } catch (RuntimeException exception) {
        return new Result(VerificationState.INVALID, VerificationFailure.CHECK_ERROR, i, i + 1);
      }
      if (result == null) {
        return new Result(VerificationState.INVALID, VerificationFailure.CHECK_ERROR, i, i + 1);
      }
      if (result != CheckResult.MATCH) {
        VerificationFailure failure = switch (result) {
          case UNAVAILABLE -> VerificationFailure.CHUNK_UNAVAILABLE;
          case UNSUPPORTED -> VerificationFailure.UNSUPPORTED_SCHEMA;
          default -> VerificationFailure.MISMATCH;
        };
        return new Result(VerificationState.INVALID, failure, i, i + 1);
      }
    }
    return new Result(VerificationState.CONFIRMED, VerificationFailure.NONE, -1, count);
  }

  public record Result(VerificationState state, VerificationFailure failure, int failedIndex, int checks) {
  }
}
