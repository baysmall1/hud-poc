package dev.codex.voyahhud;

import android.content.Context;
import android.os.Handler;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import com.iauto.val.ValManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

final class ValSpeedProvider {
    interface Callback {
        void onSpeedChanged(int speed);
    }

    private static final String TAG = "V21H53Bridge";
    private static final String GET_SPEED_URI =
            "val://Vehicle/static/Driving/v1/s0/getDrvInfoSpeedInfo";
    private static final String NOTIFY_SPEED_URI =
            "val://Vehicle/static/Driving/v1/s0/notifyDrvInfoSpeedInfo";
    private static final String LICENSE_ASSET = "voyah_h53direct.license";
    private static final long POLL_MS = 500L;

    private final Context context;
    private final Handler worker;
    private final Callback callback;
    private final ValManager.ValListener valListener = new ValManager.ValListener() {
        @Override public void onServiceConnected() {
            runOnWorker(() -> {
                connected = true;
                log("VAL speed service connected");
                afterConnected();
            });
        }

        @Override public void onServiceDisconnected() {
            runOnWorker(() -> {
                connected = false;
                listenerRegistered = false;
                log("VAL speed service disconnected");
            });
        }

        @Override public void onNotify(String uri, Parcel out) {
            if (!NOTIFY_SPEED_URI.equals(uri)) return;
            Float speed = parseNotifySpeed(out);
            runOnWorker(() -> applySpeed(speed, "notify"));
        }
    };

    private boolean started;
    private boolean sdkInitialized;
    private boolean initResult;
    private boolean connected;
    private boolean listenerRegistered;
    private int lastSpeed = -1;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!started) return;
            pollSpeed();
            if (started) worker.postDelayed(this, POLL_MS);
        }
    };

    ValSpeedProvider(Context context, Handler worker, Callback callback) {
        this.context = context.getApplicationContext();
        this.worker = worker;
        this.callback = callback;
    }

    void start() {
        if (started) return;
        started = true;
        worker.post(() -> {
            initSdk();
            registerListener();
            bindService();
            worker.removeCallbacks(pollRunnable);
            worker.postDelayed(pollRunnable, 800L);
        });
    }

    void recover() {
        if (!started) return;
        worker.post(() -> {
            if (!connected) bindService();
            initSdk();
            registerListener();
            pollSpeed();
        });
    }

    void stop() {
        started = false;
        worker.removeCallbacks(pollRunnable);
        if (listenerRegistered) {
            try {
                ValManager.getInstance().unRegisterListener(valListener);
            } catch (Throwable ignored) {
            } finally {
                listenerRegistered = false;
            }
        }
    }

    int latestSpeed() {
        return lastSpeed;
    }

    private void afterConnected() {
        initSdk();
        registerListener();
        pollSpeed();
    }

    private void initSdk() {
        if (sdkInitialized && initResult) return;
        try {
            String license = readAssetText(LICENSE_ASSET);
            if (license.isEmpty()) {
                log("VAL speed init skipped: license asset empty");
                return;
            }
            initResult = ValManager.getInstance().initSdk(license);
            sdkInitialized = true;
            log("VAL speed init result=" + initResult + " licenseLength=" + license.length());
        } catch (Throwable error) {
            log("VAL speed init failed: " + shortError(error));
        }
    }

    private void bindService() {
        try {
            ValManager.getInstance().bindService(context);
            log("VAL speed bind requested");
        } catch (Throwable error) {
            log("VAL speed bind failed: " + shortError(error));
        }
    }

    private void registerListener() {
        if (listenerRegistered) return;
        try {
            Set<String> uris = new HashSet<>();
            uris.add(NOTIFY_SPEED_URI);
            ValManager.getInstance().registerListener(valListener, uris);
            listenerRegistered = true;
            log("VAL speed listener registered");
        } catch (Throwable error) {
            log("VAL speed listener failed: " + shortError(error));
        }
    }

    private void pollSpeed() {
        try {
            Parcel in = Parcel.obtain();
            Parcel out = null;
            try {
                out = ValManager.getInstance().invoke(GET_SPEED_URI, in);
                applySpeed(parseInvokeSpeed(out), "poll");
            } finally {
                in.recycle();
                if (out != null) out.recycle();
            }
        } catch (Throwable error) {
            log("VAL speed poll failed: " + shortError(error));
        }
    }

    private Float parseInvokeSpeed(Parcel parcel) {
        if (parcel == null) return null;
        int size = parcel.dataSize();
        int posBefore = parcel.dataPosition();
        try {
            parcel.setDataPosition(0);
            if (size == 4 && initResult) {
                float speed = parcel.readFloat();
                return isPlausibleSpeed(speed) ? speed : null;
            }
            if (parcel.dataAvail() >= 4) {
                parcel.readInt();
                if (parcel.dataAvail() >= 4) {
                    float speed = parcel.readFloat();
                    return isPlausibleSpeed(speed) ? speed : null;
                }
            }
        } catch (Throwable error) {
            log("VAL speed invoke parse failed: " + shortError(error));
        } finally {
            try {
                parcel.setDataPosition(posBefore);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Float parseNotifySpeed(Parcel parcel) {
        if (parcel == null) return null;
        int posBefore = parcel.dataPosition();
        try {
            parcel.setDataPosition(0);
            if (parcel.dataAvail() >= 4) parcel.readInt();
            if (parcel.dataAvail() >= 8) {
                parcel.readInt();
                float speed = parcel.readFloat();
                return isPlausibleSpeed(speed) ? speed : null;
            }
            if (parcel.dataAvail() >= 4) {
                float speed = parcel.readFloat();
                return isPlausibleSpeed(speed) ? speed : null;
            }
        } catch (Throwable error) {
            log("VAL speed notify parse failed: " + shortError(error));
        } finally {
            try {
                parcel.setDataPosition(posBefore);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void applySpeed(Float rawSpeed, String source) {
        if (rawSpeed == null) return;
        int speed = Math.max(0, Math.min(299, Math.round(rawSpeed)));
        if (speed == lastSpeed) return;
        lastSpeed = speed;
        log("VAL speed " + source + "=" + speed);
        callback.onSpeedChanged(speed);
    }

    private boolean isPlausibleSpeed(float speed) {
        return !Float.isNaN(speed) && !Float.isInfinite(speed)
                && speed >= 0.0f && speed <= 300.0f;
    }

    private String readAssetText(String name) throws Exception {
        InputStream input = context.getAssets().open(name);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) break;
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally {
            input.close();
        }
    }

    private void runOnWorker(Runnable task) {
        worker.post(task);
    }

    private void log(String message) {
        Log.i(TAG, message);
    }

    private static String shortError(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.length() == 0) {
            message = root.getClass().getSimpleName();
        }
        if (message.length() > 180) {
            message = message.substring(0, 180) + "...";
        }
        return root.getClass().getSimpleName() + ": " + message;
    }
}
