package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/** Current temperature and conditions from Open-Meteo, which needs no key. */
final class WeatherFeed {
    private WeatherFeed() { }

    static final class Now {
        final int tempC;
        /** WMO weather code. */
        final int code;

        Now(int tempC, int code) {
            this.tempC = tempC;
            this.code = code;
        }

        String toJson() {
            return "{\"t\":" + tempC + ",\"c\":" + code + "}";
        }

        static Now fromJson(String s) {
            if (s == null) return null;
            try {
                JSONObject o = new JSONObject(s);
                return new Now(o.getInt("t"), o.getInt("c"));
            } catch (JSONException e) {
                return null;
            }
        }
    }

    static Now fetch(double latitude, double longitude) throws IOException, JSONException {
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code&timezone=auto", latitude, longitude);
        JSONObject current = new JSONObject(Net.get(url)).getJSONObject("current");
        return new Now((int) Math.round(current.getDouble("temperature_2m")), current.getInt("weather_code"));
    }

    /** An icon for a WMO code: clear, partly cloudy, cloudy (also fog and snow), rain, storm. */
    static int icon(int code) {
        if (code == 0) return R.drawable.ic_weather_clear;
        if (code <= 2) return R.drawable.ic_weather_partly;
        if (code >= 95) return R.drawable.ic_weather_storm;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_weather_rain;
        return R.drawable.ic_weather_cloudy;
    }
}
