package com.gathin.tvhome;

import android.content.res.Resources;

import org.json.JSONObject;

/**
 * Settings for this fork's additions: the All apps tile, the Inputs pill as a one-press HDMI button, weather in the
 * header and the "Up next" sports row. Defaults come from config_extras.xml (all off); a theme's config.json
 * overrides them with the same keys.
 */
final class Extras {
    final boolean allAppsTile;
    /** HDMI port the Inputs pill opens directly, 1 to 4; 0 keeps the inputs menu. */
    final int inputsPillPort;
    final String inputsPillLabel;
    /** Weather is on when both coordinates parse. */
    final boolean weather;
    final String weatherPlace;
    final double weatherLatitude, weatherLongitude;
    final boolean sportsRow;
    final String sportsTeam;
    final String sportsTeamIcs;

    private Extras(boolean allAppsTile, int inputsPillPort, String inputsPillLabel, String weatherPlace,
                   String latitude, String longitude, boolean sportsRow, String sportsTeam, String sportsTeamIcs) {
        this.allAppsTile = allAppsTile;
        this.inputsPillPort = inputsPillPort;
        this.inputsPillLabel = inputsPillLabel;
        this.weatherPlace = weatherPlace;
        double lat = Double.NaN, lon = Double.NaN;
        try {
            lat = Double.parseDouble(latitude.trim());
            lon = Double.parseDouble(longitude.trim());
        } catch (NumberFormatException ignored) {
        }
        this.weather = !Double.isNaN(lat) && !Double.isNaN(lon)
                && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
        this.weatherLatitude = lat;
        this.weatherLongitude = lon;
        this.sportsRow = sportsRow;
        this.sportsTeam = sportsTeam;
        this.sportsTeamIcs = sportsTeamIcs;
    }

    /** @param json the theme's config.json, or null when no theme is pushed */
    static Extras load(Resources res, JSONObject json) {
        boolean allApps = res.getBoolean(R.bool.all_apps_tile);
        int port = res.getInteger(R.integer.inputs_pill_port);
        String label = res.getString(R.string.inputs_pill_label).trim();
        String place = res.getString(R.string.weather_place).trim();
        String lat = res.getString(R.string.weather_latitude);
        String lon = res.getString(R.string.weather_longitude);
        boolean sports = res.getBoolean(R.bool.sports_row);
        String team = res.getString(R.string.sports_team).trim();
        String ics = res.getString(R.string.sports_team_ics).trim();
        if (json != null) {
            allApps = json.optBoolean("all_apps_tile", allApps);
            int p = json.optInt("inputs_pill_port", port);
            port = p >= 0 && p <= 4 ? p : port;
            label = json.optString("inputs_pill_label", label).trim();
            place = json.optString("weather_place", place).trim();
            // Coordinates may be written as numbers or strings.
            lat = json.has("weather_latitude") ? String.valueOf(json.opt("weather_latitude")) : lat;
            lon = json.has("weather_longitude") ? String.valueOf(json.opt("weather_longitude")) : lon;
            sports = json.optBoolean("sports_row", sports);
            team = json.optString("sports_team", team).trim();
            ics = json.optString("sports_team_ics", ics).trim();
        }
        // Only plain https feeds; anything else keeps the team card off.
        if (!ics.startsWith("https://")) ics = "";
        return new Extras(allApps, port, label, place, lat, lon, sports, team, ics);
    }
}
