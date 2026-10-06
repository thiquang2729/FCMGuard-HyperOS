package com.reed.fcmguard.backend;
interface IBackgroundPolicy {
    String appOp(String packageName, String op, boolean repair, int userId) = 1;
    void destroy() = 16777114;
}
