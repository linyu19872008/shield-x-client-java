package com.cdeer.shieldx.template;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference implementation of the shield-x block-template rendering rules.
 *
 * <p>This module is optional: a gateway may implement the same rules itself, but must pass the 14
 * conformance vectors before declaring {@code block_templates=true}.
 */
public final class TemplateRenderer {

  public static final int MAX_TEMPLATE_BYTES = 65_536;
  public static final String TEMPLATE_TOO_LARGE = "TEMPLATE_TOO_LARGE";

  private static final Set<String> ALLOWED =
      Set.of("request_id", "timestamp", "client_ip", "host", "path", "site_name", "contact_email");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*\\}\\}");

  private TemplateRenderer() {}

  public static String render(String template, Map<String, String> vars)
      throws TemplateRenderException {
    Objects.requireNonNull(template, "template");
    Map<String, String> values = vars == null ? Map.of() : vars;
    String body = template.startsWith("\uFEFF") ? template.substring(1) : template;
    if (body.getBytes(StandardCharsets.UTF_8).length > MAX_TEMPLATE_BYTES) {
      throw new TemplateRenderException(
          TEMPLATE_TOO_LARGE, "template exceeds " + MAX_TEMPLATE_BYTES + " UTF-8 bytes");
    }

    Matcher matcher = PLACEHOLDER.matcher(body);
    StringBuilder out = new StringBuilder(body.length());
    while (matcher.find()) {
      String name = matcher.group(1);
      if (!ALLOWED.contains(name)) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
        continue;
      }
      String value = values.get(name);
      String escaped = escapeHtml(value == null ? "" : value);
      matcher.appendReplacement(out, Matcher.quoteReplacement(escaped));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  static String escapeHtml(String value) {
    StringBuilder out = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(ch);
      }
    }
    return out.toString();
  }
}
