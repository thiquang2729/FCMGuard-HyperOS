package com.reed.fcmguard.guard;

import com.reed.fcmguard.backend.*;
import com.reed.fcmguard.model.ProtectionState;

/**
 * SettingsGuard remains the underlying writer; the service still owns its exact ContentObserver.
 */
public final class GmsGuard {
  private final ProtectionBackend backend;

  public GmsGuard(ProtectionBackend backend) {
    this.backend = backend;
  }

  public ProtectionState inspect() {
    return backend.inspect(StandardProtectionBackend.GMS);
  }
}
