# My TV: what this fork adds

This fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT) adds a few things to the launcher,
all off by default and turned on by the theme in [`theme/config.json`](theme/config.json):

| Key | What it does |
|---|---|
| `all_apps_tile` | The last dock slot is an **All apps** tile that opens the App library. OK on an app there opens it; hold OK adds it to Home or removes it. Hold OK on a dock app to move or remove it (`tile_menu`). |
| `inputs_pill_port`, `inputs_pill_label` | The Inputs pill switches straight to that HDMI port with that name (here HDMI 1, "PS5"). Hold OK on it for the full inputs menu. |
| `weather_place`, `weather_latitude`, `weather_longitude` | Temperature and an icon at the end of the clock row, from Open-Meteo, every 30 minutes while Home is on screen. |
| `sports_row`, `sports_team`, `sports_team_ics` | An "Up next" row under the dock: the next F1 race (Jolpica), the next MotoGP weekend (motogp.com's results API) and the team's next match (FotMob calendar, friendlies left out). Countdown within 24 hours, LIVE while on. Refreshed every 6 hours; a failed refresh keeps the last result, dimmed, with "Update failed". |

**Network:** unlike upstream, this launcher has the `INTERNET` permission, used only for those three feeds. No
keys, no accounts, nothing sent but the request itself.

The code: `Extras.java` (settings), `HomeExtras.java` (views and refresh), `WeatherFeed.java`, `SportsFeed.java`,
`Net.java`, plus small hooks in `AppAdapter.java`, `Config.java` and `MainActivity.java`.

## Installing it on the TV (for the agent on the laptop)

Follow [`PROMPT.md`](../PROMPT.md) for the backup and debloat, then:

1. Build and install the launcher from this fork (`launcher/build-and-install.ps1` on Windows).
2. Put TOD in the dock: find its package with `adb shell pm list packages | findstr -i tod` and insert it as the
   third entry of `dock_apps` in `theme/config.json` (after Netflix). Check the other four packages are installed;
   a missing one is skipped.
3. Push the theme: `powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost <tv ip> my-tv\theme`.
4. Check on the TV: the PS5 pill opens HDMI 1, All apps opens the library, the weather and "Up next" fill in within
   a minute or so (they need the TV online).
