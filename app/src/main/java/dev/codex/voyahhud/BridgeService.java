package dev.codex.voyahhud;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import com.baidu.navisdk.hudsdk.BNRemoteMessage;
import com.baidu.navisdk.hudsdk.client.BNRemoteVistor;
import com.baidu.navisdk.hudsdk.client.HUDSDkEventCallback;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class BridgeService extends Service {
    private static final String TAG = "V21H53Bridge";
    private static final String BAIDU_PKG = "com.baidu.naviauto";
    private static final String LBS_ACTION = "com.baidu.naviauto.imaplbs.lbsservice";
    private static final String SERVICE_IFACE = "com.baidu.naviauto.imaplbs.IMapAutoAPIService";
    private static final String EVENT_IFACE = "com.baidu.naviauto.imaplbs.IEventListener";
    private static final String MAP_AIDL_ACTION = "com.baidu.naviauto.imaplbs.aidlservice";
    private static final String MAP_AIDL_IFACE = "com.baidu.naviauto.imaplbs.IMapAidlService";
    private static final String OPEN_CONTROL_ACTION =
            "com.baidu.baidumaps.opencontrol.aidl.ACTION.REQUEST";
    private static final String OPEN_CONTROL_FACTORY_IFACE =
            "com.baidu.baidumaps.opencontrol.aidl.IOpenControlBinderFactory";
    private static final String OPEN_CONTROL_SERVICE_IFACE =
            "com.baidu.baidumaps.opencontrol.aidl.IBMapOpenControlService";
    private static final String OPEN_CONTROL_NOTIFY_IFACE =
            "com.baidu.baidumaps.opencontrol.aidl.IBMapNotifyHandler";
    private static final int TRANSACTION_OPEN_CONTROL_FACTORY = 1;
    private static final int TRANSACTION_OPEN_CONTROL_SET_HANDLER = 1;
    private static final int TRANSACTION_OPEN_CONTROL_NOTIFY = 1;
    private static final int OPEN_CONTROL_SCENE_NAVI = 20001;
    private static final int TRANSACTION_DRAW_HUD_MAP = 5;
    private static final int TRANSACTION_DESTROY_HUD_MAP = 7;
    private static final String HUD_SURFACE_TAG = "v21_h53_enlarge_source";
    private static final String H53_PKG = "com.autoai.ar.h53";
    private static final String H53_SERVICE = "com.zinger.hudsdklibrary.Service.HudCommService";
    private static final String H53_POOL_IFACE = "com.zinger.hudsdklibrary.IHudBinderPool";
    private static final String H53_COMM_IFACE = "com.zinger.hudsdklibrary.IHudCommInterface";
    private static final int TRANSACTION_SEND = 1;
    private static final int TRANSACTION_SET_EVENT_LISTENER = 2;
    private static final int TRANSACTION_REMOVE_EVENT_LISTENER = 3;
    private static final int TRANSACTION_EVENT = 1;
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final String AES_IV = "7777889654578967";
    private static final long EVENT_FRESH_MS = 2_000;
    private static final long NAVIGATION_POLL_MS = 500;
    private static final long OPEN_CONTROL_RETRY_MS = 30_000;
    private static final long BAIDU_HUD_SDK_RETRY_MS = 30_000;
    private static final String NOTIFICATION_CHANNEL = "v21_h53_bridge";
    private static final int NOTIFICATION_ID = 5300;
    private static final long MIN_RENDER_INTERVAL_MS = 250;
    private static final String BAIDU_HUD_APP_NAME = "V21-H53-HUD-Bridge";
    private static final String BAIDU_HUD_APP_VERSION = "5.15";
    private static final int EXPAND_MAP_STATE_HIDE = 2;
    private static final String BAIDU_BROADCAST_ACTION = "BAIDUMAP_STANDARD_BROADCAST_SEND";
    private static final int GUIDANCE_INFO_EVENT = 10001;
    private static final int TRAFFIC_LIGHT_EVENT = 20001;
    private static final String BAIDU_NAVI_INDUCE_ACTION =
            "com.baidu.map.auto.NOTIFY.ACTION_NAVI_INDUCUD";
    private static final String EXTRA_NEXT_TURN_DISTANCE = "NEXT_TURN_ICON_DISTANCE";
    private static final String EXTRA_LIMITED_SPEED = "LIMITED_SPEED";
    private static final String EXTRA_ROUTE_REMAIN_DISTANCE = "ROUTE_REMAIN_DIS";
    private static final String EXTRA_ROUTE_REMAIN_TIME = "ROUTE_REMAIN_TIME";

    private final HudState state = new HudState();
    private final V21PayloadParser parser = new V21PayloadParser();
    private Handler mainHandler;
    private HandlerThread workerThread;
    private Handler worker;
    private HudRenderer renderer;
    private ServiceConnection v21Connection;
    private ServiceConnection mapAidlConnection;
    private ServiceConnection openControlConnection;
    private ServiceConnection h53Connection;
    private IBinder v21Binder;
    private IBinder mapAidlBinder;
    private IBinder openControlFactoryBinder;
    private IBinder openControlServiceBinder;
    private SurfaceTexture mapSurfaceTexture;
    private Surface mapSurface;
    private IBinder h53PoolBinder;
    private IBinder h53CommBinder;
    private EventBinder eventBinder;
    private OpenControlNotifyBinder openControlNotifyBinder;
    private String cryptKey;
    private int requestCounter = 1000;
    private int imageSequence;
    private int inactiveSignals;
    private long lastGuideEventAt;
    private long lastNavigationCheckAt;
    private long lastSubscriptionAt;
    private long lastRenderAt;
    private boolean v21Binding;
    private boolean mapAidlBinding;
    private boolean openControlBinding;
    private boolean h53Binding;
    private boolean hudVisible;
    private boolean renderPending;
    private boolean destroyed;
    private boolean listenersSubscribed;
    private boolean authoritativeGuideActive;
    private boolean navigationStatusKnown;
    private boolean navigationStatusActive;
    private boolean baiduHudSdkInitialized;
    private boolean baiduHudSdkOpening;
    private boolean baiduHudSdkConnected;
    private long lastOpenControlBindAttemptAt;
    private String lastRenderKey = "";
    private boolean tbtLogged;
    private SharedPreferences hudPreferences;
    private BroadcastReceiver baiduBroadcastReceiver;
    private BroadcastReceiver resumeReceiver;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (preferences, key) -> {
        if (worker == null) return;
        worker.post(() -> {
            lastRenderKey = "";
            if (!HudSettings.hasAnyContent(this)) hideHud();
            else if (state.navigating) scheduleRender();
        });
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForegroundService();
        mainHandler = new Handler(getMainLooper());
        workerThread = new HandlerThread("v21-h53-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        renderer = new HudRenderer(this);
        HudSettings.migrateFromV46(this);
        hudPreferences = HudSettings.preferences(this);
        hudPreferences.registerOnSharedPreferenceChangeListener(settingsListener);
        registerBaiduBroadcastReceiver();
        registerResumeReceiver();
        startBaiduHudSdk();
        log("service 5.15 created");
        mainHandler.postDelayed(this::startConnections, 1_000);
    }

    private void registerBaiduBroadcastReceiver() {
        baiduBroadcastReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (BAIDU_NAVI_INDUCE_ACTION.equals(action)) {
                    handleOfficialNaviInduce(intent);
                    return;
                }
                int keyType = intent.getIntExtra("KEY_TYPE", 0);
                if (keyType == GUIDANCE_INFO_EVENT) {
                    handleOfficialNaviInduce(intent);
                    return;
                }
                if (keyType != TRAFFIC_LIGHT_EVENT) return;
                final String json = intent.getStringExtra("EXTRA_TRAFFIC_LIGHT_INFO");
                if (json == null || worker == null) return;
                worker.post(() -> {
                    try {
                        logPayloadChunks("V21 broadcast traffic light raw", json);
                        if (parser.applyTrafficLight(json, state)) {
                            log("V21 traffic light accepted");
                            if (state.navigating) scheduleRender();
                        }
                    } catch (Throwable error) {
                        log("V21 traffic light payload rejected: " + summarize(error));
                    }
                });
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BAIDU_BROADCAST_ACTION);
        filter.addAction(BAIDU_NAVI_INDUCE_ACTION);
        registerReceiver(baiduBroadcastReceiver, filter);
    }

    private void registerResumeReceiver() {
        resumeReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || worker == null) return;
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)
                        || Intent.ACTION_USER_PRESENT.equals(action)
                        || Intent.ACTION_POWER_CONNECTED.equals(action)) {
                    log("wake recovery trigger: " + action);
                    mainHandler.post(BridgeService.this::recoverConnections);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        registerReceiver(resumeReceiver, filter);
    }

    private void handleOfficialNaviInduce(Intent intent) {
        if (worker == null) return;
        final int distance = firstIntExtra(intent, -1, EXTRA_NEXT_TURN_DISTANCE);
        final int speedLimit = firstIntExtra(intent, -1, EXTRA_LIMITED_SPEED);
        final int totalDistance = firstIntExtra(intent, -1, EXTRA_ROUTE_REMAIN_DISTANCE);
        final int totalTime = firstIntExtra(intent, -1, EXTRA_ROUTE_REMAIN_TIME);
        worker.post(() -> {
            boolean changed = false;
            if (speedLimit >= 0 && speedLimit != state.speedLimit) {
                state.speedLimit = speedLimit;
                changed = true;
            }
            if (totalDistance >= 0 && totalDistance != state.totalDistance) {
                state.totalDistance = totalDistance;
                changed = true;
            }
            if (totalTime >= 0 && totalTime != state.totalTimeSeconds) {
                state.totalTimeSeconds = totalTime;
                changed = true;
            }
            if (distance >= 0 && distance != state.maneuverDistance) {
                state.maneuverDistance = distance;
                state.maneuverDistanceText = "";
                state.maneuverDistanceUnit = "";
                changed = true;
            }
            if (!state.navigating) {
                log("V21 navigation active");
                state.navigating = true;
                changed = true;
            }
            if (changed) {
                inactiveSignals = 0;
                lastGuideEventAt = System.currentTimeMillis();
                ensureHudOffscreenActive();
                scheduleRender();
            }
        });
    }

    private int firstIntExtra(Intent intent, int fallback, String... keys) {
        Bundle extras = intent.getExtras();
        if (extras == null) return fallback;
        for (String key : keys) {
            Object value = extras.get(key);
            int parsed = asInt(value, Integer.MIN_VALUE);
            if (parsed != Integer.MIN_VALUE) return parsed;
        }
        return fallback;
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return Math.round(((Number) value).floatValue());
        }
        if (value instanceof String) {
            try {
                return Math.round(Float.parseFloat(((String) value).trim()));
            } catch (Throwable ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private void startAsForegroundService() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL, "岚图追光 V21 HUD投射", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持百度地图 V21 与 HUD 实时同步");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                pendingFlags);
        Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("岚图追光&百度地图V21 HUD投射")
                .setContentText("HUD 实时桥接服务运行中")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mainHandler.post(this::startConnections);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startConnections() {
        if (destroyed) return;
        bindH53();
        bindV21();
        bindMapAidl();
        bindOpenControl();
    }

    private void recoverConnections() {
        if (destroyed) return;
        startBaiduHudSdk();
        if (v21Binder != null && !v21Binder.isBinderAlive()) {
            v21Binder = null;
            listenersSubscribed = false;
            navigationStatusKnown = false;
            navigationStatusActive = false;
        }
        if (mapAidlBinder != null && !mapAidlBinder.isBinderAlive()) {
            mapAidlBinder = null;
            releaseHudOffscreenSurface(false);
        }
        if (openControlServiceBinder != null && !openControlServiceBinder.isBinderAlive()) {
            openControlServiceBinder = null;
            openControlFactoryBinder = null;
            lastOpenControlBindAttemptAt = 0;
        }
        if (h53CommBinder != null && !h53CommBinder.isBinderAlive()) {
            h53CommBinder = null;
            h53PoolBinder = null;
            hudVisible = false;
        }
        startConnections();
        if (worker != null) {
            worker.post(() -> {
                if (v21Binder != null) {
                    listenersSubscribed = false;
                    subscribeListeners();
                    sendFunction("is_in_navi", 0);
                    sendFallbackSnapshot();
                }
                if (state.navigating) {
                    ensureHudOffscreenActive();
                    lastRenderKey = "";
                    scheduleRender();
                }
            });
        }
    }

    private void startBaiduHudSdk() {
        if (baiduHudSdkInitialized || destroyed) return;
        try {
            BNRemoteVistor vistor = BNRemoteVistor.getInstance();
            vistor.setShowLog(true);
            vistor.init(getApplicationContext(), BAIDU_HUD_APP_NAME, BAIDU_HUD_APP_VERSION,
                    new HUDSDkEventCallback.OnRGInfoEventCallback() {
                        @Override public void onManeuver(BNRemoteMessage.BNRGManeuver value) {
                            handleBaiduHudManeuver(value);
                        }

                        @Override public void onRemainInfo(BNRemoteMessage.BNRGRemainInfo value) {
                            handleBaiduHudRemainInfo(value);
                        }

                        @Override public void onCurrentRoad(BNRemoteMessage.BNRGCurrentRoad value) {
                            if (value == null) return;
                            updateBaiduHudRoad(value.getCurrentRoadName(), true);
                        }

                        @Override public void onNextRoad(BNRemoteMessage.BNRGNextRoad value) {
                            if (value == null) return;
                            updateBaiduHudRoad(value.getNextRoadName(), false);
                        }

                        @Override public void onNaviStart(BNRemoteMessage.BNRGNaviStart value) {
                            worker.post(() -> {
                                markBaiduHudNavigationActive();
                                scheduleRender();
                            });
                        }

                        @Override public void onNaviEnd(BNRemoteMessage.BNRGNaviEnd value) {
                            worker.post(BridgeService.this::stopNavigation);
                        }

                        @Override public void onCruiseEnd(BNRemoteMessage.BNRGCruiseEnd value) {
                            worker.post(BridgeService.this::stopNavigation);
                        }

                        @Override public void onEnlargeRoad(BNRemoteMessage.BNEnlargeRoad value) {
                            handleBaiduHudEnlargeRoad(value);
                        }

                        @Override public void onCarInfo(BNRemoteMessage.BNRGCarInfo value) {
                            handleBaiduHudCarInfo(value);
                        }

                        @Override public void onServiceArea(BNRemoteMessage.BNRGServiceArea value) { }
                        @Override public void onAssistant(BNRemoteMessage.BNRGAssistant value) { }
                        @Override public void onGPSLost(BNRemoteMessage.BNRGGPSLost value) { }
                        @Override public void onGPSNormal(BNRemoteMessage.BNRGGPSNormal value) { }
                        @Override public void onCruiseStart(BNRemoteMessage.BNRGCruiseStart value) { }
                        @Override public void onRoutePlanYawing(BNRemoteMessage.BNRGRPYawing value) { }
                        @Override public void onRoutePlanYawComplete(BNRemoteMessage.BNRGRPYawComplete value) { }
                        @Override public void onCarFreeStatus(BNRemoteMessage.BNRGCarFreeStatus value) { }
                        @Override public void onCarTunelInfo(BNRemoteMessage.BNRGCarTunelInfo value) { }
                        @Override public void onDestInfo(BNRemoteMessage.BNRGDestInfo value) {
                            handleBaiduHudDestInfo(value);
                        }
                        @Override public void onRouteInfo(BNRemoteMessage.BNRGRouteInfo value) { }
                        @Override public void onCurShapeIndexUpdate(BNRemoteMessage.BNRGCurShapeIndexUpdate value) { }
                        @Override public void onNearByCamera(BNRemoteMessage.BNRGNearByCameraInfo value) { }
                    },
                    new HUDSDkEventCallback.OnConnectCallback() {
                        @Override public void onConnected() {
                            baiduHudSdkOpening = false;
                            baiduHudSdkConnected = true;
                            log("Baidu HUD SDK connected");
                        }

                        @Override public void onReConnected() {
                            baiduHudSdkOpening = false;
                            baiduHudSdkConnected = true;
                            log("Baidu HUD SDK reconnected");
                        }

                        @Override public void onClose(int code, String reason) {
                            baiduHudSdkOpening = false;
                            baiduHudSdkConnected = false;
                            log("Baidu HUD SDK closed code=" + code + " reason=" + summarizeText(reason));
                        }

                        @Override public void onAuth(BNRemoteMessage.BNRGAuthSuccess value) {
                            log("Baidu HUD SDK auth success");
                        }

                        @Override public void onStartLBSAuth() {
                            log("Baidu HUD SDK LBS auth started");
                        }

                        @Override public void onEndLBSAuth(int result, String reason) {
                            log("Baidu HUD SDK LBS auth result=" + result
                                    + " reason=" + summarizeText(reason));
                            if (result == 0) openBaiduHudSdk();
                            else mainHandler.postDelayed(BridgeService.this::openBaiduHudSdk,
                                    BAIDU_HUD_SDK_RETRY_MS);
                        }
                    });
            baiduHudSdkInitialized = true;
            log("Baidu HUD SDK initialized");
        } catch (Throwable error) {
            log("Baidu HUD SDK init failed: " + summarize(error));
        }
    }

    private void openBaiduHudSdk() {
        if (baiduHudSdkOpening || baiduHudSdkConnected || destroyed) return;
        try {
            baiduHudSdkOpening = true;
            BNRemoteVistor.getInstance().open();
            log("Baidu HUD SDK open requested");
        } catch (Throwable error) {
            baiduHudSdkOpening = false;
            log("Baidu HUD SDK open failed: " + summarize(error));
        }
    }

    private void markBaiduHudNavigationActive() {
        inactiveSignals = 0;
        lastGuideEventAt = System.currentTimeMillis();
        if (!state.navigating) log("Baidu HUD SDK navigation active");
        state.navigating = true;
        ensureHudOffscreenActive();
    }

    private void handleBaiduHudCarInfo(BNRemoteMessage.BNRGCarInfo value) {
        if (value == null || worker == null) return;
        worker.post(() -> {
            markBaiduHudNavigationActive();
        });
    }

    private void handleBaiduHudRemainInfo(BNRemoteMessage.BNRGRemainInfo value) {
        if (value == null || worker == null) return;
        final int distance = value.getRemainDistance();
        final int time = value.getRemainTime();
        worker.post(() -> {
            markBaiduHudNavigationActive();
            boolean changed = false;
            if (distance >= 0 && distance != state.totalDistance) {
                state.totalDistance = distance;
                changed = true;
            }
            if (time >= 0 && time != state.totalTimeSeconds) {
                state.totalTimeSeconds = time;
                changed = true;
            }
            if (changed) scheduleRender();
        });
    }

    private void handleBaiduHudDestInfo(BNRemoteMessage.BNRGDestInfo value) {
        if (value == null || worker == null) return;
        final int distance = value.getDestTotalDist();
        worker.post(() -> {
            markBaiduHudNavigationActive();
            if (distance >= 0 && distance != state.totalDistance) {
                state.totalDistance = distance;
                scheduleRender();
            }
        });
    }

    private void updateBaiduHudRoad(String road, boolean current) {
        if (road == null || worker == null) return;
        final String normalized = road.trim();
        if (normalized.isEmpty()) return;
        worker.post(() -> {
            markBaiduHudNavigationActive();
            boolean changed;
            if (current) {
                changed = !normalized.equals(state.currentRoad);
                if (changed) state.currentRoad = normalized;
            } else {
                changed = !normalized.equals(state.nextRoad);
                if (changed) state.nextRoad = normalized;
            }
            if (changed) scheduleRender();
        });
    }

    private void handleBaiduHudManeuver(BNRemoteMessage.BNRGManeuver value) {
        if (value == null || worker == null) return;
        final int distance = value.getManeuverDistance();
        final String name = trimToEmpty(value.getManeuverName());
        final String nextRoad = trimToEmpty(value.getNextRoadName());
        final String tips = trimToEmpty(value.getRGTips());
        worker.post(() -> {
            markBaiduHudNavigationActive();
            boolean changed = false;
            if (distance >= 0 && distance != state.maneuverDistance) {
                state.maneuverDistance = distance;
                state.maneuverDistanceText = "";
                state.maneuverDistanceUnit = "";
                changed = true;
            }
            if (!name.isEmpty() && !name.equals(state.direction)) {
                state.direction = name;
                changed = true;
            }
            if (!nextRoad.isEmpty() && !nextRoad.equals(state.nextRoad)) {
                state.nextRoad = nextRoad;
                changed = true;
            }
            String immediate = tips.contains("现在进入") ? tips : "";
            if (!immediate.equals(state.immediateGuideText)) {
                state.immediateGuideText = immediate;
                changed = true;
            }
            if (changed) scheduleRender();
        });
    }

    private void handleBaiduHudEnlargeRoad(BNRemoteMessage.BNEnlargeRoad value) {
        if (value == null || worker == null) return;
        final int enlargeState = value.getEnlargeRoadState();
        final int remainDistance = value.getRemainDist();
        final String roadName = trimToEmpty(value.getRoadName());
        final Bitmap basicImage = value.getBasicImage();
        final Bitmap arrowImage = value.getArrowImage();
        worker.post(() -> {
            markBaiduHudNavigationActive();
            boolean changed = false;
            byte[] png = enlargeState == EXPAND_MAP_STATE_HIDE
                    ? null : composeEnlargePng(basicImage, arrowImage);
            if (enlargeState == EXPAND_MAP_STATE_HIDE) {
                if (state.enlargeImage != null) {
                    state.enlargeImage = null;
                    state.enlargeUpdatedAt = 0;
                    changed = true;
                }
            } else if (png != null && png.length > 0) {
                state.enlargeImage = png;
                state.enlargeUpdatedAt = System.currentTimeMillis();
                changed = true;
            }
            if (remainDistance >= 0 && remainDistance != state.maneuverDistance) {
                state.maneuverDistance = remainDistance;
                state.maneuverDistanceText = "";
                state.maneuverDistanceUnit = "";
                changed = true;
            }
            if (!roadName.isEmpty() && !roadName.equals(state.nextRoad)) {
                state.nextRoad = roadName;
                changed = true;
            }
            if (changed) scheduleRender();
        });
    }

    private byte[] composeEnlargePng(Bitmap basicImage, Bitmap arrowImage) {
        Bitmap source = basicImage != null ? basicImage : arrowImage;
        if (source == null) return null;
        Bitmap output = null;
        try {
            output = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            if (basicImage != null) {
                canvas.drawBitmap(basicImage, 0, 0, null);
            }
            if (arrowImage != null) {
                canvas.drawBitmap(arrowImage, null,
                        new android.graphics.Rect(0, 0, output.getWidth(), output.getHeight()), null);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            output.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            return bytes.toByteArray();
        } catch (Throwable error) {
            log("Baidu HUD SDK enlarge compose failed: " + summarize(error));
            return null;
        } finally {
            if (output != null) output.recycle();
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void bindMapAidl() {
        if (mapAidlBinding || mapAidlBinder != null || destroyed) return;
        mapAidlBinding = true;
        Intent intent = new Intent(MAP_AIDL_ACTION).setPackage(BAIDU_PKG);
        mapAidlConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                mapAidlBinding = false;
                mapAidlBinder = service;
                worker.post(() -> {
                    log("V21 HUD map service connected");
                    if (state.navigating) ensureHudOffscreenActive();
                });
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                mapAidlBinder = null;
                releaseHudOffscreenSurface(false);
                mapAidlBinding = false;
                mainHandler.postDelayed(BridgeService.this::bindMapAidl, 1_500);
            }
        };
        try {
            if (!bindService(intent, mapAidlConnection, Context.BIND_AUTO_CREATE)) {
                mapAidlBinding = false;
                log("V21 HUD map service unavailable");
                mainHandler.postDelayed(this::bindMapAidl, 2_000);
            }
        } catch (Throwable error) {
            mapAidlBinding = false;
            log("V21 HUD map bind failed: " + summarize(error));
            mainHandler.postDelayed(this::bindMapAidl, 2_000);
        }
    }

    private void bindOpenControl() {
        if (openControlBinding || openControlServiceBinder != null || destroyed) return;
        long now = System.currentTimeMillis();
        if (now - lastOpenControlBindAttemptAt < OPEN_CONTROL_RETRY_MS) return;
        lastOpenControlBindAttemptAt = now;
        openControlBinding = true;
        Intent intent = new Intent(OPEN_CONTROL_ACTION).setPackage(BAIDU_PKG);
        intent.putExtra("package_name", getPackageName());
        intent.putExtra("version", "1.0");
        intent.putExtra("from", "v21_h53_hud_bridge");
        intent.putExtra("auth_api_key", "");
        openControlNotifyBinder = new OpenControlNotifyBinder();
        openControlConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                openControlBinding = false;
                openControlFactoryBinder = service;
                worker.post(BridgeService.this::initializeOpenControl);
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                openControlFactoryBinder = null;
                openControlServiceBinder = null;
                openControlBinding = false;
                mainHandler.postDelayed(BridgeService.this::bindOpenControl, OPEN_CONTROL_RETRY_MS);
            }
        };
        try {
            if (!bindService(intent, openControlConnection, Context.BIND_AUTO_CREATE)) {
                openControlBinding = false;
                log("V21 OpenControl bind returned false");
                mainHandler.postDelayed(this::bindOpenControl, OPEN_CONTROL_RETRY_MS);
            }
        } catch (Throwable error) {
            openControlBinding = false;
            log("V21 OpenControl bind failed: " + summarize(error));
            mainHandler.postDelayed(this::bindOpenControl, OPEN_CONTROL_RETRY_MS);
        }
    }

    private void initializeOpenControl() {
        try {
            openControlServiceBinder = queryOpenControlServiceBinder("v21_h53_hud_bridge");
            if (openControlServiceBinder == null) {
                log("V21 OpenControl service binder unavailable");
                return;
            }
            setOpenControlNotifyHandler();
            log("V21 OpenControl listener registered");
        } catch (Throwable error) {
            log("V21 OpenControl initialize failed: " + summarize(error));
        }
    }

    private IBinder queryOpenControlServiceBinder(String type) throws RemoteException {
        if (openControlFactoryBinder == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPEN_CONTROL_FACTORY_IFACE);
            data.writeString(type);
            if (!openControlFactoryBinder.transact(TRANSACTION_OPEN_CONTROL_FACTORY, data, reply, 0)) {
                return null;
            }
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void setOpenControlNotifyHandler() throws RemoteException {
        if (openControlServiceBinder == null || openControlNotifyBinder == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPEN_CONTROL_SERVICE_IFACE);
            data.writeStrongBinder(openControlNotifyBinder);
            if (openControlServiceBinder.transact(
                    TRANSACTION_OPEN_CONTROL_SET_HANDLER, data, reply, 0)) {
                reply.readException();
            }
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Activates Baidu's documented HUD off-screen pipeline. V21 only produces
     * HUD junction image bytes while at least one HUD map Surface is active.
     * A 1x1 map target is sufficient for that lifecycle flag; the junction
     * bitmap itself is generated separately by V21 at 400x303.
     */
    private void ensureHudOffscreenActive() {
        if (mapAidlBinder == null || mapSurface != null || !state.navigating) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            mapSurfaceTexture = new SurfaceTexture(0);
            mapSurfaceTexture.setDefaultBufferSize(1, 1);
            mapSurface = new Surface(mapSurfaceTexture);
            data.writeInterfaceToken(MAP_AIDL_IFACE);
            data.writeString(HUD_SURFACE_TAG);
            data.writeInt(1);
            mapSurface.writeToParcel(data, 0);
            data.writeInt(1);              // non-null HudMapConfig
            data.writeIntArray(null);      // visibleElements
            data.writeIntArray(null);      // invisibleElements
            data.writeInt(1);              // width
            data.writeInt(1);              // height
            data.writeDouble(0);           // offsetY
            data.writeDouble(0);           // offsetX
            data.writeFloat(16.0f);        // official default map level
            data.writeParcelable(null, 0); // enlargeDrawRect
            if (!mapAidlBinder.transact(TRANSACTION_DRAW_HUD_MAP, data, reply, 0)) {
                throw new RemoteException("drawHUDMap transaction rejected");
            }
            reply.readException();
            log("V21 HUD offscreen source activated");
        } catch (Throwable error) {
            log("V21 HUD offscreen activation failed: " + summarize(error));
            releaseHudOffscreenSurface(false);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void releaseHudOffscreenSurface(boolean notifyV21) {
        if (notifyV21 && mapAidlBinder != null && mapSurface != null) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MAP_AIDL_IFACE);
                data.writeString(HUD_SURFACE_TAG);
                mapAidlBinder.transact(TRANSACTION_DESTROY_HUD_MAP, data, reply, 0);
                reply.readException();
            } catch (Throwable error) {
                log("V21 HUD offscreen release failed: " + summarize(error));
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        if (mapSurface != null) mapSurface.release();
        if (mapSurfaceTexture != null) mapSurfaceTexture.release();
        mapSurface = null;
        mapSurfaceTexture = null;
    }

    private void bindH53() {
        if (h53Binding || h53CommBinder != null || destroyed) return;
        h53Binding = true;
        Intent intent = new Intent(H53_SERVICE).setPackage(H53_PKG);
        h53Connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                h53Binding = false;
                h53PoolBinder = service;
                worker.post(() -> {
                    try {
                        h53CommBinder = queryH53Binder(1);
                        log("H53 connected " + (h53CommBinder != null));
                        if (state.navigating) scheduleRender();
                    } catch (Throwable error) {
                        log("H53 query failed: " + summarize(error));
                    }
                });
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                h53PoolBinder = null;
                h53CommBinder = null;
                hudVisible = false;
                lastRenderKey = "";
                h53Binding = false;
                mainHandler.postDelayed(BridgeService.this::bindH53, 1_500);
            }
        };
        try {
            if (!bindService(intent, h53Connection, Context.BIND_AUTO_CREATE)) {
                h53Binding = false;
                mainHandler.postDelayed(this::bindH53, 2_000);
            }
        } catch (Throwable error) {
            h53Binding = false;
            log("H53 bind failed: " + summarize(error));
            mainHandler.postDelayed(this::bindH53, 2_000);
        }
    }

    private void bindV21() {
        if (v21Binding || v21Binder != null || destroyed) return;
        v21Binding = true;
        Intent intent = new Intent(LBS_ACTION).setPackage(BAIDU_PKG);
        intent.putExtra("package", getPackageName());
        eventBinder = new EventBinder();
        v21Connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                v21Binding = false;
                v21Binder = service;
                worker.post(BridgeService.this::initializeV21);
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                v21Binder = null;
                cryptKey = null;
                listenersSubscribed = false;
                authoritativeGuideActive = false;
                navigationStatusKnown = false;
                navigationStatusActive = false;
                v21Binding = false;
                worker.post(BridgeService.this::stopNavigation);
                mainHandler.postDelayed(BridgeService.this::bindV21, 1_500);
            }
        };
        try {
            if (!bindService(intent, v21Connection, Context.BIND_AUTO_CREATE)) {
                v21Binding = false;
                mainHandler.postDelayed(this::bindV21, 2_000);
            }
        } catch (Throwable error) {
            v21Binding = false;
            log("V21 bind failed: " + summarize(error));
            mainHandler.postDelayed(this::bindV21, 2_000);
        }
    }

    private void initializeV21() {
        try {
            cryptKey = rawSend("on_sync_connect_state");
            setEventListener();
            subscribeListeners();
            sendFunction("is_in_navi", 0);
            if (state.navigating) sendFallbackSnapshot();
            worker.removeCallbacks(pollRunnable);
            worker.postDelayed(pollRunnable, 1_000);
            log("V21 listeners registered");
        } catch (Throwable error) {
            log("V21 initialize failed: " + summarize(error));
        }
    }

    private boolean subscribe(String function) {
        return sendFunction(function, 1);
    }

    private void subscribeListeners() {
        if (listenersSubscribed) return;
        lastSubscriptionAt = System.currentTimeMillis();
        boolean success = true;
        success &= subscribe("add_on_navi_status_change_listener");
        success &= subscribe("add_on_turn_info_change_listener");
        success &= subscribe("add_on_mapmatch_info_change_listener");
        success &= subscribe("add_on_remain_info_change_listener");
        success &= subscribe("add_app_guide_info_change_listener");
        success &= subscribe("add_on_lane_info_change_listener");
        success &= subscribe("add_app_lane_info_change_listener");
        success &= subscribe("add_on_enlarge_info_change_listener");
        success &= subscribe("add_on_hud_enlarge_info_change_listener");
        success &= subscribe("add_on_speed_limit_change_listener");
        listenersSubscribed = success;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (v21Binder != null) {
                long now = System.currentTimeMillis();
                if (now - lastNavigationCheckAt >= NAVIGATION_POLL_MS) {
                    sendFunction("is_in_navi", 0);
                    lastNavigationCheckAt = now;
                }
                if (state.navigating && !authoritativeGuideActive
                        && now - lastGuideEventAt > EVENT_FRESH_MS) {
                    sendFallbackSnapshot();
                }
            }
            worker.postDelayed(this, 250);
        }
    };

    private void sendFallbackSnapshot() {
        sendFunction("get_tbtinfo", 0);
        sendFunction("get_map_match_info", 0);
        sendFunction("get_remain_info", 0);
        sendFunction("get_speed_limit", 0);
    }

    private boolean sendFunction(String function, int flag) {
        if (v21Binder == null) return false;
        try {
            String request = buildRequest(function, flag);
            String response = rawSend(cryptKey == null ? request : encrypt(cryptKey, request));
            String decoded = decryptIfPossible(response);
            if (!tbtLogged && "get_tbtinfo".equals(function)) {
                tbtLogged = true;
                log("V21 TBT snapshot received");
            }
            applyPayload(decoded, false);
            return decoded != null && decoded.contains("\"errorCode\":0");
        } catch (Throwable error) {
            log("V21 " + function + " failed: " + summarize(error));
            return false;
        }
    }

    private void applyPayload(String payload, boolean event) {
        if (payload == null || !payload.trim().startsWith("{")) return;
        try {
            V21PayloadParser.Result result = parser.apply(payload, state, !authoritativeGuideActive);
            String normalized = payload.toLowerCase(java.util.Locale.US);
            boolean explicitStatus = normalized.contains("is_in_navi")
                    || normalized.contains("navi_status");
            if (explicitStatus && result.navigationSignal != V21PayloadParser.NavigationSignal.NONE) {
                boolean stoppedTransition = !navigationStatusKnown || navigationStatusActive
                        || state.navigating || hudVisible;
                navigationStatusKnown = true;
                navigationStatusActive = result.navigationSignal == V21PayloadParser.NavigationSignal.ACTIVE;
                if (!navigationStatusActive) {
                    if (stoppedTransition) {
                        log("V21 authoritative navigation status stopped");
                        stopNavigation();
                    }
                    return;
                }
            }
            if (event && result.freshGuide) {
                lastGuideEventAt = System.currentTimeMillis();
                if (result.authoritativeGuide && !authoritativeGuideActive) {
                    authoritativeGuideActive = true;
                    log("V21 app guide events active");
                }
            }
            if (result.navigationSignal == V21PayloadParser.NavigationSignal.ACTIVE) {
                if (navigationStatusKnown && !navigationStatusActive) {
                    state.clearNavigation();
                    return;
                }
                inactiveSignals = 0;
                if (!state.navigating) log("V21 navigation active");
                state.navigating = true;
                ensureHudOffscreenActive();
                long now = System.currentTimeMillis();
                if (!listenersSubscribed && now - lastSubscriptionAt > 5_000) {
                    subscribeListeners();
                }
            } else if (result.navigationSignal == V21PayloadParser.NavigationSignal.STOPPED) {
                boolean statusEvent = event && normalized.contains("navi_status");
                if (statusEvent || ++inactiveSignals >= 2) {
                    stopNavigation();
                    return;
                }
            }
            if (result.changed && state.navigating) {
                scheduleRender();
            }
        } catch (Throwable error) {
            log("V21 payload rejected: " + summarize(error));
        }
    }

    private void stopNavigation() {
        log("V21 navigation stopped");
        state.clearNavigation();
        inactiveSignals = 0;
        lastGuideEventAt = 0;
        authoritativeGuideActive = false;
        lastRenderKey = "";
        hideHud();
        releaseHudOffscreenSurface(true);
    }

    private void scheduleRender() {
        if (renderPending || !state.navigating || h53CommBinder == null
                || !HudSettings.hasAnyContent(this)) return;
        renderPending = true;
        long delay = Math.max(0, MIN_RENDER_INTERVAL_MS - (System.currentTimeMillis() - lastRenderAt));
        worker.postDelayed(() -> {
            renderPending = false;
            if (!state.navigating || h53CommBinder == null) return;
            String key = state.renderKey();
            if (key.equals(lastRenderKey)) return;
            try {
                Bitmap bitmap = renderer.render(state);
                String path = writeHudImage(bitmap);
                showHudImage(path);
                lastRenderAt = System.currentTimeMillis();
                lastRenderKey = key;
            } catch (Throwable error) {
                log("HUD render failed: " + summarize(error));
            }
        }, delay);
    }

    private String writeHudImage(Bitmap bitmap) throws Exception {
        imageSequence = (imageSequence + 1) % 8;
        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File directory = new File(pictures, "V21H53Bridge");
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IllegalStateException("Public HUD image directory unavailable: " + directory);
        }
        File file = new File(directory, "v21_h53_" + imageSequence + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            bitmap.recycle();
        }
        file.setReadable(true, false);
        directory.setReadable(true, false);
        directory.setExecutable(true, false);
        return file.getAbsolutePath();
    }

    private void showHudImage(String path) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(H53_COMM_IFACE);
            data.writeString(path);
            boolean accepted = h53CommBinder.transact(0x34, data, reply, 0);
            if (!accepted) throw new RemoteException("H53 rejected showLocalImage transaction");
            reply.readException();
            if (!hudVisible) log("HUD local navigation image submitted: " + path);
            hudVisible = true;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void hideHud() {
        if (h53CommBinder == null) {
            hudVisible = false;
            return;
        }
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(H53_COMM_IFACE);
                if (h53CommBinder.transact(0x31, data, reply, 0)) reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable error) {
            log("HUD hide failed: " + summarize(error));
        } finally {
            hudVisible = false;
        }
    }

    private IBinder queryH53Binder(int id) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(H53_POOL_IFACE);
            data.writeInt(id);
            if (!h53PoolBinder.transact(1, data, reply, 0)) return null;
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private String buildRequest(String function, int flag) {
        return "{\"requestId\":" + (++requestCounter)
                + ",\"function\":\"" + function + "\""
                + ",\"flag\":" + flag
                + ",\"pid\":\"" + Process.myPid() + "\",\"data\":null}";
    }

    private String rawSend(String payload) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_IFACE);
            data.writeString(payload);
            if (!v21Binder.transact(TRANSACTION_SEND, data, reply, 0)) return null;
            reply.readException();
            return reply.readString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void setEventListener() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_IFACE);
            data.writeStrongBinder(eventBinder);
            data.writeString(String.valueOf(Process.myPid()));
            if (v21Binder.transact(TRANSACTION_SET_EVENT_LISTENER, data, reply, 0)) reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void removeEventListener() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_IFACE);
            data.writeString(String.valueOf(Process.myPid()));
            if (v21Binder.transact(TRANSACTION_REMOVE_EVENT_LISTENER, data, reply, 0)) reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private final class EventBinder extends Binder {
        EventBinder() { attachInterface(null, EVENT_IFACE); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(EVENT_IFACE);
                return true;
            }
            if (code == TRANSACTION_EVENT) {
                data.enforceInterface(EVENT_IFACE);
                String event = data.readString();
                worker.post(() -> {
                    String decoded = decryptIfPossible(event);
                    applyPayload(decoded, true);
                });
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private final class OpenControlNotifyBinder extends Binder {
        OpenControlNotifyBinder() { attachInterface(null, OPEN_CONTROL_NOTIFY_IFACE); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(OPEN_CONTROL_NOTIFY_IFACE);
                return true;
            }
            if (code == TRANSACTION_OPEN_CONTROL_NOTIFY) {
                data.enforceInterface(OPEN_CONTROL_NOTIFY_IFACE);
                int scene = data.readInt();
                String payload = data.readString();
                if (reply != null) reply.writeNoException();
                worker.post(() -> {
                    String decoded = decryptIfPossible(payload);
                    String lower = decoded == null ? "" : decoded.toLowerCase(java.util.Locale.US);
                    if (isTrafficLightCandidate(lower) || scene == OPEN_CONTROL_SCENE_NAVI) {
                        logPayloadChunks("V21 OpenControl raw scene=" + scene, decoded);
                    }
                    if (lower.contains("\"error\"")) {
                        log("V21 OpenControl event error: " + summarizePayload(decoded));
                    }
                    applyPayload(decoded, true);
                });
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private String encrypt(String key, String plain) throws Exception {
        SecretKeySpec secret = new SecretKeySpec(Base64.decode(key.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT), "AES");
        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secret, new GCMParameterSpec(128, AES_IV.getBytes(StandardCharsets.UTF_8)));
        return new String(Base64.encode(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private String decryptIfPossible(String value) {
        if (cryptKey == null || value == null || value.isEmpty()) return value;
        try {
            SecretKeySpec secret = new SecretKeySpec(Base64.decode(cryptKey.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT), "AES");
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, AES_IV.getBytes(StandardCharsets.UTF_8)));
            byte[] decoded = Base64.decode(value.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return value;
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            if (v21Binder != null) try { removeEventListener(); } catch (Throwable ignored) { }
            hideHud();
        }
        if (baiduBroadcastReceiver != null) try { unregisterReceiver(baiduBroadcastReceiver); } catch (Throwable ignored) { }
        if (resumeReceiver != null) try { unregisterReceiver(resumeReceiver); } catch (Throwable ignored) { }
        if (hudPreferences != null) hudPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        if (renderer != null) renderer.clearAssetCache();
        if (baiduHudSdkInitialized) {
            try { BNRemoteVistor.getInstance().unInit(); } catch (Throwable ignored) { }
        }
        if (v21Connection != null) try { unbindService(v21Connection); } catch (Throwable ignored) { }
        releaseHudOffscreenSurface(true);
        if (mapAidlConnection != null) try { unbindService(mapAidlConnection); } catch (Throwable ignored) { }
        if (h53Connection != null) try { unbindService(h53Connection); } catch (Throwable ignored) { }
        if (openControlConnection != null) try { unbindService(openControlConnection); } catch (Throwable ignored) { }
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    private void log(String message) { Log.i(TAG, message); }

    private boolean isTrafficLightCandidate(String normalizedPayload) {
        return normalizedPayload.contains("traffic_light")
                || normalizedPayload.contains("trafficlight")
                || normalizedPayload.contains("traffic_light_info")
                || normalizedPayload.contains("lamp_status")
                || normalizedPayload.contains("count_down")
                || normalizedPayload.contains("countdown")
                || normalizedPayload.contains("lighttype")
                || normalizedPayload.contains("remainsec")
                || normalizedPayload.contains("mtrafficlights")
                || normalizedPayload.contains("mperiodlights");
    }

    private void logPayloadChunks(String prefix, String payload) {
        if (payload == null) {
            log(prefix + ": null");
            return;
        }
        String compact = payload.replace('\n', ' ').replace('\r', ' ').trim();
        int chunkSize = 3000;
        int count = Math.max(1, (compact.length() + chunkSize - 1) / chunkSize);
        for (int i = 0; i < count; i++) {
            int start = i * chunkSize;
            int end = Math.min(compact.length(), start + chunkSize);
            log(prefix + " [" + (i + 1) + "/" + count + "]: " + compact.substring(start, end));
        }
    }

    private String summarizePayload(String payload) {
        if (payload == null) return "null";
        String compact = payload.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 180) + "...";
    }

    private String summarizeText(String text) {
        if (text == null) return "";
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
    }

    private String summarize(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
