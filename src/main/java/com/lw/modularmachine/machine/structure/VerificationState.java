package com.lw.modularmachine.machine.structure;

/** Production permission, independent from recipe progress and UI state. */
public enum VerificationState {
  CONFIRMED,
  PENDING,
  INVALID
}
