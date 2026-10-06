package com.reed.fcmguard.backend;

import java.util.regex.*;

/** Strict parser: absent/default/foreground/ambiguous output is never permission to mutate. */
public final class AppOpsOutput {
  private AppOpsOutput() {}

  public static String mode(String output, String op) {
    if (output == null || !output.startsWith("0\n")) return "unknown";
    Matcher matcher =
        Pattern.compile(
                "(?m)^\\s*"
                    + Pattern.quote(op)
                    + ":\\s*(allow|ignore|deny|default|foreground)(?:[;\\s]|$)")
            .matcher(output.substring(2));
    String found = null;
    while (matcher.find()) {
      String next = matcher.group(1);
      if (found != null && !found.equals(next)) return "unknown";
      found = next;
    }
    // A UID override affects other packages too. Avoid repairing or claiming per-package success.
    if (output.contains("Uid mode:")) return "unknown";
    return found == null ? "unknown" : found;
  }

  public static boolean restricted(String mode) {
    return "ignore".equals(mode) || "deny".equals(mode);
  }
}
