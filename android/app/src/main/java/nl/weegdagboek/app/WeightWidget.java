package nl.weegdagboek.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Startscherm-widget: gewicht van de laatste 30 dagen met weekgemiddelde, in Catppuccin Macchiato. */
public class WeightWidget extends AppWidgetProvider {

    private static final String PREFS = "widget";
    private static final String KEY = "entries";
    private static final int RANGE_DAYS = 30;

    // Catppuccin Macchiato
    private static final int MAUVE = 0xFFC6A0F6, PEACH = 0xFFF5A97F, SUBTEXT = 0xFFA5ADCB, SURFACE0 = 0xFF363A4F;
    private static final Locale NL = new Locale("nl", "NL");
    private static final String[] MONTHS = {"jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"};

    private static final class Entry {
        final LocalDate date; final double kg;
        Entry(LocalDate d, double kg) { this.date = d; this.kg = kg; }
    }

    private static final class Week {
        final LocalDate monday; final int week; double sum; int n;
        Week(LocalDate monday, int week) { this.monday = monday; this.week = week; }
        double avg() { return sum / n; }
    }

    /** Door de app aangeroepen na elke wijziging. */
    static void saveAndRefresh(Context c, String entriesJson) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (entriesJson.equals(p.getString(KEY, null))) return;
        p.edit().putString(KEY, entriesJson).apply();
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, WeightWidget.class))) update(c, m, id);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle newOptions) {
        update(c, m, id);
    }

    /** De app stuurt {"kanji":bool,"entries":[...]}; oudere versies alleen de lijst. */
    private static boolean kanji(Context c) {
        try {
            String raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
            return raw.trim().startsWith("{") && new JSONObject(raw).optBoolean("kanji", false);
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Entry> load(Context c) {
        List<Entry> list = new ArrayList<>();
        try {
            String raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
            JSONArray a = raw.trim().startsWith("{") ? new JSONObject(raw).optJSONArray("entries") : new JSONArray(raw);
            if (a == null) a = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                list.add(new Entry(LocalDate.parse(o.getString("date")), o.getDouble("kg")));
            }
        } catch (Exception ignored) { }
        list.sort((x, y) -> x.date.compareTo(y.date));
        return list;
    }

    private static Map<LocalDate, Week> weeks(List<Entry> list) {
        Map<LocalDate, Week> map = new LinkedHashMap<>();
        for (Entry e : list) {
            LocalDate monday = e.date.with(DayOfWeek.MONDAY);
            Week w = map.get(monday);
            if (w == null) { w = new Week(monday, e.date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)); map.put(monday, w); }
            w.sum += e.kg; w.n++;
        }
        return map;
    }

    static void update(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_chart);

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        List<Entry> list = load(c);
        boolean k = kanji(c);
        v.setTextViewText(R.id.empty, k ? "測定なし" : "Nog geen metingen. Tik om je eerste gewicht in te vullen.");
        if (list.isEmpty()) {
            v.setTextViewText(R.id.latest, "–");
            v.setTextViewText(R.id.sub, k ? "体重日記" : "Weegdagboek");
            v.setViewVisibility(R.id.chart, View.GONE);
            v.setViewVisibility(R.id.empty, View.VISIBLE);
            m.updateAppWidget(id, v);
            return;
        }

        Map<LocalDate, Week> weeks = weeks(list);
        Entry last = list.get(list.size() - 1);
        Week lastWeek = weeks.get(last.date.with(DayOfWeek.MONDAY));
        v.setTextViewText(R.id.latest, String.format(NL, "%.1f kg", last.kg));
        v.setTextViewText(R.id.sub, k
                ? String.format(NL, "第%d週 · 平均 %.1f kg", lastWeek.week, lastWeek.avg())
                : String.format(NL, "week %d · gem. %.1f kg", lastWeek.week, lastWeek.avg()));
        v.setViewVisibility(R.id.empty, View.GONE);
        v.setViewVisibility(R.id.chart, View.VISIBLE);

        // Grootte van de widget bepalen (staand: minimale breedte, maximale hoogte)
        Bundle o = m.getAppWidgetOptions(id);
        int wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        int hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110);
        if (wDp <= 0) wDp = 250;
        if (hDp <= 0) hDp = 110;
        float density = c.getResources().getDisplayMetrics().density;
        int chartWdp = Math.max(120, wDp - 24);
        int chartHdp = Math.max(50, hDp - 24 - 34);
        float scale = density;
        if (chartWdp * scale > 1600f) scale = 1600f / chartWdp; // begrens bitmapgrootte (geheugenlimiet widgets)
        v.setImageViewBitmap(R.id.chart, drawChart(list, weeks, chartWdp, chartHdp, scale, k));
        m.updateAppWidget(id, v);
    }

    private static Bitmap drawChart(List<Entry> all, Map<LocalDate, Week> weeks, int wDp, int hDp, float d, boolean k) {
        int W = Math.round(wDp * d), H = Math.round(hDp * d);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);

        LocalDate end = all.get(all.size() - 1).date;
        LocalDate start = end.minusDays(RANGE_DAYS - 1);
        if (all.get(0).date.isAfter(start)) start = all.get(0).date;
        long x0 = start.toEpochDay(), x1 = Math.max(end.toEpochDay(), x0 + 6);

        List<Entry> data = new ArrayList<>();
        for (Entry e : all) if (!e.date.isBefore(start)) data.add(e);
        List<double[]> wk = new ArrayList<>();
        for (Week w : weeks.values()) {
            double x = w.monday.toEpochDay() + 3;
            if (x >= x0 - 3 && x <= x1 + 3) wk.add(new double[]{Math.min(Math.max(x, x0), x1), w.avg()});
        }

        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (Entry e : data) { yMin = Math.min(yMin, e.kg); yMax = Math.max(yMax, e.kg); }
        for (double[] p : wk) { yMin = Math.min(yMin, p[1]); yMax = Math.max(yMax, p[1]); }
        double span = Math.max(yMax - yMin, 1);
        double step = span <= 2 ? 0.5 : span <= 5 ? 1 : span <= 12 ? 2 : 5;
        yMin = Math.floor((yMin - span * 0.1) / step) * step;
        yMax = Math.ceil((yMax + span * 0.1) / step) * step;

        float L = 30 * d, R = 6 * d, T = 6 * d, B = 16 * d;
        final double fx0 = x0, fx1 = x1, fyMin = yMin, fyMax = yMax;
        java.util.function.DoubleUnaryOperator sx = x -> L + (x - fx0) / (fx1 - fx0) * (W - L - R);
        java.util.function.DoubleUnaryOperator sy = y -> T + (fyMax - y) / (fyMax - fyMin) * (H - T - B);

        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(SURFACE0); grid.setStrokeWidth(1 * d);
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(SUBTEXT); label.setTextSize(10 * d);
        if (k) label.setTextLocale(Locale.JAPAN); // Japanse vormen van de tekens

        // horizontale rasterlijnen met labels
        label.setTextAlign(Paint.Align.RIGHT);
        int lines = 0;
        for (double y = yMin; y <= yMax + 1e-9 && lines < 8; y += step, lines++) {
            float py = (float) sy.applyAsDouble(y);
            cv.drawLine(L, py, W - R, py, grid);
            cv.drawText(String.format(NL, step < 1 ? "%.1f" : "%.0f", y), L - 5 * d, py + 3.5f * d, label);
        }

        // datums onderaan: begin en eind
        label.setTextAlign(Paint.Align.LEFT);
        cv.drawText(shortDate(LocalDate.ofEpochDay(x0), k), L, H - 3 * d, label);
        label.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(shortDate(LocalDate.ofEpochDay(x1), k), W - R, H - 3 * d, label);

        // dagelijkse lijn, onderbroken bij gaten van meer dan 3 dagen
        Paint daily = new Paint(Paint.ANTI_ALIAS_FLAG);
        daily.setColor(MAUVE); daily.setAlpha(140); daily.setStyle(Paint.Style.STROKE);
        daily.setStrokeWidth(1.5f * d); daily.setStrokeJoin(Paint.Join.ROUND);
        Path dp = new Path();
        long prev = Long.MIN_VALUE;
        for (Entry e : data) {
            long x = e.date.toEpochDay();
            float px = (float) sx.applyAsDouble(x), py = (float) sy.applyAsDouble(e.kg);
            if (prev == Long.MIN_VALUE || x - prev > 3) dp.moveTo(px, py); else dp.lineTo(px, py);
            prev = x;
        }
        cv.drawPath(dp, daily);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(MAUVE);
        for (Entry e : data) {
            cv.drawCircle((float) sx.applyAsDouble(e.date.toEpochDay()), (float) sy.applyAsDouble(e.kg), 2f * d, dot);
        }

        // weekgemiddelde
        if (!wk.isEmpty()) {
            Paint wl = new Paint(Paint.ANTI_ALIAS_FLAG);
            wl.setColor(PEACH); wl.setStyle(Paint.Style.STROKE); wl.setStrokeWidth(2.5f * d);
            wl.setStrokeCap(Paint.Cap.ROUND); wl.setStrokeJoin(Paint.Join.ROUND);
            Path wp = new Path();
            for (int i = 0; i < wk.size(); i++) {
                float px = (float) sx.applyAsDouble(wk.get(i)[0]), py = (float) sy.applyAsDouble(wk.get(i)[1]);
                if (i == 0) wp.moveTo(px, py); else wp.lineTo(px, py);
            }
            cv.drawPath(wp, wl);
            Paint wd = new Paint(Paint.ANTI_ALIAS_FLAG);
            wd.setColor(PEACH);
            for (double[] p : wk) cv.drawCircle((float) sx.applyAsDouble(p[0]), (float) sy.applyAsDouble(p[1]), 3f * d, wd);
        }
        return bmp;
    }

    private static String shortDate(LocalDate d, boolean kanji) {
        if (kanji) return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
        return d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1];
    }
}
