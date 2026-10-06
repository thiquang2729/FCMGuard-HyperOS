package com.reed.fcmguard;

import android.content.Context;
import android.content.Intent;
import com.reed.fcmguard.model.*;
import com.reed.fcmguard.core.RecoveryGate;

/**
 * Best-effort FCM/MCS reconnect kick without ADB, Shizuku or root.
 * Mirrors the heartbeat intents used by HeartbeatFixerForFCM.
 */
public final class FcmReconnect {
    private static final String ACTION_GTALK_HEARTBEAT =
            "com.google.android.intent.action.GTALK_HEARTBEAT";
    private static final String ACTION_MCS_HEARTBEAT =
            "com.google.android.intent.action.MCS_HEARTBEAT";

    private static final String[] TARGET_PACKAGES = {
            "com.google.android.gms",
            "com.google.android.gsf"
    };

    private FcmReconnect() {}

    /** Compatibility entry point: all old callers share the persisted cooldown. */
    public static boolean kick(Context context) { return controlled(context).changed; }

    public static synchronized RepairResult controlled(Context context) {
        android.content.SharedPreferences prefs=context.getSharedPreferences("transport_v2",0);
        long now=System.currentTimeMillis();
        if(RecoveryGate.stopped(prefs.getInt("failures",0))) return result(RepairResult.Outcome.STOPPED,false,"Reconnect stopped after 3 send failures");
        if(RecoveryGate.cooling(now,prefs.getLong("last",0)))return result(RepairResult.Outcome.COOLDOWN,false,"Reconnect cooldown");
        prefs.edit().putLong("last",now).apply();
        boolean sent=send(context);
        if(!sent)prefs.edit().putInt("failures",prefs.getInt("failures",0)+1).apply();
        else prefs.edit().remove("failures").apply();
        // Broadcast dispatch is not transport verification. Never report SUCCESS/Connected.
        return result(sent?RepairResult.Outcome.UNKNOWN:RepairResult.Outcome.FAILED,sent,
                sent?"Heartbeat dispatched; transport remains unverified":"Heartbeat dispatch failed");
    }
    private static RepairResult result(RepairResult.Outcome outcome,boolean sent,String message){
        return new RepairResult("com.google.android.gms",RepairAction.RECONNECT,"Standard",outcome,sent,"UNKNOWN","UNKNOWN",message);
    }
    private static boolean send(Context context) {
        boolean sent = false;
        for (String target : TARGET_PACKAGES) {
            try {
                context.sendBroadcast(new Intent(ACTION_GTALK_HEARTBEAT).setPackage(target));
                context.sendBroadcast(new Intent(ACTION_MCS_HEARTBEAT).setPackage(target));
                sent = true;
            } catch (Throwable ignored) {
            }
        }
        return sent;
    }
}
