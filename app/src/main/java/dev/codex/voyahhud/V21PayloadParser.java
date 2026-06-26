package dev.codex.voyahhud;

import android.util.Base64;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class V21PayloadParser {
    enum NavigationSignal { NONE, ACTIVE, STOPPED }

    static final class Result {
        NavigationSignal navigationSignal = NavigationSignal.NONE;
        boolean changed;
        boolean freshGuide;
        boolean authoritativeGuide;
    }

    Result apply(String payload, HudState state, boolean allowFallbackGuide) throws JSONException {
        JSONObject root = new JSONObject(payload);
        String function = string(root, "function", string(root, "type", "")).toLowerCase(Locale.US);
        String method = string(root, "method", "").toLowerCase(Locale.US);
        Result result = new Result();

        int errorCode = integer(root, "errorCode", 0);
        if (function.contains("is_in_navi")) {
            Object value = find(root, "result");
            Boolean active = asBoolean(value);
            if (active != null) {
                result.navigationSignal = active ? NavigationSignal.ACTIVE : NavigationSignal.STOPPED;
            }
        } else if ((function.contains("tbt") && errorCode == 8) || errorCode == 10) {
            result.navigationSignal = NavigationSignal.STOPPED;
        }

        if (function.contains("navi_status")) {
            Object status = find(root, "status");
            if (status instanceof Number) {
                int value = ((Number) status).intValue();
                result.navigationSignal = value > 0 ? NavigationSignal.ACTIVE : NavigationSignal.STOPPED;
            }
        }

        boolean appGuide = function.contains("app_guide");
        if (appGuide || (allowFallbackGuide && (function.contains("turn") || function.contains("tbt")))) {
            JSONObject turn = findObjectWithAny(root, "turnKind", "turnKindType");
            if (turn != null) {
                result.changed |= setString(turn, "roadName", state.currentRoad, v -> state.currentRoad = v);
                result.changed |= setString(turn, "nextRoadName", state.nextRoad, v -> state.nextRoad = v);
                String direction = string(turn, "directionContent", string(turn, "directionName", ""));
                if (direction.isEmpty()) direction = string(turn, "gotoContent", "");
                if (!direction.isEmpty() && !direction.equals(state.direction)) {
                    state.direction = direction;
                    result.changed = true;
                }
                int turnKind = intAny(turn, state.turnKind, "turnKind", "maneuver", "turnKindType");
                if (turnKind != state.turnKind) {
                    state.turnKind = turnKind;
                    result.changed = true;
                }
                int distance = integer(turn, "remainDistance", -1);
                if (distance < 0) {
                    distance = integer(turn, "linkRemainDist", integer(turn, "distance", -1));
                }
                if (distance >= 0 && distance != state.maneuverDistance) {
                    state.maneuverDistance = distance;
                    result.changed = true;
                }
                if (appGuide) {
                    String displayDistance = string(turn, "distance", "").trim();
                    String displayUnit = string(turn, "distanceUnit", "").trim();
                    if (!displayDistance.equals(state.maneuverDistanceText)
                            || !displayUnit.equals(state.maneuverDistanceUnit)) {
                        state.maneuverDistanceText = displayDistance;
                        state.maneuverDistanceUnit = displayUnit;
                        result.changed = true;
                    }
                    String immediate = immediateGuideText(turn);
                    if (!immediate.equals(state.immediateGuideText)) {
                        state.immediateGuideText = immediate;
                        result.changed = true;
                    }
                }
                state.guideUpdatedAt = System.currentTimeMillis();
                result.freshGuide = true;
                result.authoritativeGuide = appGuide;
                result.navigationSignal = NavigationSignal.ACTIVE;
            }
        }

        if (function.contains("mapmatch") || function.contains("map_match")) {
            JSONObject match = findObjectWithAny(root, "linkName", "roadName", "speedLimit");
            if (match != null) {
                String road = string(match, "linkName", string(match, "roadName", ""));
                if (!road.isEmpty() && !road.equals(state.currentRoad)) {
                    state.currentRoad = road;
                    result.changed = true;
                }
                result.changed |= updateSpeedLimit(match, state);
            }
        }

        if (function.contains("remain_info") || function.contains("remaininfo")) {
            JSONObject remain = findObjectWithAny(root, "totalRemainDist", "totalRemainTime", "remainTime");
            if (remain != null) {
                int distance = integer(remain, "totalRemainDist", integer(remain, "remainDistance", -1));
                int time = integer(remain, "totalRemainTime", integer(remain, "remainTime", -1));
                if (distance >= 0 && distance != state.totalDistance) {
                    state.totalDistance = distance;
                    result.changed = true;
                }
                if (time >= 0 && time != state.totalTimeSeconds) {
                    state.totalTimeSeconds = time;
                    result.changed = true;
                }
            }
        }

        if (function.contains("speed_limit")) {
            JSONObject limit = findObjectWithAny(root, "speedLimit", "limitSpeed", "currentSpeed");
            if (limit != null) {
                result.changed |= updateSpeedLimit(limit, state);
                result.changed |= updateCurrentSpeed(limit, state);
            }
        }

        if (function.contains("traffic_light") || method.contains("traffic_light")) {
            result.changed |= applyOpenControlTrafficLight(root, state);
        }

        if (function.contains("lane")) {
            List<HudState.Lane> lanes = parseLanes(root);
            JSONObject appLane = findObjectWithAny(root, "index", "laneInfo", "status");
            boolean explicitHide = function.contains("app_lane") && appLane != null
                    && integer(appLane, "index", -1) == 0
                    && integer(appLane, "status", -1) == 2;
            if (explicitHide || !lanes.isEmpty() || hasKey(root, "itemList")) {
                if (!sameLanes(lanes, state.lanes)) {
                    state.lanes.clear();
                    state.lanes.addAll(lanes);
                    result.changed = true;
                }
            }
        }

        if (function.contains("enlarge")) {
            // Baidu's enlarge-map callback is stateful: 0=SHOW, 1=UPDATE,
            // 2=HIDE.  A normal vector-junction update legitimately has no
            // imageBytes, so absence of bytes must never be treated as HIDE.
            Object statusValue = find(root, "status");
            int status = statusValue instanceof Number
                    ? ((Number) statusValue).intValue()
                    : statusValue instanceof String ? parseInt((String) statusValue, -1) : -1;
            byte[] image = findImageBytes(root);
            if (status == 2) {
                if (state.enlargeImage != null) {
                    state.enlargeImage = null;
                    state.enlargeUpdatedAt = 0;
                    result.changed = true;
                }
            } else if (image != null && image.length > 0) {
                state.enlargeImage = image;
                state.enlargeUpdatedAt = System.currentTimeMillis();
                result.changed = true;
            }
        }
        return result;
    }

    private boolean applyOpenControlTrafficLight(Object value, HudState state) {
        JSONObject light = findObjectWithAny(value, "countdownTime", "countDownTime", "color");
        if (light == null) return false;
        int[] normalized = normalizeTrafficLight(
                intAny(light, 0, "color", "lightType", "mLightType"),
                intAny(light, 0, "countdownTime", "countDownTime", "remainSec", "mRemainSec"));
        int color = normalized[0];
        int seconds = normalized[1];
        if (color == state.trafficLightColor && seconds == state.trafficLightSeconds) return false;
        state.trafficLightColor = color;
        state.trafficLightSeconds = seconds;
        return true;
    }

    private String immediateGuideText(JSONObject guide) {
        String distance = string(guide, "distance", "").trim();
        String unit = string(guide, "distanceUnit", "").trim();
        String direction = string(guide, "directionContent", "").trim();
        String go = string(guide, "gotoContent", "").trim();
        String road = string(guide, "nextRoadName", "").trim();
        String[] candidates = {
                distance + unit + direction + road,
                distance + unit + go + road + direction,
                direction, go, road, string(guide, "loadingContent", "")
        };
        for (String candidate : candidates) {
            String text = candidate == null ? "" : candidate.trim();
            if (text.contains("现在进入")) {
                if (!road.isEmpty() && !text.contains(road)) return text + " " + road;
                return text;
            }
        }
        return "";
    }

    boolean applyTrafficLight(String json, HudState state) throws JSONException {
        Object decoded = unwrap(json);
        int[] officialLight = readOfficialTrafficLight(decoded);
        int color = officialLight[0];
        int seconds = officialLight[1];

        JSONArray directions = array(decoded);
        if (directions == null) {
            JSONObject wrapper = object(decoded);
            if (wrapper != null) directions = array(wrapper.opt("mTrafficLights"));
        }
        if (color == 0 && directions != null && directions.length() > 0) {
            JSONObject direction = object(directions.opt(0));
            if (direction != null && boolAny(direction, false, "mTargetDirection", "targetDirection")) {
                JSONArray periods = array(first(direction, "mPeriodLights", "periodLights"));
                JSONObject period = periods == null || periods.length() == 0 ? null : object(periods.opt(0));
                if (period != null) {
                    int[] periodLight = normalizeTrafficLight(
                            intAny(period, 0, "mLightType", "lightType", "lamp_status_1", "lamp_status"),
                            intAny(period, 0, "mRemainSec", "remainSec", "count_down_1", "count_down"));
                    color = periodLight[0];
                    seconds = periodLight[1];
                }
            }
        }
        if (color == state.trafficLightColor && seconds == state.trafficLightSeconds) return false;
        state.trafficLightColor = color;
        state.trafficLightSeconds = seconds;
        return true;
    }

    private int[] readOfficialTrafficLight(Object value) {
        JSONObject light = findObjectWithAny(value,
                "lamp_status_1", "count_down_1", "lamp_status", "count_down",
                "color", "countdownTime", "countDownTime");
        if (light == null) return new int[]{0, 0};
        return normalizeTrafficLight(
                intAny(light, 0, "lamp_status_1", "lamp_status", "color", "lightType", "mLightType"),
                intAny(light, 0, "count_down_1", "count_down", "countdownTime", "countDownTime", "remainSec", "mRemainSec"));
    }

    private int[] normalizeTrafficLight(int rawColor, int rawSeconds) {
        int color;
        if (rawColor == 1 || rawColor == 21) color = 1;
        else if (rawColor == 2 || rawColor == 22) color = 2;
        else if (rawColor == 3 || rawColor == 23) color = 3;
        else color = 0;

        // Baidu official red-light countdown Web API documents 10000 as
        // "no countdown data mined"; do not show it as a real countdown.
        int seconds = rawSeconds > 0 && rawSeconds < 10000 ? rawSeconds : 0;
        if (color == 0 || seconds == 0) return new int[]{0, 0};
        return new int[]{color, seconds};
    }

    private Object first(JSONObject object, String... keys) {
        for (String key : keys) if (object.has(key)) return object.opt(key);
        return null;
    }

    private int intAny(JSONObject object, int fallback, String... keys) {
        for (String key : keys) if (object.has(key)) return integer(object, key, fallback);
        return fallback;
    }

    private boolean boolAny(JSONObject object, boolean fallback, String... keys) {
        for (String key : keys) if (object.has(key)) return bool(object, key, fallback);
        return fallback;
    }

    private boolean updateSpeedLimit(JSONObject object, HudState state) {
        int value = intAny(object, -1, "speedLimit", "limitSpeed");
        if (value >= 0 && value != state.speedLimit) {
            state.speedLimit = value;
            return true;
        }
        return false;
    }

    private boolean updateCurrentSpeed(JSONObject object, HudState state) {
        int value = intAny(object, -1, "currentSpeed", "curSpeed", "CUR_SPEED");
        if (value >= 0) {
            int normalized = Math.max(0, Math.min(299, value));
            if (normalized != state.speed) {
                state.speed = normalized;
                return true;
            }
        }
        return false;
    }

    private List<HudState.Lane> parseLanes(Object root) {
        List<HudState.Lane> lanes = new ArrayList<>();
        JSONObject laneInfo = findObjectWith(root, "itemList");
        if (laneInfo == null) {
            return lanes;
        }
        JSONArray items = array(laneInfo.opt("itemList"));
        if (items == null) {
            return lanes;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = object(items.opt(i));
            String resource = item == null ? null : laneResource(item);
            if (resource == null) {
                lanes.clear();
                return lanes;
            }
            lanes.add(new HudState.Lane(resource));
        }
        return lanes;
    }

    private String laneResource(JSONObject item) {
        JSONObject typeInfo = object(item.opt("laneTypeInfo"));
        int laneType = typeInfo == null ? 1 : integer(typeInfo, "laneType", 1);
        boolean bright = (typeInfo != null && bool(typeInfo, "isBright", false)) || anyRecommended(item);
        if (laneType == 2 || laneType == 3) return "ic_navi_lane_bus_for_" + (bright ? "bus" : "null");
        if (laneType == 5) return bright ? "ic_navi_lane_hov" : "ic_navi_lane_hov_for_null";
        if (laneType == 6) return bright ? "ic_navi_lane_tidal_text" : "ic_navi_lane_tidal_text_for_null";
        if (laneType == 7) return bright ? "ic_navi_lane_tidal_front" : "ic_navi_lane_tidal_front_for_null";
        if (laneType == 8) return bright ? "ic_navi_lane_tidal_available" : "ic_navi_lane_tidal_available_for_null";

        int variation = integer(item, "laneVariationType", 0);
        if (variation == 1 || variation == 2) {
            return bright ? "ic_navi_lane_variable_for_variable" : "ic_navi_lane_variable_for_null";
        }

        JSONArray directions = array(item.opt("directionList"));
        if (directions == null || directions.length() == 0) return null;
        boolean[] present = new boolean[5];
        boolean[] recommended = new boolean[5];
        int count = 0;
        for (int i = 0; i < directions.length(); i++) {
            JSONObject direction = object(directions.opt(i));
            if (direction == null) return null;
            int value = integer(direction, "direction", 0);
            int index = directionIndex(value);
            if (index == 0) continue;
            if (!present[index]) count++;
            present[index] = true;
            recommended[index] |= bool(direction, "isRecommend", false)
                    || integer(direction, "validType", 0) == 1;
        }
        if (count == 0) return null;

        String[] code = {"", "ah", "le", "ri", "lu"};
        StringBuilder prefix = new StringBuilder();
        int recommendedCount = 0;
        int recommendedIndex = 0;
        for (int i = 1; i <= 4; i++) {
            if (present[i]) {
                if (prefix.length() > 0) prefix.append('_');
                prefix.append(code[i]);
            }
            if (recommended[i]) {
                recommendedCount++;
                recommendedIndex = i;
            }
        }
        String suffix = recommendedCount == count ? "all"
                : recommendedCount == 1 ? code[recommendedIndex] : "null";
        if (count == 1) {
            String single = code[firstPresent(present)];
            if ("ah".equals(single)) return "ic_navi_lane_ahead_for_" + ("null".equals(suffix) ? "null" : "ahead");
            if ("le".equals(single)) return "ic_navi_lane_left_for_" + ("null".equals(suffix) ? "null" : "left");
            if ("ri".equals(single)) return "ic_navi_lane_right_for_" + ("null".equals(suffix) ? "null" : "right");
            return "ic_navi_lane_lu_for_" + ("null".equals(suffix) ? "null" : "lu");
        }
        return "ic_navi_lane_" + prefix + "_for_" + suffix;
    }

    private boolean anyRecommended(JSONObject item) {
        JSONArray directions = array(item.opt("directionList"));
        if (directions == null) return false;
        for (int i = 0; i < directions.length(); i++) {
            JSONObject direction = object(directions.opt(i));
            if (direction != null && (bool(direction, "isRecommend", false)
                    || integer(direction, "validType", 0) == 1)) return true;
        }
        return false;
    }

    private int directionIndex(int value) {
        if (value == 1) return 1;
        if (value == 2 || value == 5) return 2;
        if (value == 3 || value == 6) return 3;
        if (value == 4 || value == 7) return 4;
        return 0;
    }

    private int firstPresent(boolean[] values) {
        for (int i = 1; i < values.length; i++) if (values[i]) return i;
        return 0;
    }

    private byte[] findImageBytes(Object root) {
        byte[] background = bytes(find(root, "backgroundMapBytes"));
        byte[] arrow = bytes(find(root, "arrowMapBytes"));
        byte[] merged = mergeImages(background, arrow);
        if (merged != null && merged.length > 0) return merged;
        for (String key : new String[]{"imageBytes", "backgroundMapBytes", "arrowMapBytes"}) {
            Object value = find(root, key);
            byte[] bytes = bytes(value);
            if (bytes != null && bytes.length > 0) return bytes;
        }
        return null;
    }

    private byte[] mergeImages(byte[] background, byte[] arrow) {
        if (background == null || background.length == 0 || arrow == null || arrow.length == 0) {
            return null;
        }
        Bitmap base = BitmapFactory.decodeByteArray(background, 0, background.length);
        Bitmap overlay = BitmapFactory.decodeByteArray(arrow, 0, arrow.length);
        if (base == null || overlay == null) {
            if (base != null) base.recycle();
            if (overlay != null) overlay.recycle();
            return null;
        }
        Bitmap output = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawBitmap(base, 0, 0, null);
        if (overlay.getWidth() == base.getWidth() && overlay.getHeight() == base.getHeight()) {
            canvas.drawBitmap(overlay, 0, 0, null);
        } else {
            canvas.drawBitmap(overlay, null,
                    new android.graphics.Rect(0, 0, base.getWidth(), base.getHeight()), null);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        output.compress(Bitmap.CompressFormat.PNG, 100, out);
        base.recycle();
        overlay.recycle();
        output.recycle();
        return out.toByteArray();
    }

    private byte[] bytes(Object value) {
        if (value instanceof String) {
            try {
                return Base64.decode((String) value, Base64.DEFAULT);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        JSONArray array = array(value);
        if (array == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(array.length());
        for (int i = 0; i < array.length(); i++) out.write(array.optInt(i) & 0xff);
        return out.toByteArray();
    }

    private boolean sameLanes(List<HudState.Lane> left, List<HudState.Lane> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).resourceName.equals(right.get(i).resourceName)) return false;
        }
        return true;
    }

    private interface StringSetter { void set(String value); }

    private boolean setString(JSONObject object, String key, String old, StringSetter setter) {
        String value = string(object, key, "");
        if (!value.isEmpty() && !value.equals(old)) {
            setter.set(value);
            return true;
        }
        return false;
    }

    private JSONObject findObjectWith(Object value, String key) {
        return findObjectWithAny(value, key);
    }

    private JSONObject findObjectWithAny(Object value, String... keys) {
        value = unwrap(value);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : keys) if (object.has(key)) return object;
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                JSONObject found = findObjectWithAny(object.opt(names.optString(i)), keys);
                if (found != null) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findObjectWithAny(array.opt(i), keys);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Object find(Object value, String key) {
        value = unwrap(value);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has(key)) return object.opt(key);
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                Object found = find(object.opt(names.optString(i)), key);
                if (found != null) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                Object found = find(array.opt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean hasKey(Object value, String key) { return find(value, key) != null; }

    private Object unwrap(Object value) {
        if (!(value instanceof String)) return value;
        String text = ((String) value).trim();
        try {
            if (text.startsWith("{")) return new JSONObject(text);
            if (text.startsWith("[")) return new JSONArray(text);
        } catch (JSONException ignored) {
        }
        return value;
    }

    private JSONObject object(Object value) {
        value = unwrap(value);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    private JSONArray array(Object value) {
        value = unwrap(value);
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    private String string(JSONObject object, String key, String fallback) {
        Object value = object.opt(key);
        return value == null || value == JSONObject.NULL ? fallback : String.valueOf(value);
    }

    private int integer(JSONObject object, String key, int fallback) {
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) try { return Integer.parseInt((String) value); } catch (NumberFormatException ignored) { }
        return fallback;
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private boolean bool(JSONObject object, String key, boolean fallback) {
        Boolean value = asBoolean(object.opt(key));
        return value == null ? fallback : value;
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value instanceof String) {
            if ("true".equalsIgnoreCase((String) value)) return true;
            if ("false".equalsIgnoreCase((String) value)) return false;
        }
        return null;
    }
}
