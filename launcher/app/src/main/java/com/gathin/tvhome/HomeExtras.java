package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * This fork's additions on Home (see Extras): weather in the clock row, the "Up next" sports row under the dock and
 * the Inputs pill as a one-press HDMI button. The feeds are fetched on one background thread only while Home is in
 * front (tick() runs with the minute tick), cached, and shown greyed out with "Update failed" when a refresh fails,
 * so one broken source never breaks Home.
 */
final class HomeExtras {
    private static final String TAG = "TvHomeExtras";
    private static final int ACCENT = 0xFFF2A93B;
    private static final long WEATHER_EVERY = 30 * 60_000L, SPORTS_EVERY = 6 * 3600_000L, RETRY_AFTER = 15 * 60_000L;
    /** Height of the "Up next" row: its label, the gap and a card. */
    private static final float ROW_DP = 128, CARD_DP = 98;

    private final Activity activity;
    private final Extras x;
    private final ViewGroup root;
    private final RecyclerView grid;
    private final int columns;
    private final float density;
    private final boolean sportsOn;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> inFlight = new HashSet<>();

    private ImageView weatherIcon;
    private TextView weatherTemp;
    private View weatherBlock;
    private LinearLayout panel;
    private final List<Card> cards = new ArrayList<>();

    HomeExtras(Activity activity, Extras x, ViewGroup root, RecyclerView grid, int columns, float density, boolean dockRow) {
        this.activity = activity;
        this.x = x;
        this.root = root;
        this.grid = grid;
        this.columns = columns;
        this.density = density;
        this.sportsOn = x.sportsRow && dockRow;
        this.prefs = activity.getSharedPreferences("extras", Activity.MODE_PRIVATE);
        if (x.weather) buildWeather();
        if (sportsOn) buildPanel();
    }

    // ---- Inputs pill -----------------------------------------------------------------------------------

    /** inputs_pill_port: OK switches straight to that HDMI port; hold OK still opens the inputs menu. */
    void setupInputsPill(TextView pill, Consumer<String> openInput, Runnable menu) {
        if (pill == null || x.inputsPillPort <= 0) return;
        if (!x.inputsPillLabel.isEmpty()) pill.setText(x.inputsPillLabel);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_gamepad, 0, 0, 0);
        pill.setOnClickListener(v -> {
            String id = hdmiInput(x.inputsPillPort);
            if (id != null) {
                openInput.accept(id);
            } else {
                Toast.makeText(activity, activity.getString(R.string.input_not_found, x.inputsPillPort), Toast.LENGTH_SHORT).show();
                menu.run();
            }
        });
        pill.setOnLongClickListener(v -> {
            menu.run();
            return true;
        });
    }

    /** The input id of HDMI port {@code port}: the port whose label ends in that number, else the port-th by id. */
    private String hdmiInput(int port) {
        TvInputManager tim = (TvInputManager) activity.getSystemService(Activity.TV_INPUT_SERVICE);
        if (tim == null) return null;
        List<TvInputInfo> all;
        try {
            all = tim.getTvInputList();
        } catch (RuntimeException e) {
            return null;
        }
        Set<String> ids = new HashSet<>();
        for (TvInputInfo info : all) ids.add(info.getId());
        List<TvInputInfo> ports = new ArrayList<>();
        for (TvInputInfo info : all) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
            // A CEC device (the PS5 itself) is a child of its port; switch to the port.
            if (info.getParentId() != null && ids.contains(info.getParentId())) continue;
            ports.add(info);
        }
        for (TvInputInfo info : ports) {
            CharSequence label = info.loadLabel(activity);
            if (label != null && label.toString().trim().matches("(?i).*\\D" + port)) return info.getId();
        }
        ports.sort((a, b) -> a.getId().compareTo(b.getId()));
        return port <= ports.size() ? ports.get(port - 1).getId() : null;
    }

    // ---- room under the dock ---------------------------------------------------------------------------

    /** Space the "Up next" row takes under the dock; MainActivity pushes the dock up by this much. */
    int sportsRoomPx() {
        return sportsOn ? (int) (ROW_DP * density) : 0;
    }

    /** Pushes the first grid row down under the "Up next" row, so the two never overlap. */
    RecyclerView.ItemDecoration roomDecoration() {
        return new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(view);
                if (pos >= columns && pos < 2 * columns) out.top += sportsRoomPx();
            }
        };
    }

    // ---- refresh ---------------------------------------------------------------------------------------

    /** On resume and every minute while Home is in front: refresh what is due, then redraw. */
    void tick() {
        long now = System.currentTimeMillis();
        if (x.weather && due("weather", WEATHER_EVERY, now, false)) {
            fetch("weather", () -> WeatherFeed.fetch(x.weatherLatitude, x.weatherLongitude).toJson());
        }
        if (sportsOn) {
            Instant instant = Instant.ofEpochMilli(now);
            if (due(SportsFeed.F1, SPORTS_EVERY, now, over(SportsFeed.F1, instant))) {
                fetch(SportsFeed.F1, () -> json(SportsFeed.f1()));
            }
            if (due(SportsFeed.MOTOGP, SPORTS_EVERY, now, over(SportsFeed.MOTOGP, instant))) {
                fetch(SportsFeed.MOTOGP, () -> json(SportsFeed.motogp()));
            }
            if (!x.sportsTeamIcs.isEmpty() && due(SportsFeed.TEAM, SPORTS_EVERY, now, over(SportsFeed.TEAM, instant))) {
                fetch(SportsFeed.TEAM, () -> json(SportsFeed.team(x.sportsTeamIcs, x.sportsTeam)));
            }
        }
        render();
    }

    private interface Job {
        String run() throws Exception;
    }

    private static String json(SportsFeed.Event e) {
        // No event (the season is over) is stored as an empty value, so the card hides instead of failing.
        return e == null ? "" : e.toJson();
    }

    private boolean over(String key, Instant now) {
        SportsFeed.Event e = SportsFeed.Event.fromJson(prefs.getString(key, null));
        return e != null && e.over(now);
    }

    /** Due when never fetched, when the data is older than {@code every} or already over, and not just tried. */
    private boolean due(String key, long every, long now, boolean stale) {
        if (inFlight.contains(key)) return false;
        long tried = prefs.getLong(key + ".tried", 0);
        if (now - tried < RETRY_AFTER && tried <= now) return false;
        long ok = prefs.getLong(key + ".ok", 0);
        return !prefs.contains(key) || stale || now - ok >= every || ok > now;
    }

    private void fetch(String key, Job job) {
        inFlight.add(key);
        prefs.edit().putLong(key + ".tried", System.currentTimeMillis()).apply();
        worker.execute(() -> {
            String value = null;
            try {
                value = job.run();
            } catch (Exception e) {
                Log.w(TAG, key + " refresh failed", e);
            }
            final String result = value;
            root.post(() -> {
                inFlight.remove(key);
                SharedPreferences.Editor ed = prefs.edit();
                if (result != null) {
                    ed.putString(key, result).putLong(key + ".ok", System.currentTimeMillis()).putBoolean(key + ".failed", false);
                } else {
                    ed.putBoolean(key + ".failed", true);
                }
                ed.apply();
                render();
            });
        });
    }

    // ---- weather -------------------------------------------------------------------------------------

    /** Divider, icon, "24°" and the place, at the right end of the clock row. */
    private void buildWeather() {
        View date = activity.findViewById(R.id.date);
        if (date == null || !(date.getParent() instanceof View)) return;
        View dateColumn = (View) date.getParent();
        if (!(dateColumn.getParent() instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) dateColumn.getParent();

        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.HORIZONTAL);
        block.setGravity(Gravity.CENTER_VERTICAL);
        block.setFocusable(false);
        block.setVisibility(View.GONE);

        View divider = new View(activity);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(1), dp(26));
        dlp.setMarginEnd(dp(16));
        block.addView(divider, dlp);

        weatherIcon = new ImageView(activity);
        block.addView(weatherIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        weatherTemp = text(22, R.font.outfit_light, 0xFFFFFFFF, 0f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(dp(8));
        block.addView(weatherTemp, tlp);

        if (!x.weatherPlace.isEmpty()) {
            TextView place = text(12, R.font.outfit_regular, 0x8CFFFFFF, 0.18f);
            place.setAllCaps(true);
            place.setText(x.weatherPlace);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.setMarginStart(dp(8));
            block.addView(place, plp);
        }

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        blp.bottomMargin = dp(6);
        blp.setMarginStart(dp(16));
        row.addView(block, blp);
        weatherBlock = block;
    }

    private void renderWeather() {
        if (weatherBlock == null) return;
        WeatherFeed.Now now = WeatherFeed.Now.fromJson(prefs.getString("weather", null));
        if (now == null) {
            weatherBlock.setVisibility(View.GONE);
            return;
        }
        weatherIcon.setImageResource(WeatherFeed.icon(now.code));
        weatherTemp.setText(now.tempC + "°");
        // An old reading is still shown, dimmed, rather than nothing.
        weatherBlock.setAlpha(prefs.getBoolean("weather.failed", false) ? 0.5f : 1f);
        weatherBlock.setVisibility(View.VISIBLE);
    }

    // ---- "Up next" ---------------------------------------------------------------------------------------

    private static final class Card {
        final String key;
        final String series;
        LinearLayout view;
        TextView title, detail;
        LinearLayout times;

        Card(String key, String series) {
            this.key = key;
            this.series = series;
        }
    }

    private void buildPanel() {
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setFocusable(false);
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.setAlpha(0f);

        TextView label = text(11, R.font.outfit_medium, 0x8CFFFFFF, 0.22f);
        label.setAllCaps(true);
        label.setText(R.string.up_next);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.setMarginStart(dp(4));
        llp.bottomMargin = dp(8);
        panel.addView(label, llp);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CARD_DP)));

        cards.add(new Card(SportsFeed.F1, activity.getString(R.string.series_f1)));
        cards.add(new Card(SportsFeed.MOTOGP, activity.getString(R.string.series_motogp)));
        if (!x.sportsTeamIcs.isEmpty()) cards.add(new Card(SportsFeed.TEAM, x.sportsTeam));
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            c.view = new LinearLayout(activity);
            c.view.setOrientation(LinearLayout.VERTICAL);
            c.view.setBackgroundResource(R.drawable.upnext_card);
            c.view.setPadding(dp(16), dp(12), dp(16), dp(12));
            TextView kicker = text(10, R.font.outfit_medium, 0x8CFFFFFF, 0.2f);
            kicker.setAllCaps(true);
            kicker.setText(c.series);
            c.view.addView(kicker);
            c.title = text(16, R.font.outfit_regular, 0xFFFFFFFF, 0f);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(4);
            c.view.addView(c.title, tlp);
            c.detail = text(11, R.font.outfit_light, 0xA6FFFFFF, 0f);
            c.view.addView(c.detail);
            c.times = new LinearLayout(activity);
            c.times.setOrientation(LinearLayout.HORIZONTAL);
            c.times.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            c.view.addView(c.times, wlp);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) clp.setMarginStart(dp(14));
            row.addView(c.view, clp);
        }

        // Beside the grid's tiles: the content's 56dp padding plus the grid's own 10dp.
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP;
        plp.setMarginStart(dp(66));
        plp.setMarginEnd(dp(66));
        // Under the glass highlight, which is the root's last child.
        root.addView(panel, Math.max(0, root.getChildCount() - 1), plp);

        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                place();
            }
        });
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());
    }

    /** Keeps the row just under the dock; it scrolls away with the dock when the grid moves up. */
    private void place() {
        if (panel == null) return;
        RecyclerView.ViewHolder dock = grid.findViewHolderForAdapterPosition(0);
        if (dock == null) {
            panel.setAlpha(0f);
            return;
        }
        int[] g = new int[2], r = new int[2];
        grid.getLocationInWindow(g);
        root.getLocationInWindow(r);
        float top = g[1] - r[1] + dock.itemView.getBottom() + AppAdapter.DOCK_EXTRA_DP * density / 2f + dp(10);
        panel.setY(top);
        // Fades out as it reaches the top of the grid, rather than drawing over the clock.
        float fade = (top - (g[1] - r[1])) / (40 * density);
        panel.setAlpha(hasContent ? Math.max(0f, Math.min(1f, fade)) : 0f);
    }

    private boolean hasContent;

    private void renderPanel() {
        if (panel == null) return;
        Instant now = Instant.now();
        hasContent = false;
        for (Card c : cards) {
            String raw = prefs.getString(c.key, null);
            boolean failed = prefs.getBoolean(c.key + ".failed", false);
            SportsFeed.Event e = SportsFeed.Event.fromJson(raw);
            c.times.removeAllViews();
            if (e == null) {
                // "" means the source has nothing coming up (the season is over): no card.
                boolean nothing = raw != null && raw.isEmpty() && !failed;
                c.view.setVisibility(nothing ? View.INVISIBLE : View.VISIBLE);
                c.title.setText("");
                c.detail.setText(failed ? activity.getString(R.string.update_failed) : "");
                c.view.setAlpha(failed ? 0.5f : 1f);
                continue;
            }
            hasContent = true;
            c.view.setVisibility(View.VISIBLE);
            c.title.setText(e.title);
            c.detail.setText(failed ? activity.getString(R.string.update_failed) : e.detail);
            c.view.setAlpha(failed ? 0.5f : 1f);
            boolean first = true;
            for (SportsFeed.Session s : e.sessions) {
                if (!now.isBefore(s.start.plus(s.live))) continue;   // already over
                addTime(c.times, s, now, first);
                first = false;
            }
        }
        if (!hasContent) hasContent = anyFailed();
        place();
    }

    private boolean anyFailed() {
        for (Card c : cards) if (prefs.getBoolean(c.key + ".failed", false)) return true;
        return false;
    }

    private void addTime(LinearLayout times, SportsFeed.Session s, Instant now, boolean first) {
        TextView label = text(12, R.font.outfit_regular, 0xA6FFFFFF, 0f);
        label.setText(sessionLabel(s.kind));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!first) llp.setMarginStart(dp(14));
        times.addView(label, llp);

        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMarginStart(dp(6));
        if (!now.isBefore(s.start)) {
            TextView live = text(10, R.font.outfit_medium, 0xFF000000, 0.08f);
            live.setText(R.string.live);
            live.setBackgroundResource(R.drawable.live_badge);
            live.setPadding(dp(6), dp(1), dp(6), dp(1));
            times.addView(live, vlp);
            return;
        }
        TextView when = text(12, R.font.outfit_medium, 0xFFFFFFFF, 0f);
        Duration until = Duration.between(now, s.start);
        if (until.compareTo(Duration.ofHours(24)) <= 0) {
            long minutes = (until.toMillis() + 59_999) / 60_000;   // never "in 0 m" before the start
            when.setText(minutes >= 60
                    ? activity.getString(R.string.in_hours_minutes, (int) (minutes / 60), (int) (minutes % 60))
                    : activity.getString(R.string.in_minutes, (int) minutes));
            when.setTextColor(ACCENT);
        } else {
            boolean h24 = android.text.format.DateFormat.is24HourFormat(activity);
            DateTimeFormatter f = DateTimeFormatter.ofPattern(h24 ? "EEE d MMM · HH:mm" : "EEE d MMM · h:mm a",
                    Locale.getDefault()).withZone(ZoneId.systemDefault());
            when.setText(f.format(s.start));
        }
        times.addView(when, vlp);
    }

    private String sessionLabel(String kind) {
        switch (kind) {
            case "sprint": return activity.getString(R.string.session_sprint);
            case "kickoff": return activity.getString(R.string.session_kickoff);
            default: return activity.getString(R.string.session_race);
        }
    }

    private void render() {
        renderWeather();
        renderPanel();
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private TextView text(float sp, int font, int color, float letterSpacing) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLetterSpacing(letterSpacing);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setFocusable(false);
        try {
            Typeface face = activity.getResources().getFont(font);
            if (face != null) t.setTypeface(face);
        } catch (RuntimeException ignored) {
        }
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
