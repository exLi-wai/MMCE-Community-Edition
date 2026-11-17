package com.lw.modularmachine.api.controller;

import java.util.Optional;

/**
 * Budgeted main-thread capture. A stale/closed capture releases its world
 * references.
 */
public interface ControllerPatternQuery extends AutoCloseable {
  enum Status {
    PENDING,
    COMPLETE,
    STALE,
    CLOSED
  }

  /** Returns actual work used, at most the nonnegative supplied budget. */
  int advance(int budget);

  Status status();

  Optional<ControllerPatternSnapshot> result();

  @Override
  void close();
}
