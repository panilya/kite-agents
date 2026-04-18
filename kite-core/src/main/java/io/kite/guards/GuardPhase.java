package io.kite.guards;

/**
 * Which phase of a run a {@link GuardOutcome} came from. {@link Guard#phase()} returns the
 * corresponding value for each {@link InputGuard} / {@link OutputGuard} record.
 */
public enum GuardPhase { INPUT, OUTPUT }
