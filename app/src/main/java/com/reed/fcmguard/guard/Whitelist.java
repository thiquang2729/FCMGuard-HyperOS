package com.reed.fcmguard.guard;

import java.util.LinkedHashSet;

/** Pure planner. A present required item is a strict no-write path. */
public final class Whitelist {
  private Whitelist() {}

  public static LinkedHashSet<String> parse(String value) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (value != null)
      for (String item : value.split(",")) {
        String trimmed = item.trim();
        if (!trimmed.isEmpty()) out.add(trimmed);
      }
    return out;
  }

  public static String normalize(String value) {
    return join(parse(value));
  }

  public static String restore(String current, String lastGood, String required) {
    LinkedHashSet<String> items = parse(current);
    if (items.contains(required)) return current;
    if (items.isEmpty()) items.addAll(parse(lastGood));
    // Keep the upstream empty-list compatibility fallback.
    if (items.isEmpty()) {
      items.add("com.tencent.mm");
      items.add("com.android.vending");
    }
    items.add(required);
    return join(items);
  }

  private static String join(LinkedHashSet<String> items) {
    StringBuilder out = new StringBuilder();
    for (String item : items) {
      if (out.length() > 0) out.append(',');
      out.append(item);
    }
    return out.toString();
  }
}
