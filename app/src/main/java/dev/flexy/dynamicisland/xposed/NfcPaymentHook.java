package dev.flexy.dynamicisland.xposed;

import android.content.Context;
import android.content.Intent;

final class NfcPaymentHook {
    static final String PACKAGE = "com.android.nfc";
    private static final String HOST = "com.android.nfc.cardemulation.HostEmulationManager";
    private static final String ACTION = "dev.flexy.dynamicisland.NFC_PAYMENT";

    private NfcPaymentHook() {
    }

    static void install(ClassLoader cl) {
        int n = Refl.hookAll(cl, HOST, "onHostEmulationActivated", SafeHook.after("nfc pay start", p -> send("start")));
        n += Refl.hookAll(cl, HOST, "onHostEmulationDeactivated", SafeHook.after("nfc pay end", p -> send("end")));
        XLog.i("nfc payment hooks: " + n);
    }

    private static void send(String state) {
        try {
            Context ctx = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (ctx == null) return;
            XLog.i("nfc payment " + state);
            ctx.sendBroadcast(new Intent(ACTION).setPackage("com.android.systemui").putExtra("state", state));
        } catch (Throwable t) {
            XLog.e("nfc payment send", t);
        }
    }
}
