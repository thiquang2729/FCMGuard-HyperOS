package com.reed.fcmguard.backend;

import com.reed.fcmguard.model.*;

public interface ProtectionBackend {
  String name();

  boolean isAvailable();

  ProtectionState inspect(String packageName);

  RepairResult repair(String packageName, RepairAction action);
}
