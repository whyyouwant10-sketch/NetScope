# NetScope POC-01 — Android Network Measurement Laboratory

A deliberately small Android app that validates the riskiest part of NetScope:
**can 4G/5G usage be classified honestly on a real device?**

POC-01 is not the product UI. It has no Room database, charts, Hilt, per-app
usage, or background scheduler. It measures, classifies, logs, keeps daily
history, and posts a few focused alerts.

## What it does

| Pipeline | Source | Used for |
| --- | --- | --- |
| A — authoritative totals | `NetworkStatsManager` | Mobile and Wi-Fi totals, RX/TX split |
| B — generation estimate | `TrafficStats` + `TelephonyCallback` | 4G / 5G / other / unknown split |

The two pipelines are intentionally separate. The classification engine never
writes into the authoritative totals; the screen shows both and the difference
between them.

Classification rules:

| Observed state | Result |
| --- | --- |
| NR (`NETWORK_TYPE_NR`) | `5g` |
| LTE / LTE-CA + NR NSA, NR NSA mmWave, or NR advanced display override | `5g` |
| LTE / LTE-CA without an NR override, including `OVERRIDE_NETWORK_TYPE_LTE_CA` | `4g` |
| 3G / 2G | `other` |
| Radio state unknown or counters unsupported | `unknown` |
| Radio transition inside the interval | split by time spent in each class |
| Mixed interval with no usable timestamp | `4g`, reported as ambiguous (4G tie-break) |
| Mobile bytes move while Wi-Fi is the active transport | last stable mobile class, `wifi_mobile`, reported as ambiguous |
| Counter decreased (reboot / mobile data off) | `reset`, delta dropped |
| Implausible or uncorroborated upward jump (counter rebase) | `counter_rebase`, excluded |

A display override is treated as an indicator, not proof. `TelephonyDisplayInfo`
is display-oriented; Android does not expose historical per-generation byte
counters.

### Counter rebase policy (A)

While mobile data is off, Samsung reports a frozen stale counter. Turning data
back on makes the counter jump to its true since-boot value. RX and TX high-water
marks are tracked separately, so a reset in one direction is still detected when
growth in the other direction hides it in the combined total. A decrease in
either direction sets `rebase_pending`. While pending, an increase that is still
below the pre-drop high-water is treated as a recovery/rebase signal.

The app flags a discontinuity when the interval delta exceeds:

1. the physical ceiling (150 MB/s sustained), or
2. `max(256 MB, 4 x the NetworkStats mobile delta)`.

The NetworkStats bound applies to every interval, not only physically
implausible ones. Under policy **A**, the corroborated part of the jump goes to
`unknown`; the excess goes to `counter_rebase` and is **excluded from every
bucket, from coverage, and from the 4G/5G split**. Nothing is hidden: the actual
excluded amount is shown and logged. If NetworkStats is unavailable, the jump is
logged as `counter_rebase_unverified` and the screen warns that the session is
unreliable.

### Sampling and attribution

- TrafficStats and telephony sample at **1 s** while mobile bytes are moving and
  **5 s** while idle. NetworkStats is queried at each sample but no more often
  than every **2 s**, so active sampling gets a fresh sanity bound every 2 s.
  The 1 s active cadence drives the front-page and notification speed.
- Every display-info class change inside an interval is recorded. Multiple
  transitions are replayed in order and the interval is split proportionally to
  the time spent in each class. The total byte count stays exact; only the split
  uses observed durations.
- When a split is impossible (no usable transition timestamp), the interval goes
  to 4G (never under-counting 4G) and the amount is reported as `ambiguous`. The
  "longer-duration class" alternative is not used because without a timestamp
  there is no duration information.
- The **4G upper bound** switch is deliberately narrow: it assigns residual
  ambiguity to 4G only when no better estimate exists (unknown class, mixed
  interval without a timestamp, or late mobile bytes with no known class). It
  does not overwrite a time-weighted split or a known last-stable class. The
  assigned amount is always shown as `+/-`.


## Requirements

- Android 12+ (API 31). The telephony callback used here starts at API 31.
- JDK 17 or 21 (Gradle 8.7 does not run on JDK 25).
- Android SDK Platform 34 and Build Tools 34.0.0.
- Target device for validation: Samsung Galaxy A55 on Jio 4G/5G.

## Build and test

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Android Studio

Open the **`NetScopePOC` directory itself**, not the parent `NetScope` directory:
the parent contains separate Gradle builds and Android Studio will create a
plain Java project instead of linking Gradle. One Android Studio window per
Gradle build is the reliable setup.

The project pins Gradle 8.7 / AGP 8.5.2, which does **not** run on Android
Studio's bundled JBR 25. Set **Settings → Build, Execution, Deployment → Build
Tools → Gradle → Gradle JDK** to JDK 17 or 21, then sync. Also install **SDK Platform
34** and **Build Tools 34.0.0**. Do not accept an AGP/Gradle upgrade while
validating POC-01; upgrade the toolchain as a separate, tested change.

`NetworkClassifier` and `TrafficAttributor` are pure Kotlin and are covered by
JVM unit tests, so the difficult classification rules can be checked without a
device.

## Using it

1. Grant permissions when the **Permissions needed** section appears. Once
   Phone state, Notifications, and Usage access are all granted, that section is
   hidden.
2. Logging starts automatically when the app opens. A low-importance foreground
   notification keeps the sampler alive; use **Menu > All stats > Stop logging**
   to end the session.
3. The front screen shows the simplified view: current class, download/upload
   speed, mobile and Wi-Fi session totals, 4G/5G session bytes, coverage,
   reconciliation, and warnings. Open
   **Menu > History** for daily usage and **Menu > All stats** for raw counters,
   all buckets, per-interface speeds, and the full reconciliation.
4. Type a label (optional) and tap **Mark next row** to tag the next CSV row.
   The last marker and its time are shown near the tools at the bottom.
5. Tap **Export CSV** to save the log through the Storage Access Framework.
6. The bottom **App resources** block shows dynamic RAM (PSS), Java heap, app
   data, CSV size, and APK size. PSS is Android's process-memory estimate and
   changes between samples.

### Ongoing notification

While logging, the foreground notification shows the current network
(4G/5G/Wi-Fi), download/upload speed, and 4G/5G/Wi-Fi session totals in its
collapsed form. Expanding it adds cellular and Wi-Fi signal quality
(Excellent/Good/Fair/Weak/Poor). Tapping the notification opens the app.

### 4G tracking

Set a 4G allowance in GB on the front page. Only 4G usage is counted against it;
5G, other mobile generations, and unknown traffic do not reduce the figure. 4G
usage is counted across logging sessions, service restarts, and explicit stops,
so the remaining figure is remembered. It is shown in the app and in the
notification as `4G: 500MB (1.5GB)`. Editing the allowance changes the remaining
figure without clearing usage; **Reset usage** clears it.

A second field sets the 4G usage alert step in MB (default 500 MB). Each time
usage since the last reset crosses the next step, the app posts a notification
such as `1 GB 4G consumed`, including the remaining allowance when one is set.

The app also posts a notification when the mobile generation changes directly
between 4G and 5G (`5G → 4G` or `4G → 5G`). These alerts use a separate
high-importance channel, so they can appear as heads-up notifications.

### Test protocol

1. Confirm the baseline: start logging on Wi-Fi, then disable Wi-Fi.
2. Large download on 4G; mark it.
3. Large download on 5G; mark it.
4. Start a download on one generation and switch to the other mid-transfer.
5. Wi-Fi-only traffic: mobile deltas should stay at zero.
6. **Rebase test:** download on Wi-Fi with mobile data off, mark it, turn mobile
   data off, wait 10 s, turn it on, wait 10 s, then stop. The jump must appear as
   `counter_rebase`, not as 4G/5G.
7. Repeat with the screen off.
8. Reboot, start logging again, and confirm `boot_count` changes and no delta is
   negative.
9. Revoke Phone state and confirm the app keeps running with `unknown` rather
   than fabricated data.
10. Set a 4G allowance (for example 2 GB) and confirm the remaining figure
    decreases on 4G traffic in both the app and the notification, including
    after a restart. 5G traffic must not reduce it.
11. Set the 4G alert step to a small value and confirm the usage notifications
    report the cumulative total.
12. Move between 4G and 5G and confirm the generation-change notification.

If you tap **Stop logging** while a marker is pending, the app writes one more
sample before stopping, so the marker is not lost. Otherwise wait at least one
sampling interval after the last marker.

### Testing notifications and mobile tracking

1. Install the debug APK, grant Phone state, Notifications, and Usage access,
   then start logging.
2. Press Home. The ongoing notification must keep updating while the app is in
   the background.
3. Start a download. The speed line should update about once per second while
   traffic is moving; after traffic stops it backs off to 5 s.
4. Expand the notification. Signal quality should show labels such as
   `Cell Good · Wi-Fi Excellent`, not raw dBm.
5. Enter `2` in **4G allowance (GB)**. On 4G, the app and notification should
   show `4G: 500MB (1.5GB)`; 5G traffic must not reduce the remaining figure.
6. Set **4G usage alert every (MB)** to a small value such as `50`, consume 4G
   data, and confirm cumulative alerts: `50 MB 4G consumed`, `100 MB ...`.
7. Switch the preferred network between 5G and LTE while mobile data is active.
   Expect `5G → 4G` or `4G → 5G` heads-up alerts.
8. Tap any notification and confirm the app opens.
9. Force-stop and restart the app, then confirm the used amount is remembered.
   Tap **Reset usage** and confirm the remaining figure returns to the full
   allowance while the allowance and alert step stay saved.

### Testing history, persistence, and auto-start

1. Use some 4G, 5G, and Wi-Fi data, then open **Menu > History**. Confirm daily
   rows appear and the last 7 days / last 30 days / this month / all-time
   summaries match the rows.
2. Force-stop the app from Android settings, reopen it, and confirm logging
   starts automatically and the session totals from before the stop are still
   shown.
3. While the service is running, open **Menu > All stats** and tap **Stop
   logging**. Confirm logging stops and the next app open starts a fresh session
   (session totals reset, daily history remains).
4. Reboot the device and confirm logging starts again without opening the app
   (subject to the OEM's background-start policy).
5. Confirm the CSV file never contains rows older than 24 hours after an hourly
   prune, while **History** still shows older daily totals.

### CSV columns

| Column | Meaning |
| --- | --- |
| `time_utc` | Sample time in UTC |
| `elapsed_ms` | `SystemClock.elapsedRealtime()`; reveals sampling gaps |
| `boot_count` | `Settings.Global.BOOT_COUNT`; detects reboots |
| `mobile_rx`, `mobile_tx` | Cumulative `TrafficStats` mobile counters |
| `total_rx`, `total_tx` | Cumulative all-interface counters |
| `net_type` | `TelephonyManager.NETWORK_TYPE_*` (13 = LTE, 19 = LTE-CA, 20 = NR) |
| `override` | `TelephonyDisplayInfo` override (1 = LTE-CA, 3 = NR NSA, 4 = NR NSA mmWave, 5 = NR advanced) |
| `sub_id` | Default data subscription, `-1` when unavailable |
| `transport` | `wifi`, `cell`, `other`, or `none` |
| `class` | Instantaneous class at the end of the interval |
| `bucket` | Dominant bucket of the interval (`4g`, `5g`, `other`, `unknown`, `counter_rebase`, `reset`) |
| `delta_bytes` | Bytes attributed to radio buckets; excludes `counter_rebase` |
| `ns_mobile_today`, `ns_wifi_today` | `NetworkStatsManager` totals since local midnight; `-1` means unavailable |
| `note` | Marker label written on this row |
| `sample_ms` | Length of this sampling interval |
| `reason` | `normal`, `radio_transition`, `wifi_mobile`, `sampling_gap`, `mixed_to_4g`, `counter_reset`, `counter_rebase`, `counter_rebase_unverified`, `unsupported_counters` |
| `ambiguous_bytes` | Best-effort part of `delta_bytes` |
| `rebase_excess_bytes` | Excess bytes excluded as a counter rebase, beyond NetworkStats corroboration |
| `ts_delta_bytes` | Raw signed `TrafficStats` delta for the interval (negative on a reset) |
| `ns_delta_bytes` | NetworkStats mobile delta since the previous NetworkStats query (2 s cadence) |
| `ns_refreshed` | `true` when NetworkStats was queried on this row |
| `bytes_4g`, `bytes_5g`, `bytes_other`, `bytes_unknown` | Exact per-bucket split of this interval, including time-split transitions |

Bytes are integers. Times are UTC; convert to local dates during analysis.

## Reading the results

- **Classification coverage** = `(4g + 5g) / (4g + 5g + other + unknown)` for
  observed mobile traffic. This is not overall accuracy.
- **Ambiguity** = the part of a bucket assigned on a best-effort basis
  (time-split minority, `wifi_mobile`, overlap without a timestamp, or the
  4G upper bound). It is shown as `+/-` and must stay inside the quality gate:
  `max(10 MB, 1% of mobile usage)`.
- **Reconciliation** = NetworkStats mobile usage during the logging session
  minus observed classified mobile bytes. The screen shows the absolute
  difference prominently and shows a percentage only once the session is at
  least 10 MB. NetworkStats includes bytes that TrafficStats sampling can miss,
  and has a reporting delay, so exact equality is not expected. The screen warns
  when observed mobile exceeds system mobile by more than `max(50 MB, 5%)`.
- **Counter rebase** is excluded from every bucket. A non-zero value is not
  traffic; it is a counter discontinuity. The session remains usable as long as
  reconciliation stays small.
- NetworkStats is accumulated as positive per-query deltas, so a midnight
  rollover does not corrupt the session total.
- `unknown` is a real result, not an error to be hidden.

The field log that motivated this design validated the approach: after removing
two rebase jumps of 40.9 GB and 41.3 GB, observed mobile was 875.54 MB against
NetworkStats 875.66 MB — a 0.01% difference — with 95% classification coverage.


## History, persistence, and auto-start

Logging starts automatically when the app is opened and after a reboot
(`BootReceiver`). It keeps running until the user explicitly stops it with the
**Stop logging** button in **Menu > All stats**. The notification itself has no
stop action, so it cannot be stopped by accident.

- The current session is snapshotted to `session.json` every few seconds and on
  service shutdown. After a system-initiated restart the service restores the
  4G/5G totals, ambiguity, markers, and NetworkStats session totals.
- An explicit user Stop clears `session.json`, so the next start is a fresh
  session. Daily history and the 4G allowance usage are never cleared by a
  stop; only **Reset usage** clears the 4G allowance usage.
- At startup, the first NetworkStats query after a restore adds only a positive
  mobile gap (traffic that happened while the service was down) to the
  `unknown` mobile bucket. A missing or rolled-back value adds nothing, so a
  midnight rollover cannot double-count.
- Daily history is stored separately in `history.json` and is kept indefinitely.
  **Menu > History** shows the last 30 calendar days (newest first, zero-filled)
  as a table: Date, 4G, 5G, Mobile (4G + 5G + other/unknown), Wi-Fi, Total.
  Summary rows at the bottom show the last 7 days, last 30 days, this month, and
  all time.
- The detailed CSV is pruned to the last 24 hours at startup and once per hour.
  Export always writes the current 24-hour file.

## Known limitations

- No public historical per-generation byte counters exist.
- 5G NSA can report LTE as the underlying radio; the display override is the
  only public hint.
- The display override is not guaranteed to reflect the true radio state.
- Background restrictions and OEM battery management can create sampling gaps;
  the app records them and marks the affected interval.
- `TrafficStats` counters reset on reboot and rebase when mobile data is
  re-enabled. RX and TX high-water marks are tracked separately so a masked reset
  is still detected. Resets are dropped and rebases are excluded; neither is
  counted as traffic. A counter that resets and then grows gradually can still
  look like traffic until NetworkStats catches up.
- A rebase is excluded rather than assigned to a generation, so the 4G/5G split
  is conservative around discontinuities.
- `NetworkStatsManager` historical data can lag live counters. A jump that
  NetworkStats has not yet corroborated is logged as `counter_rebase_unverified`.
- Per-SIM totals are not guaranteed to be available to third-party apps.
- Per-app usage and the Room database are out of scope for POC-01. Daily
  history is a compact JSON store, not a queryable analytics database.

## Next step

Run the protocol above on the A55. If classification coverage and precision hold
up on stable 4G and 5G tests, proceed to the Room schema and the Home/Analytics
screens. If the 4G/5G split is unreliable, revise the measurement design before
building the product UI.
