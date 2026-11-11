package app.morphe.extension.maps.patches;

import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wakes MicroG-RE when Maps starts and keeps an important binding to it while Maps is alive.
 * Everything runs off the main thread except the binder callbacks, which only schedule work.
 */
@SuppressWarnings("unused")
public final class GmsCoreKeepAlive {
    private static final String TAG = "MorpheGmsKeepAlive";
    private static final String PROVIDER_PACKAGE = "app.revanced.android.gms";
    private static final String GSERVICES_AUTHORITY = "app.revanced.android.gsf.gservices";
    private static final String[] BIND_ACTIONS = {
            "com.google.android.location.internal.GoogleLocationManagerService.START",
            "app.revanced.android.location.internal.GoogleLocationManagerService.START",
    };
    private static final long MAX_BACKOFF_MS = 30_000;

    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile Context appContext;
    private static volatile Handler worker;
    private static volatile boolean bound;
    private static int failures;

    private GmsCoreKeepAlive() {
    }

    /** Injection point: GoogleMapsApplication.onCreate. */
    public static void onApplicationCreate(Context context) {
        if (!started.compareAndSet(false, true)) return;
        try {
            Context app = context.getApplicationContext();
            appContext = app != null ? app : context;
            HandlerThread thread = new HandlerThread(TAG);
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(GmsCoreKeepAlive::wakeAndBind);
        } catch (Throwable error) {
            Log.w(TAG, "not started", error);
        }
    }

    private static void wakeAndBind() {
        wake();
        if (!bound) bind();
    }

    /** Acquiring the provider forces the MicroG-RE process to start if the system allows it. */
    private static void wake() {
        long start = System.currentTimeMillis();
        try (ContentProviderClient client = appContext.getContentResolver()
                .acquireUnstableContentProviderClient(GSERVICES_AUTHORITY)) {
            Log.i(TAG, (client != null ? "provider up" : "provider unavailable")
                    + " in " + (System.currentTimeMillis() - start) + "ms");
        } catch (Throwable error) {
            Log.w(TAG, "wake failed", error);
        }
    }

    private static void bind() {
        Intent intent = resolveBindIntent();
        if (intent == null) {
            Log.w(TAG, "no bindable MicroG-RE service found");
            return;
        }
        try {
            bound = appContext.bindService(intent, CONNECTION,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            Log.i(TAG, "bind " + (bound ? "requested" : "refused") + ": " + intent.getAction());
            if (!bound) scheduleRetry();
        } catch (Throwable error) {
            Log.w(TAG, "bind failed", error);
            scheduleRetry();
        }
    }

    private static Intent resolveBindIntent() {
        PackageManager packages = appContext.getPackageManager();
        for (String action : BIND_ACTIONS) {
            Intent intent = new Intent(action).setPackage(PROVIDER_PACKAGE);
            ResolveInfo info = packages.resolveService(intent, 0);
            if (info != null && info.serviceInfo != null && info.serviceInfo.exported
                    && PROVIDER_PACKAGE.equals(info.serviceInfo.packageName)) {
                return intent;
            }
        }
        return null;
    }

    private static void scheduleRetry() {
        Handler handler = worker;
        if (handler == null) return;
        long delay = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(failures++, 5));
        handler.postDelayed(GmsCoreKeepAlive::wakeAndBind, delay);
    }

    private static void unbindQuietly() {
        try {
            appContext.unbindService(CONNECTION);
        } catch (Throwable ignored) {
        }
        bound = false;
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            failures = 0;
            Log.i(TAG, "connected: " + name.flattenToShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Process died; the binding stays and the system restarts it. Nudge it anyway.
            Log.w(TAG, "disconnected: " + name.flattenToShortString());
            Handler handler = worker;
            if (handler != null) handler.postDelayed(GmsCoreKeepAlive::wake, 1000);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Typically a force-stop by the head unit's app killer: the binding is gone for good.
            Log.w(TAG, "binding died: " + name.flattenToShortString());
            unbindQuietly();
            scheduleRetry();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "null binding: " + name.flattenToShortString());
            unbindQuietly();
        }
    };
}
