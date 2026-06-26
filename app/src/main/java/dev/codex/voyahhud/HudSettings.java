package dev.codex.voyahhud;

import android.content.Context;
import android.content.SharedPreferences;

final class HudSettings {
    static final String PREFS = "hud_display_settings";
    static final String TURN_ARROW = "show_turn_arrow";
    static final String MANEUVER_DISTANCE = "show_maneuver_distance";
    static final String ROADS = "show_roads";
    static final String ENLARGE = "show_enlarge";
    static final String LANES = "show_lanes";
    static final String TRAFFIC_LIGHT = "show_traffic_light";
    static final String REMAINING = "show_remaining";
    static final String SPEED = "show_speed";
    static final String SPEED_LIMIT = "show_speed_limit";
    static final String SIZE_SUFFIX = "_size_percent";
    static final String X_SUFFIX = "_center_x_px";
    static final String Y_SUFFIX = "_center_y_px";
    private static final String SPEED_15X_MIGRATED = "speed_15x_from_v46";
    private static final String SPEED_V49_BASE_MIGRATED = "speed_v49_base_70pct_of_v48";
    private static final String ENLARGE_V50_BASE_MIGRATED = "enlarge_v50_base_150pct";
    // v4.8 rendered speed at 300%. New UI default is 100%, while the actual
    // target size is 70% of v4.8: 3.0 * 0.7 = 2.1.
    private static final float SPEED_BASE_SCALE = 2.1f;
    // Keep the UI value at 100%, but render the junction detail image at 1.5x
    // by default so the HUD image is easier to read.
    private static final float ENLARGE_BASE_SCALE = 1.5f;
    static final String[] CONTENT_KEYS = {
            TURN_ARROW, MANEUVER_DISTANCE, ROADS, ENLARGE, LANES,
            TRAFFIC_LIGHT, REMAINING, SPEED, SPEED_LIMIT
    };

    private HudSettings() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context, String key) {
        return preferences(context).getBoolean(key, true);
    }

    static String sizeKey(String contentKey) {
        return contentKey + SIZE_SUFFIX;
    }

    static float elementScale(Context context, String contentKey) {
        int percent = preferences(context).getInt(sizeKey(contentKey), defaultElementPercent(contentKey));
        int maximum = SPEED.equals(contentKey) ? 400 : 250;
        float uiScale = Math.max(50, Math.min(maximum, percent)) / 100f;
        if (SPEED.equals(contentKey)) return uiScale * SPEED_BASE_SCALE;
        if (ENLARGE.equals(contentKey)) return uiScale * ENLARGE_BASE_SCALE;
        return uiScale;
    }

    static String xKey(String contentKey) { return contentKey + X_SUFFIX; }
    static String yKey(String contentKey) { return contentKey + Y_SUFFIX; }

    static int positionX(Context context, String contentKey) {
        return clamp(preferences(context).getInt(xKey(contentKey), defaultX(contentKey)), 0, HudRenderer.WIDTH);
    }

    static int positionY(Context context, String contentKey) {
        return clamp(preferences(context).getInt(yKey(contentKey), defaultY(contentKey)), 0, HudRenderer.HEIGHT);
    }

    static int defaultX(String key) {
        if (TURN_ARROW.equals(key)) return 40;
        if (MANEUVER_DISTANCE.equals(key)) return 83;
        if (ROADS.equals(key)) return 105;
        if (ENLARGE.equals(key)) return 559;
        if (SPEED.equals(key)) return 380;
        if (SPEED_LIMIT.equals(key)) return 740;
        if (TRAFFIC_LIGHT.equals(key)) return 380;
        if (LANES.equals(key)) return 380;
        if (REMAINING.equals(key)) return 146;
        return HudRenderer.WIDTH / 2;
    }

    static int defaultY(String key) {
        if (TURN_ARROW.equals(key)) return 34;
        if (MANEUVER_DISTANCE.equals(key)) return 120;
        if (ROADS.equals(key)) return 220;
        if (ENLARGE.equals(key)) return 127;
        if (SPEED.equals(key)) return 100;
        if (SPEED_LIMIT.equals(key)) return 28;
        if (TRAFFIC_LIGHT.equals(key)) return 210;
        if (LANES.equals(key)) return 265;
        if (REMAINING.equals(key)) return 295;
        return HudRenderer.HEIGHT / 2;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static void resetAllLayout(Context context) {
        SharedPreferences.Editor editor = preferences(context).edit();
        for (String key : CONTENT_KEYS) {
            editor.putInt(xKey(key), defaultX(key));
            editor.putInt(yKey(key), defaultY(key));
            editor.putInt(sizeKey(key), defaultElementPercent(key));
        }
        editor.apply();
    }

    static String boundaryWarning(Context context, String key) {
        float scale = elementScale(context, key);
        float halfWidth = baseWidth(key) * scale / 2f;
        float halfHeight = baseHeight(key) * scale / 2f;
        int x = positionX(context, key);
        int y = positionY(context, key);
        StringBuilder sides = new StringBuilder();
        if (x - halfWidth < 0) sides.append("左侧 ");
        if (x + halfWidth > HudRenderer.WIDTH) sides.append("右侧 ");
        if (y - halfHeight < 0) sides.append("顶部 ");
        if (y + halfHeight > HudRenderer.HEIGHT) sides.append("底部 ");
        return sides.length() == 0 ? "" : "警告：内容可能超出画布（" + sides.toString().trim() + "）";
    }

    private static int baseWidth(String key) {
        if (TURN_ARROW.equals(key)) return 100;
        if (MANEUVER_DISTANCE.equals(key)) return 210;
        if (ROADS.equals(key)) return 245;
        if (ENLARGE.equals(key)) return 195;
        if (LANES.equals(key)) return 350;
        if (TRAFFIC_LIGHT.equals(key)) return 120;
        if (REMAINING.equals(key)) return 270;
        if (SPEED.equals(key)) return 114;
        if (SPEED_LIMIT.equals(key)) return 60;
        return 100;
    }

    private static int baseHeight(String key) {
        if (TURN_ARROW.equals(key)) return 100;
        if (MANEUVER_DISTANCE.equals(key)) return 66;
        if (ROADS.equals(key)) return 100;
        if (ENLARGE.equals(key)) return 115;
        if (LANES.equals(key)) return 60;
        if (TRAFFIC_LIGHT.equals(key)) return 50;
        if (REMAINING.equals(key)) return 34;
        if (SPEED.equals(key)) return 66;
        if (SPEED_LIMIT.equals(key)) return 60;
        return 100;
    }

    static int defaultElementPercent(String contentKey) {
        if (TURN_ARROW.equals(contentKey)) return 68;
        if (MANEUVER_DISTANCE.equals(contentKey)) return 79;
        if (ROADS.equals(contentKey)) return 85;
        if (ENLARGE.equals(contentKey)) return 100;
        if (LANES.equals(contentKey)) return 85;
        if (TRAFFIC_LIGHT.equals(contentKey)) return 100;
        if (REMAINING.equals(contentKey)) return 106;
        if (SPEED.equals(contentKey)) return 80;
        if (SPEED_LIMIT.equals(contentKey)) return 86;
        return 100;
    }

    static void migrateFromV46(Context context) {
        SharedPreferences prefs = preferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        if (!prefs.getBoolean(SPEED_15X_MIGRATED, false)) {
            int oldPercent = prefs.getInt(sizeKey(SPEED), 200);
            int newPercent = Math.max(50, Math.min(400, Math.round(oldPercent * 1.5f)));
            editor.putInt(sizeKey(SPEED), newPercent)
                    .putBoolean(SPEED_15X_MIGRATED, true);
            changed = true;
        }
        if (!prefs.getBoolean(SPEED_V49_BASE_MIGRATED, false)) {
            editor.putInt(sizeKey(SPEED), 100)
                    .putBoolean(SPEED_V49_BASE_MIGRATED, true);
            changed = true;
        }
        if (!prefs.getBoolean(ENLARGE_V50_BASE_MIGRATED, false)) {
            if (prefs.contains(sizeKey(ENLARGE))) {
                int oldPercent = prefs.getInt(sizeKey(ENLARGE), 150);
                int newPercent = Math.max(50, Math.min(250,
                        Math.round(oldPercent / ENLARGE_BASE_SCALE)));
                editor.putInt(sizeKey(ENLARGE), newPercent);
            }
            editor.putBoolean(ENLARGE_V50_BASE_MIGRATED, true);
            changed = true;
        }
        if (changed) editor.apply();
    }

    static boolean hasAnyContent(Context context) {
        return enabled(context, TURN_ARROW) || enabled(context, MANEUVER_DISTANCE)
                || enabled(context, ROADS) || enabled(context, ENLARGE)
                || enabled(context, LANES) || enabled(context, REMAINING)
                || enabled(context, TRAFFIC_LIGHT)
                || enabled(context, SPEED) || enabled(context, SPEED_LIMIT);
    }
}
