package dev.codex.voyahhud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.Locale;

final class HudRenderer {
    static final int WIDTH = 760;
    static final int HEIGHT = 320;

    private final Context context;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    HudRenderer(Context context) {
        this.context = context;
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    Bitmap render(HudState state) {
        Bitmap output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT);

        int saved = canvas.save();

        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(Color.argb(232, 3, 7, 10));
        canvas.drawRoundRect(new RectF(0, 0, WIDTH, HEIGHT), 8, 8, background);

        if (HudSettings.enabled(context, HudSettings.TURN_ARROW)) {
            drawElement(canvas, HudSettings.TURN_ARROW, 68, 64,
                    () -> drawAsset(canvas, turnResource(state), new RectF(18, 14, 118, 114)));
        }
        boolean enlargeVisible = HudSettings.enabled(context, HudSettings.ENLARGE)
                && state.enlargeImage != null;
        if (HudSettings.enabled(context, HudSettings.MANEUVER_DISTANCE)) {
            drawElement(canvas, HudSettings.MANEUVER_DISTANCE, 105, 145,
                    () -> drawManeuverDistance(canvas, state));
        }
        if (HudSettings.enabled(context, HudSettings.ROADS)) {
            drawElement(canvas, HudSettings.ROADS, 125, 220,
                    () -> drawRoads(canvas, state, enlargeVisible));
        }
        if (enlargeVisible) {
            drawElement(canvas, HudSettings.ENLARGE, 488, 128,
                    () -> drawEnlargeImage(canvas, state.enlargeImage));
        }
        if (!state.lanes.isEmpty() && HudSettings.enabled(context, HudSettings.LANES)) {
            drawElement(canvas, HudSettings.LANES, 380, 265,
                    () -> drawLanes(canvas, state, 235));
        }
        if (state.trafficLightColor > 0 && HudSettings.enabled(context, HudSettings.TRAFFIC_LIGHT)) {
            drawElement(canvas, HudSettings.TRAFFIC_LIGHT, 380, 210,
                    () -> drawTrafficLight(canvas, state));
        }
        if (HudSettings.enabled(context, HudSettings.REMAINING)) {
            drawElement(canvas, HudSettings.REMAINING, 150, 300,
                    () -> drawRemaining(canvas, state.totalDistance, state.totalTimeSeconds));
        }
        if (HudSettings.enabled(context, HudSettings.SPEED)) {
            drawElement(canvas, HudSettings.SPEED, 380, 100, () -> drawSpeed(canvas, state.speed));
        }
        if (HudSettings.enabled(context, HudSettings.SPEED_LIMIT)) {
            drawElement(canvas, HudSettings.SPEED_LIMIT, 700, 50,
                    () -> drawSpeedLimit(canvas, state.speedLimit));
        }
        canvas.restoreToCount(saved);
        return output;
    }

    private void drawManeuverDistance(Canvas canvas, HudState state) {
        if (!state.immediateGuideText.isEmpty()) return;
        int meters = state.maneuverDistance;
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(52);
        String value = !state.maneuverDistanceText.isEmpty() ? state.maneuverDistanceText
                : meters < 0 ? "--" : meters >= 1000
                ? String.format(Locale.US, "%.1f", meters / 1000f) : String.valueOf(meters);
        canvas.drawText(value, 24, 158, textPaint);
        float valueWidth = textPaint.measureText(value);
        textPaint.setTextSize(25);
        String unit = !state.maneuverDistanceText.isEmpty() ? state.maneuverDistanceUnit
                : meters >= 1000 ? "km" : "m";
        canvas.drawText(unit, 24 + valueWidth + 12, 158, textPaint);
    }

    private void drawRoads(Canvas canvas, HudState state, boolean enlargeVisible) {
        float width = 245;
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(25);
        String action = !state.immediateGuideText.isEmpty() ? state.immediateGuideText
                : state.direction.isEmpty() ? "\u8fdb\u5165" : state.direction;
        canvas.drawText(ellipsize(action, width), 24, 194, textPaint);

        textPaint.setTextSize(35);
        String next = !state.immediateGuideText.isEmpty() ? ""
                : state.nextRoad.isEmpty() ? "\u65e0\u540d\u8def" : state.nextRoad;
        canvas.drawText(ellipsize(next, width), 24, 230, textPaint);

        textPaint.setColor(Color.rgb(190, 205, 215));
        textPaint.setTextSize(23);
        String current = "\u5f53\u524d  " + (state.currentRoad.isEmpty() ? "--" : state.currentRoad);
        canvas.drawText(ellipsize(current, width), 24, 260, textPaint);
    }

    private void drawTrafficLight(Canvas canvas, HudState state) {
        int color = state.trafficLightColor == 1 ? Color.rgb(255, 55, 55)
                : state.trafficLightColor == 2 ? Color.rgb(255, 190, 20)
                : Color.rgb(45, 235, 110);
        Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
        light.setColor(color);
        canvas.drawCircle(352, 212, 15, light);
        textPaint.setColor(color);
        textPaint.setTextSize(31);
        canvas.drawText(state.trafficLightSeconds + "s", 378, 222, textPaint);
    }

    private void drawEnlargeImage(Canvas canvas, byte[] bytes) {
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (image == null) return;
        Rect source = new Rect(0, 0, image.getWidth(), image.getHeight());
        RectF target = new RectF(390, 70, 585, 185);
        canvas.drawBitmap(image, source, fitCenter(target, image.getWidth(), image.getHeight()), bitmapPaint);
        image.recycle();
    }

    private void drawLanes(Canvas canvas, HudState state, float top) {
        int count = state.lanes.size();
        float available = 350;
        float size = Math.min(60, available / count);
        float start = 205 + (available - size * count) / 2f;
        for (int i = 0; i < count; i++) {
            drawAsset(canvas, state.lanes.get(i).resourceName,
                    new RectF(start + i * size, top, start + (i + 1) * size, top + size));
        }
    }

    private void drawRemaining(Canvas canvas, int meters, int seconds) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(23);
        String distance = meters < 0 ? "--"
                : meters >= 1000 ? String.format(Locale.US, "%.1f km", meters / 1000f) : meters + " m";
        String time = seconds < 0 ? "--"
                : seconds >= 3600 ? (seconds / 3600) + " h " + ((seconds % 3600) / 60) + " min"
                : Math.max(1, seconds / 60) + " min";
        canvas.drawText(distance + "  |  " + time, 24, 306, textPaint);
    }

    private void drawSpeed(Canvas canvas, int speed) {
        if (speed < 0) return;
        int value = Math.max(0, Math.min(speed < 0 ? 0 : speed, 299));
        String digits = String.valueOf(value);
        Bitmap[] bitmaps = new Bitmap[digits.length()];
        float[] widths = new float[digits.length()];
        float height = 70f;
        // Source digit PNGs already contain transparent side bearings.
        float gap = 0f;
        float totalWidth = gap * Math.max(0, digits.length() - 1);
        for (int i = 0; i < digits.length(); i++) {
            bitmaps[i] = loadAsset("speed_" + digits.charAt(i));
            if (bitmaps[i] != null) {
                widths[i] = height * bitmaps[i].getWidth() / bitmaps[i].getHeight();
                totalWidth += widths[i];
            }
        }
        float left = 380 - totalWidth / 2f;
        for (int i = 0; i < bitmaps.length; i++) {
            Bitmap bitmap = bitmaps[i];
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null,
                        new RectF(left, 65, left + widths[i], 65 + height), bitmapPaint);
                left += widths[i] + gap;
                bitmap.recycle();
            }
        }
    }

    private void drawElement(Canvas canvas, String contentKey, float defaultCenterX, float defaultCenterY, Drawing drawing) {
        int saved = canvas.save();
        float scale = HudSettings.elementScale(context, contentKey);
        float dx = HudSettings.positionX(context, contentKey) - defaultCenterX;
        float dy = HudSettings.positionY(context, contentKey) - defaultCenterY;
        canvas.translate(dx, dy);
        canvas.scale(scale, scale, defaultCenterX, defaultCenterY);
        drawing.draw();
        canvas.restoreToCount(saved);
    }

    private interface Drawing {
        void draw();
    }

    private void drawSpeedLimit(Canvas canvas, int limit) {
        if (limit <= 0) return;
        drawAsset(canvas, "speed_limit_bg", new RectF(670, 20, 730, 80));
        String digits = String.valueOf(Math.min(limit, 199));
        Bitmap[] bitmaps = new Bitmap[digits.length()];
        float[] widths = new float[digits.length()];
        float height = 30f;
        float gap = 0f;
        float totalWidth = gap * Math.max(0, digits.length() - 1);
        for (int i = 0; i < digits.length(); i++) {
            bitmaps[i] = loadAsset("speed_limit_" + digits.charAt(i));
            if (bitmaps[i] != null) {
                widths[i] = height * bitmaps[i].getWidth() / bitmaps[i].getHeight();
                totalWidth += widths[i];
            }
        }
        float left = 700 - totalWidth / 2f;
        for (int i = 0; i < bitmaps.length; i++) {
            Bitmap bitmap = bitmaps[i];
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null,
                        new RectF(left, 35, left + widths[i], 35 + height), bitmapPaint);
                left += widths[i] + gap;
                bitmap.recycle();
            }
        }
    }

    private Bitmap loadAsset(String name) {
        if (name == null || name.isEmpty()) return null;
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        return id == 0 ? null : BitmapFactory.decodeResource(context.getResources(), id);
    }

    private void drawAsset(Canvas canvas, String name, RectF target) {
        Bitmap bitmap = loadAsset(name);
        if (bitmap == null) return;
        canvas.drawBitmap(bitmap, null, fitCenter(target, bitmap.getWidth(), bitmap.getHeight()), bitmapPaint);
        bitmap.recycle();
    }

    private RectF fitCenter(RectF bounds, int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || bounds.width() <= 0 || bounds.height() <= 0) {
            return new RectF(bounds);
        }
        float scale = Math.min(bounds.width() / sourceWidth, bounds.height() / sourceHeight);
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        float left = bounds.centerX() - width / 2f;
        float top = bounds.centerY() - height / 2f;
        return new RectF(left, top, left + width, top + height);
    }

    private String turnResource(HudState state) {
        if (state.turnIconResource != null && !state.turnIconResource.isEmpty()) {
            return state.turnIconResource;
        }
        return null;
    }

    private String ellipsize(String text, float width) {
        if (textPaint.measureText(text) <= width) return text;
        String suffix = "...";
        float available = width - textPaint.measureText(suffix);
        int end = text.length();
        while (end > 0 && textPaint.measureText(text, 0, end) > available) end--;
        return text.substring(0, end) + suffix;
    }
}
