package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The next F1 race (Jolpica, the Ergast successor), the next MotoGP weekend (motogp.com's public results API,
 * undocumented) and a team's next match (a calendar feed such as FotMob's). No keys. Each source is parsed on its
 * own, so a change in one never breaks the others. Call off the UI thread.
 */
final class SportsFeed {
    private SportsFeed() { }

    // "team2": the team card's cached text from before the venue line is dropped, so it is fetched again.
    static final String F1 = "f1", MOTOGP = "motogp", TEAM = "team2";

    static final class Session {
        /** "race", "sprint" or "kickoff"; the view picks the words. */
        final String kind;
        final Instant start;
        /** How long the session counts as live after it starts. */
        final Duration live;

        Session(String kind, Instant start, Duration live) {
            this.kind = kind;
            this.start = start;
            this.live = live;
        }
    }

    static final class Event {
        final String title;
        final String detail;
        final List<Session> sessions;

        Event(String title, String detail, List<Session> sessions) {
            this.title = title;
            this.detail = detail;
            this.sessions = sessions;
        }

        /** True once every session has ended: the feed should be asked again. */
        boolean over(Instant now) {
            for (Session s : sessions) if (now.isBefore(s.start.plus(s.live))) return false;
            return true;
        }

        String toJson() {
            try {
                JSONObject o = new JSONObject().put("title", title).put("detail", detail);
                JSONArray a = new JSONArray();
                for (Session s : sessions) {
                    a.put(new JSONObject().put("kind", s.kind).put("start", s.start.toEpochMilli())
                            .put("live", s.live.toMinutes()));
                }
                return o.put("sessions", a).toString();
            } catch (JSONException e) {
                return null;
            }
        }

        static Event fromJson(String s) {
            if (s == null) return null;
            try {
                JSONObject o = new JSONObject(s);
                JSONArray a = o.getJSONArray("sessions");
                List<Session> sessions = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.getJSONObject(i);
                    sessions.add(new Session(x.getString("kind"), Instant.ofEpochMilli(x.getLong("start")),
                            Duration.ofMinutes(x.getLong("live"))));
                }
                return new Event(o.getString("title"), o.optString("detail", ""), sessions);
            } catch (JSONException e) {
                return null;
            }
        }
    }

    // ---- F1 ---------------------------------------------------------------------------------------------

    static Event f1() throws IOException, JSONException {
        JSONArray races = new JSONObject(Net.get("https://api.jolpi.ca/ergast/f1/current/next.json"))
                .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
        if (races.length() == 0) return null;   // the season is over
        JSONObject race = races.getJSONObject(0);
        List<Session> sessions = new ArrayList<>();
        JSONObject sprint = race.optJSONObject("Sprint");
        if (sprint != null) sessions.add(new Session("sprint", ergastTime(sprint), Duration.ofMinutes(45)));
        sessions.add(new Session("race", ergastTime(race), Duration.ofMinutes(120)));
        JSONObject circuit = race.optJSONObject("Circuit");
        return new Event(race.getString("raceName"), circuit == null ? "" : circuit.optString("circuitName", ""), sessions);
    }

    /** {"date": "2026-11-01", "time": "20:00:00Z"}; no time yet means midday UTC. */
    private static Instant ergastTime(JSONObject o) throws JSONException {
        String date = o.getString("date");
        String time = o.optString("time", "12:00:00Z");
        if (!time.endsWith("Z")) time = time + "Z";
        return Instant.parse(date + "T" + time);
    }

    // ---- MotoGP -----------------------------------------------------------------------------------------

    private static final String MOTOGP_API = "https://api.motogp.pulselive.com/motogp/v1/results/";

    static Event motogp() throws IOException, JSONException {
        JSONArray seasons = new JSONArray(Net.get(MOTOGP_API + "seasons"));
        String season = null;
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject s = seasons.getJSONObject(i);
            if (s.optBoolean("current")) season = s.getString("id");
        }
        if (season == null) return null;
        JSONArray events = new JSONArray(Net.get(MOTOGP_API + "events?seasonUuid=" + season + "&isFinished=false"));
        JSONObject event = null;
        for (int i = 0; i < events.length() && event == null; i++) {
            JSONObject e = events.getJSONObject(i);
            if (!e.optBoolean("test")) event = e;
        }
        if (event == null) return null;
        String eventId = event.getString("id");
        JSONArray categories = new JSONArray(Net.get(MOTOGP_API + "categories?eventUuid=" + eventId));
        String category = null;
        for (int i = 0; i < categories.length(); i++) {
            JSONObject c = categories.getJSONObject(i);
            if (c.optString("name").startsWith("MotoGP")) category = c.getString("id");
        }
        if (category == null) return null;
        JSONArray list = new JSONArray(Net.get(MOTOGP_API + "sessions?eventUuid=" + eventId + "&categoryUuid=" + category));

        // The API labels session times +00:00, but they read as the circuit's local time (Sepang's race at
        // 15:00), so the wall clock is taken as is in the host country's time zone.
        ZoneId zone = motogpZone(event);
        Instant sprint = null, race = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.getJSONObject(i);
            String type = s.optString("type");
            if (!"SPR".equals(type) && !"RAC".equals(type)) continue;
            Instant at = localWallClock(s.optString("date"), zone);
            if (at == null) continue;
            if ("SPR".equals(type)) sprint = at; else race = at;
        }
        List<Session> sessions = new ArrayList<>();
        if (sprint != null) sessions.add(new Session("sprint", sprint, Duration.ofMinutes(45)));
        if (race != null) sessions.add(new Session("race", race, Duration.ofMinutes(50)));
        if (sessions.isEmpty()) return null;

        String name = event.optString("name", "");
        if (name.isEmpty()) name = event.optString("sponsored_name", "MotoGP");
        JSONObject circuit = event.optJSONObject("circuit");
        return new Event(titleCase(name), circuit == null ? "" : circuit.optString("name", ""), sessions);
    }

    /** "2026-11-01T15:00:00+00:00" read as a wall-clock time in {@code zone}. */
    private static Instant localWallClock(String s, ZoneId zone) {
        if (s == null || s.length() < 19) return null;
        try {
            return LocalDateTime.parse(s.substring(0, 19)).atZone(zone).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Map<String, String> ZONES = new HashMap<>();
    static {
        String[][] z = {
                {"TH", "THA", "Asia/Bangkok"}, {"AR", "ARG", "America/Argentina/Buenos_Aires"},
                {"US", "USA", "America/Chicago"}, {"QA", "QAT", "Asia/Qatar"}, {"ES", "ESP", "Europe/Madrid"},
                {"FR", "FRA", "Europe/Paris"}, {"GB", "GBR", "Europe/London"}, {"AT", "AUT", "Europe/Vienna"},
                {"IT", "ITA", "Europe/Rome"}, {"SM", "SMR", "Europe/Rome"}, {"NL", "NLD", "Europe/Amsterdam"},
                {"DE", "DEU", "Europe/Berlin"}, {"CZ", "CZE", "Europe/Prague"}, {"HU", "HUN", "Europe/Budapest"},
                {"JP", "JPN", "Asia/Tokyo"}, {"ID", "IDN", "Asia/Makassar"}, {"AU", "AUS", "Australia/Melbourne"},
                {"MY", "MYS", "Asia/Kuala_Lumpur"}, {"PT", "PRT", "Europe/Lisbon"}, {"BR", "BRA", "America/Sao_Paulo"},
                {"KZ", "KAZ", "Asia/Almaty"}, {"IN", "IND", "Asia/Kolkata"}, {"TR", "TUR", "Europe/Istanbul"},
        };
        for (String[] row : z) {
            ZONES.put(row[0], row[2]);
            ZONES.put(row[1], row[2]);
        }
    }

    private static ZoneId motogpZone(JSONObject event) {
        String code = null;
        JSONObject country = event.optJSONObject("country");
        if (country != null) code = country.optString("iso", null);
        JSONObject circuit = event.optJSONObject("circuit");
        if (code == null && circuit != null) code = circuit.optString("nation", null);
        String zone = code == null ? null : ZONES.get(code.toUpperCase(Locale.ROOT));
        try {
            return zone == null ? ZoneOffset.UTC : ZoneId.of(zone);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /** "GRAND PRIX OF MALAYSIA" -> "Grand Prix of Malaysia"; mixed case is kept. */
    static String titleCase(String s) {
        if (!s.equals(s.toUpperCase(Locale.ROOT))) return s;
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            boolean small = b.length() > 0 && (w.equals("of") || w.equals("de") || w.equals("the"));
            b.append(small ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    // ---- a team's calendar ------------------------------------------------------------------------------

    /** The earliest match not yet over in an .ics feed; friendlies are left out. */
    static Event team(String icsUrl, String team) throws IOException {
        return nextMatch(Net.get(icsUrl), team, Instant.now());
    }

    static Event nextMatch(String ics, String team, Instant now) {
        List<String> lines = unfold(ics);
        Map<String, String> ev = null;
        Map<String, String> params = null;
        Event best = null;
        Instant bestStart = null;
        boolean inAlarm = false;
        for (String line : lines) {
            // FotMob nests a reminder in every match whose SUMMARY and DESCRIPTION ("... starting in 15 minutes")
            // would otherwise overwrite the match's own.
            if (line.equals("BEGIN:VALARM")) inAlarm = true;
            if (inAlarm) {
                if (line.equals("END:VALARM")) inAlarm = false;
                continue;
            }
            if (line.equals("BEGIN:VEVENT")) {
                ev = new HashMap<>();
                params = new HashMap<>();
                continue;
            }
            if (line.equals("END:VEVENT") && ev != null) {
                Instant start = icsTime(ev.get("DTSTART"), params.get("DTSTART"));
                String summary = unescape(ev.getOrDefault("SUMMARY", ""));
                String description = unescape(ev.getOrDefault("DESCRIPTION", ""));
                boolean friendly = (summary + " " + description).toLowerCase(Locale.ROOT).contains("friendl");
                Duration live = Duration.ofMinutes(115);
                if (start != null && !friendly && now.isBefore(start.plus(live))
                        && (bestStart == null || start.isBefore(bestStart))) {
                    bestStart = start;
                    // The venue: LOCATION is "Old Trafford, Sir Matt Busby Way, Manchester, GBR".
                    String venue = unescape(ev.getOrDefault("LOCATION", ""));
                    int comma = venue.indexOf(',');
                    if (comma >= 0) venue = venue.substring(0, comma).trim();
                    best = new Event(matchTitle(summary, team), venue,
                            Collections.singletonList(new Session("kickoff", start, live)));
                }
                ev = null;
                continue;
            }
            if (ev == null) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);
            int semi = head.indexOf(';');
            String name = (semi < 0 ? head : head.substring(0, semi)).toUpperCase(Locale.ROOT);
            ev.put(name, value);
            params.put(name, semi < 0 ? "" : head.substring(semi + 1));
        }
        return best;
    }

    /** RFC 5545 line folding: a line starting with a space or tab continues the one before. */
    private static List<String> unfold(String ics) {
        List<String> out = new ArrayList<>();
        for (String raw : ics.split("\r?\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else {
                out.add(raw);
            }
        }
        return out;
    }

    private static final DateTimeFormatter ICS_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** "20261025T160000Z" (UTC), "20261025T160000" with TZID=Europe/London, or a bare date. */
    static Instant icsTime(String value, String params) {
        if (value == null) return null;
        value = value.trim();
        try {
            if (value.endsWith("Z")) {
                return LocalDateTime.parse(value.substring(0, value.length() - 1), ICS_LOCAL).toInstant(ZoneOffset.UTC);
            }
            ZoneId zone = ZoneId.systemDefault();
            if (params != null) {
                for (String p : params.split(";")) {
                    if (p.toUpperCase(Locale.ROOT).startsWith("TZID=")) zone = ZoneId.of(p.substring(5).replace("\"", ""));
                }
            }
            if (value.length() == 8) return LocalDate.parse(value, ICS_DATE).atStartOfDay(zone).toInstant();
            return LocalDateTime.parse(value, ICS_LOCAL).atZone(zone).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }

    /** "Manchester United - Chelsea" -> "Man Utd Vs Chelsea", with the team's short name. */
    static String matchTitle(String summary, String team) {
        String t = summary.replace(" - ", " Vs ").replace(" vs. ", " Vs ").replace(" vs ", " Vs ");
        if (!team.isEmpty()) t = t.replace("Manchester United", team).replace("Manchester Utd", team);
        return t.trim();
    }
}
