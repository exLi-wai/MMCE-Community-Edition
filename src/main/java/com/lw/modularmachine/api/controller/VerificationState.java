package com.lw.modularmachine.api.controller;

/** Production permission, independent from recipe progress and UI state. */
public enum VerificationState {
  CONFIRMED,
  PENDING,
  INVALID
}
