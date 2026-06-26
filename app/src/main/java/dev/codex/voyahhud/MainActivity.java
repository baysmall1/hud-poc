package dev.codex.voyahhud;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int ACCENT = Color.rgb(36, 205, 180);
    private static final int TEXT = Color.rgb(238, 246, 248);
    private static final int MUTED = Color.rgb(165, 188, 195);
    private static final int WARNING = Color.rgb(255, 174, 66);
    private SharedPreferences hudPreferences;
    private final Map<String, TextView> boundaryViews = new HashMap<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener boundaryListener =
            (preferences, key) -> runOnUiThread(this::updateBoundaryWarnings);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8, 17, 21));
        getWindow().setNavigationBarColor(Color.rgb(8, 17, 21));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 39);
        }
        Intent service = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);

        HudSettings.migrateFromV46(this);
        SharedPreferences preferences = HudSettings.preferences(this);
        hudPreferences = preferences;
        hudPreferences.registerOnSharedPreferenceChangeListener(boundaryListener);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(36));
        root.setBackgroundColor(Color.rgb(8, 17, 21));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(28), dp(16), dp(28), dp(16));
        header.setBackground(rounded(Color.rgb(15, 35, 41), 22));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("岚图追光&百度地图V21 HUD投射", 34, TEXT);
        title.setSingleLine(false);
        identity.addView(title);
        TextView author = text("By 非洲小白脸", 22, ACCENT);
        author.setPadding(0, dp(4), 0, 0);
        identity.addView(author);
        header.addView(identity, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = text("版本 " + versionName() + "  ·  桥接服务运行中", 22, MUTED);
        status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        root.addView(header, matchWrap(0, 12));

        Button resetAll = new Button(this);
        resetAll.setText("一键恢复全部默认位置与大小");
        resetAll.setTextSize(25);
        resetAll.setTextColor(TEXT);
        resetAll.setAllCaps(false);
        resetAll.setMinHeight(dp(70));
        resetAll.setOnClickListener(view -> HudSettings.resetAllLayout(this));
        root.addView(resetAll, matchWrap(0, 12));

        addContentCard(root, preferences, "转向箭头", HudSettings.TURN_ARROW);
        addContentCard(root, preferences, "前方转向距离", HudSettings.MANEUVER_DISTANCE);
        addContentCard(root, preferences, "道路与动作文字", HudSettings.ROADS);
        addContentCard(root, preferences, "路口放大图", HudSettings.ENLARGE);
        addContentCard(root, preferences, "车道信息", HudSettings.LANES);
        addContentCard(root, preferences, "红绿灯颜色与倒计时", HudSettings.TRAFFIC_LIGHT);
        addContentCard(root, preferences, "剩余里程与时间", HudSettings.REMAINING);
        addContentCard(root, preferences, "当前车速（仅数字）", HudSettings.SPEED);
        addContentCard(root, preferences, "道路限速", HudSettings.SPEED_LIMIT);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 17, 21));
        scroll.addView(root);
        setContentView(scroll);
    }

    private void addContentCard(LinearLayout root, SharedPreferences preferences,
                                String label, String contentKey) {
        EditText xInput = coordinateInput(preferences, HudSettings.xKey(contentKey),
                HudSettings.defaultX(contentKey), HudRenderer.WIDTH);
        EditText yInput = coordinateInput(preferences, HudSettings.yKey(contentKey),
                HudSettings.defaultY(contentKey), HudRenderer.HEIGHT);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(24), dp(12), dp(24), dp(12));
        card.setMinimumHeight(dp(208));
        card.setBackground(rounded(Color.rgb(17, 31, 37), 18));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(0, 0, dp(20), 0);
        TextView contentLabel = text(label, 27, TEXT);
        info.addView(contentLabel, matchWrap(0, 8));
        int maximum = HudSettings.SPEED.equals(contentKey) ? 400 : 150;
        info.addView(createScaleControl(preferences, HudSettings.sizeKey(contentKey),
                50, maximum, HudSettings.defaultElementPercent(contentKey)), matchWrap(0, 0));
        TextView boundary = text("", 21, WARNING);
        boundary.setPadding(dp(8), dp(4), dp(8), 0);
        boundaryViews.put(contentKey, boundary);
        info.addView(boundary);
        card.addView(info, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        card.addView(createPositionControl(preferences, contentKey, xInput, yInput),
                new LinearLayout.LayoutParams(dp(540), LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(createNudgePad(preferences, contentKey, xInput, yInput),
                new LinearLayout.LayoutParams(dp(252), dp(156)));

        ToggleButton toggle = new ToggleButton(this);
        toggle.setChecked(preferences.getBoolean(contentKey, true));
        toggle.setTextOn("已开启");
        toggle.setTextOff("已关闭");
        toggle.setText(toggle.isChecked() ? toggle.getTextOn() : toggle.getTextOff());
        toggle.setTextSize(27);
        toggle.setTextColor(TEXT);
        toggle.setAllCaps(false);
        toggle.setGravity(Gravity.CENTER);
        toggle.setMinimumWidth(dp(240));
        toggle.setMinHeight(dp(78));
        toggle.setPadding(dp(24), 0, dp(24), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            toggle.setBackgroundTintList(new ColorStateList(
                    new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                    new int[] { Color.rgb(20, 126, 111), Color.rgb(68, 79, 84) }));
        }
        toggle.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(contentKey, checked).apply());
        contentLabel.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
        contentLabel.setContentDescription(label + "显示开关");

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(18), 0, 0, 0);
        actions.addView(toggle, new LinearLayout.LayoutParams(dp(240), dp(90)));
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(dp(240), dp(72));
        resetParams.setMargins(0, dp(8), 0, 0);
        actions.addView(createResetButton(preferences, contentKey, xInput, yInput), resetParams);
        card.addView(actions, new LinearLayout.LayoutParams(dp(258),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        updateBoundaryWarning(contentKey);
        root.addView(card, matchWrap(0, 12));
    }

    private View createPositionControl(SharedPreferences preferences, String contentKey,
                                       EditText xInput, EditText yInput) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(5), 0, 0);

        group.addView(createAxisRow("X 坐标", xInput, preferences,
                HudSettings.xKey(contentKey), HudRenderer.WIDTH));
        group.addView(createAxisRow("Y 坐标", yInput, preferences,
                HudSettings.yKey(contentKey), HudRenderer.HEIGHT));
        return group;
    }

    private Button createResetButton(SharedPreferences preferences, String contentKey,
                                     EditText xInput, EditText yInput) {
        Button reset = new Button(this);
        reset.setText("恢复默认位置");
        reset.setTextSize(24);
        reset.setTextColor(TEXT);
        reset.setAllCaps(false);
        reset.setMinHeight(dp(72));
        reset.setOnClickListener(view -> {
            int x = HudSettings.defaultX(contentKey);
            int y = HudSettings.defaultY(contentKey);
            preferences.edit().putInt(HudSettings.xKey(contentKey), x)
                    .putInt(HudSettings.yKey(contentKey), y).apply();
            xInput.setText(String.valueOf(x));
            yInput.setText(String.valueOf(y));
        });
        return reset;
    }

    private View createNudgePad(SharedPreferences preferences, String contentKey,
                                EditText xInput, EditText yInput) {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER);
        top.addView(nudgeButton("↑", yInput, preferences, HudSettings.yKey(contentKey),
                -1, HudRenderer.HEIGHT));
        pad.addView(top, new LinearLayout.LayoutParams(dp(252), dp(76)));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.addView(nudgeButton("←", xInput, preferences, HudSettings.xKey(contentKey),
                -1, HudRenderer.WIDTH));
        bottom.addView(nudgeButton("↓", yInput, preferences, HudSettings.yKey(contentKey),
                1, HudRenderer.HEIGHT));
        bottom.addView(nudgeButton("→", xInput, preferences, HudSettings.xKey(contentKey),
                1, HudRenderer.WIDTH));
        pad.addView(bottom, new LinearLayout.LayoutParams(dp(252), dp(76)));
        pad.setContentDescription("上下左右微调位置，每次 1 像素");
        return pad;
    }

    private Button nudgeButton(String label, EditText input, SharedPreferences preferences,
                               String key, int delta, int maximum) {
        Button button = stepButton(label, input, preferences, key, delta, maximum);
        button.setTextSize(30);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(78), dp(72));
        params.setMargins(dp(3), dp(2), dp(3), dp(2));
        button.setLayoutParams(params);
        return button;
    }

    private View createAxisRow(String label, EditText input, SharedPreferences preferences,
                               String key, int maximum) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView axisLabel = text(label, 22, MUTED);
        row.addView(axisLabel, new LinearLayout.LayoutParams(dp(130), dp(87)));
        row.addView(stepButton("−5", input, preferences, key, -5, maximum));
        row.addView(input, new LinearLayout.LayoutParams(dp(188), dp(87)));
        row.addView(stepButton("+5", input, preferences, key, 5, maximum));
        return row;
    }

    private Button stepButton(String label, EditText input, SharedPreferences preferences,
                              String key, int delta, int maximum) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(24);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(96), dp(72)));
        button.setOnClickListener(view -> {
            int current = 0;
            try { current = Integer.parseInt(input.getText().toString()); }
            catch (NumberFormatException ignored) { }
            int adjusted = Math.max(0, Math.min(maximum, current + delta));
            input.setText(String.valueOf(adjusted));
            preferences.edit().putInt(key, adjusted).apply();
        });
        return button;
    }

    private void updateBoundaryWarnings() {
        for (String key : boundaryViews.keySet()) updateBoundaryWarning(key);
    }

    private void updateBoundaryWarning(String contentKey) {
        TextView view = boundaryViews.get(contentKey);
        if (view == null) return;
        String warning = HudSettings.boundaryWarning(this, contentKey);
        view.setText(warning);
        view.setVisibility(warning.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private EditText coordinateInput(SharedPreferences preferences, String key,
                                     int defaultValue, int maximum) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(30);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setGravity(Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(true);
        input.setText(String.valueOf(Math.max(0, Math.min(maximum,
                preferences.getInt(key, defaultValue)))));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                if (value.length() == 0) return;
                try {
                    int parsed = Integer.parseInt(value.toString());
                    if (parsed >= 0 && parsed <= maximum) preferences.edit().putInt(key, parsed).apply();
                } catch (NumberFormatException ignored) { }
            }
        });
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) return;
            int parsed = defaultValue;
            try { parsed = Integer.parseInt(input.getText().toString()); }
            catch (NumberFormatException ignored) { }
            parsed = Math.max(0, Math.min(maximum, parsed));
            input.setText(String.valueOf(parsed));
            preferences.edit().putInt(key, parsed).apply();
        });
        return input;
    }

    private View createScaleControl(SharedPreferences preferences, String key,
                                    int minimum, int maximum, int defaultValue) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        int value = Math.max(minimum, Math.min(maximum, preferences.getInt(key, defaultValue)));
        TextView valueView = text("大小：" + value + "%", 24, ACCENT);
        valueView.setPadding(dp(8), 0, 0, 0);
        group.addView(valueView);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(maximum - minimum);
        seekBar.setProgress(value - minimum);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            seekBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            seekBar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int selected = progress + minimum;
                valueView.setText("大小：" + selected + "%");
                if (fromUser) preferences.edit().putInt(key, selected).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        group.addView(seekBar, matchWrap(0, 0));
        return group;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), Color.rgb(35, 63, 69));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    @Override protected void onDestroy() {
        if (hudPreferences != null) hudPreferences.unregisterOnSharedPreferenceChangeListener(boundaryListener);
        super.onDestroy();
    }
}
