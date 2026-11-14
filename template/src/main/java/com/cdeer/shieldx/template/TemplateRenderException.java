package com.cdeer.shieldx.template;

/** Template cannot be rendered; {@link #code()} is stable for gateway integration. */
public final class TemplateRenderException extends Exception {

  private final String code;

  public TemplateRenderException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
