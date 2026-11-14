package com.cdeer.shieldx.error;

/** A single event or batch field failed contract validation. */
public class ValidationException extends IllegalArgumentException {

  private final int eventIndex;
  private final String field;

  public ValidationException(int eventIndex, String field, String message) {
    super(format(eventIndex, field, message));
    this.eventIndex = eventIndex;
    this.field = field;
  }

  public int eventIndex() {
    return eventIndex;
  }

  public String field() {
    return field;
  }

  private static String format(int eventIndex, String field, String message) {
    if (eventIndex >= 0) {
      return "event #" + eventIndex + " field " + field + ": " + message;
    }
    return "field " + field + ": " + message;
  }
}
