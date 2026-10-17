# Duo Open for Galaxy Z Fold

The iPhone "Duo" frosted-glass fold, playing system-wide on a Samsung Galaxy
Z Fold as you open and close it, following the real hinge angle. No root.

This is a Samsung-only fork of the original
[Duo Open](https://github.com/marcoazeem/duo-open), which targets Android
foldables in general (built on the OnePlus Open). This fork drops everything
that isn't needed on a Galaxy Z Fold, including the live-wallpaper mode and the lite edition, and adds:

- **Real-time hinge tracking.** The frost follows your hand continuously
  rather than at the 0° / 90° / 180° stops that Samsung lets apps see.
- **Choose when each screen takes over.** The cover can light partway
  through a close, so its half of the fold is actually seen, and the inner
  screen can light earlier on an open.
- **Hand-overs between the screens that dip through black**, hiding the
  panel switching on and apps resizing or relaunching.

Developed and measured on a **Galaxy Z Fold 8 Ultra** (SM-F976B, Android 17).
Other Galaxy Z Folds running One UI 5 (Android 13) or later should work the
same way but are untested; reports welcome (see *Reporting a problem*).

The shader comes from
[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation), via the
original Duo Open.

## What it does

Each half of the screen acts as a pane of frosted glass hinged at the crease.
While the phone is partly folded the moving half is blurred and darkened by
how far it is from flat. As the hinge reaches 180° the picture settles into
focus. Both screens take part:

- **Opening:** the cover screen frosts in until the inner screen takes over
  at about 85°. The inner screen then picks up fully frosted and clears as
  you reach flat.
- **Closing:** the same in reverse.

It works over *everything*: your own wallpaper, icons, widgets, the lock
screen, whatever app is open. It runs as an accessibility service that
draws a touch-transparent overlay above every window.

## Requirements

- A Samsung Galaxy Z Fold on Android 13 or later.
- [Shizuku](https://shizuku.rikka.app/) for live hinge tracking (strongly
  recommended). Without it the fold still plays, but as a short timed
  animation at each 0° / 90° / 180° stop, since that's all Samsung reports
  to apps.

## Install

1. Download `DuoOpen-<version>.apk` from [Releases](../../releases) and
   install it.
2. Open **Duo Open**, tap **Turn on** under *Whole-screen fold*, and enable
   *Duo Open full-screen fold* in Accessibility.
   - Android blocks accessibility for sideloaded apps until you allow it.
     If the toggle is greyed out:
     1. Tap it once so Android refuses you.
     2. Go to *Settings → Apps → Duo Open → ⋮ (top right) → Allow
        restricted settings*.
     3. Try the toggle again.

     If *Allow restricted settings* is missing, turn off *Settings →
     Security and privacy → Auto Blocker* first. **Settings → Toggle greyed
     out?** in the app walks through it.
   - Or skip the check by installing over USB: apps installed with
     `adb install` aren't restricted.
3. Install and start Shizuku. Start it over wireless debugging or from a
   computer, once per boot. Forks that keep Shizuku's API under another
   package, such as thedjchi's `moe.shizuku.privileged.api.szuix`, work too.
4. Back in Duo Open, tap **Authorise** under *Live hinge tracking*. The
   home screen shows *Tracking: Live* once the hinge sensors are being read.
5. Fold the phone partway and open it. **Play the fold** replays the effect
   without folding.

## Live hinge tracking

The public hinge sensor on a Galaxy Z Fold reports only 0°, 90° and 180°.
Samsung's own hinge sensors, `folding_angle` and `lid_angle_fusion`, need a
system-only permission. However, Samsung's sensor HAL writes each step of
both to the system log, with the time it was sampled:

- `folding_angle` every 11°.
- `lid_angle_fusion` every 10°.

The two interleave, so a reading arrives roughly every 5°, over the whole
fold and whatever is on screen. With Shizuku, a helper running as the shell
user reads just those log lines.

Readings that sparse would make the frost jump, so the app tracks the hinge
*between* readings (`fold/HingeTracker.kt`):

- **Speed** comes from consecutive readings of the *same* sensor. Those are
  a full, exact step apart, so the few degrees the two sensors disagree by
  never shows up as a change of speed.
- **Position** is each sensor's last reading carried forward at that speed,
  averaged across both sensors, and re-evaluated on every frame (120 Hz).
- **No new reading yet is information too.** A sensor logs as soon as it
  has moved a full step, so until it logs again the hinge can't be further
  than that. The estimate glides toward that limit and eases off as it gets
  close. A hand that slows down is met by a frost that slows down, rather
  than one that overshoots and pulls back.
- **The frost never moves backwards while you keep folding the same way.**
  A reading that lands slightly behind the estimate slows it down instead.

It costs almost nothing: no extra sensors are switched on, and the work is a
few arithmetic operations per frame while the overlay is up.

## Settings

The **Settings** sheet, top to bottom:

- **Status.** Whole-screen fold and Shizuku: on, off, or what's missing.
- **Screens.**
  - **Inner screen turns on (while opening).** 20–85°. A Z Fold switches
    by itself at ~85°, so the inner screen can light earlier than that but
    not later.
  - **Cover screen turns on (while closing).** Up to 120°, or *When shut*
    (the system's own switch).
  - **When you hold the phone part-folded.** Keep the frost (the picture
    under it stays live), or clear it after 3 s or 10 s.
- **Look.** Strength, blur, dimming and depth, as a percentage of the
  defaults; Reset look.
- **Fold.** The half that moves, which edge the cover-screen frost comes
  from, and the crease orientation.
- **Advanced** (collapsed):
  - Snapshot or Live blur. Live blur uses the system blur; One UI usually
    has it turned off.
  - Live picture under the frost (Shizuku capture).
  - Start instantly on a new screen.
  - Follow the real hinge angle.
  - Both screens at once (experimental dual-screen mode).
  - Diagnostics: sensor status, a hinge simulator for the preview, and
    **Copy sensor report** for bug reports.

## What Shizuku mode changes on the phone

Nothing persistent, and nothing physical: the hinge and sensors are only
read.

The one thing that changes system behaviour is the screen takeover angles.
As the hinge passes the angle you set, Duo Open tells the system the phone
is already in the next posture, so that screen lights at that moment:

- closing: *shut*, for the cover screen;
- opening: *half open*, for the inner screen.

This is a device-state *base state override*, the same as
`adb shell cmd device_state base-state 0`. Only which screen is used
changes.

Each override is held as briefly as possible. It is released:

- **Cover:** once the hall sensor reports the phone shut. The sensor HAL
  logs this as `folding_angle device_mode(0 -> 1)`. Not sooner: while
  closing, a Z Fold stays *half open* until it's shut, so releasing early
  would light the inner screen again for a moment.
- **Inner:** once the hinge is past the system's own switch at ~85°.
- **Either:** if you turn back, after 20 s at most, or when the service or
  the hinge reader stops.

On a Z Fold 8 Ultra the system does **not** drop an override by itself if
the helper is killed. Duo Open therefore records that one may be
outstanding, and clears it the moment the helper reconnects (~0.4 s,
Shizuku restarts it). A reboot also clears it.

If a screen ever seems stuck, open and close the phone once, or run
`adb shell cmd device_state base-state reset`. To turn the feature off,
set the cover slider to *When shut* and the inner slider to *At 85°*.

## Privacy

The fold needs a picture of the screen to bend.

- **Without Shizuku,** the accessibility service takes a screenshot as each
  fold phase starts.
- **With Shizuku,** the helper captures the display instead, with Duo Open's
  own overlay left out, and refreshes it while the fold plays.

Either way the picture lives in memory only while the overlay is up.
Nothing is stored, logged or sent anywhere, and the app has no network
permission. Screens the system marks secure (banking apps, DRM video) can't
be captured, and the effect simply doesn't play there.

For the hinge, the helper reads only the sensor HAL's tag
(`logcat -s sensors-hal`). Of those lines it parses only the two hinge
sensors' readings and the hall sensor's shut/open line; nothing else in the
log is read. The helper exits when the
service stops. None of it runs unless you authorise Duo Open in Shizuku.

## Known limits

- **A short wait at the start of a movement.** A sensor logs only once it
  has moved a full step, so the first reading of a fold arrives after ~5–10°
  of movement. From then on the frost follows continuously.
- **If you stop partway,** the frost may rest a few degrees ahead of the
  real hinge, short of where the next reading would have landed.
  Moving on picks up from there.
- **Holding the phone part-folded keeps the frost** by default; the
  picture under it stays live. To get the clear screen back in tent or
  laptop pose, set *When you hold the phone part-folded* to clear after a
  few seconds.
- **Samsung sometimes puts the phone to sleep when it's folded** (it depends
  on the app and on *Continue apps on cover screen*). Then the cover screen
  lights late or not at all, and its half of the fold isn't seen.
- **The accessibility service needs re-enabling after uninstalling.**
  Updating in place keeps it on.
- **Shizuku has to be started again after every reboot.**

## Reporting a problem

Open an issue with:

- your model and One UI version;
- **Settings → Copy sensor report**;
- `adb logcat -s DuoOverlay DuoAngleFeed DuoScreenSwitch sensors-hal` from
  one open and close.

## Build

```
./gradlew assembleDebug     # debug-signed
./gradlew assembleRelease   # signed with keystore.properties if present
```

Release signing reads `keystore.properties` in the project root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it, the
release build uses the debug key.

Handy adb commands:

- Enable the service:
  `adb shell settings put secure enabled_accessibility_services com.duoopen/com.duoopen.overlay.FoldOverlayService`
- Replay the effect: `adb shell am broadcast -a com.duoopen.DEMO`
- Watch it: `adb logcat -s DuoOverlay`

## Layout

```
res/raw/duo_unfold.agsl          fold shader (hinge line, moving side, eye) — app preview
res/raw/duo_unfold_lod.agsl      same geometry, mip-pyramid blur + cross-fade — overlay
fold/HingeTracker.kt             hinge angle between Samsung's sparse readings
fold/HingeAngleSource.kt         stops sensor, device posture and Shizuku readings → one angle
fold/TiltFollower.kt             per-vsync ease toward a fixed value or a per-frame estimate
fold/DuoShader.kt                uniforms, hinge→tilt mapping, fold placement
fold/Panels.kt                   inner vs cover panel from the display mode
shell/DuoShellService.kt         Shizuku helper: capture, hinge-sensor log reader, device-state override
shell/ShizukuBridge.kt           app side: state, permission, binder calls
shell/SamsungAngleFeed.kt        starts the hinge reader, feeds its readings in
overlay/FoldOverlayService.kt    accessibility service: one engine per lit display
overlay/PanelEngine.kt           per-display fold: capture + overlay + hinge tracking + hand-overs
overlay/FoldSurface.kt           snapshot surface, or live-blur strip windows
overlay/FoldOverlayView.kt       draws the snapshot through the shader (1/2.5-res layer), veils
overlay/DeviceStateFeed.kt       fold posture (CLOSED/TENT/HALF_OPENED/OPENED) from the device state
overlay/EarlyScreenSwitch.kt     screen takeovers at the chosen angles (closing: cover, opening: inner)
ui/                              Compose app: home + setup, Settings sheet, restricted-settings guide
settings/DuoSettings.kt          shared tuning (SharedPreferences + StateFlow)
```

## Galaxy Z Fold 8 Ultra notes

- **Displays.** Measured 2026-10-17 (SM-F976B, Android 17). The cover
  (1080×2520) is a second logical display flagged `FLAG_PRESENTATION`; the
  inner panel is 2256×2504. Display 0 swaps between them: to the inner at
  HALF_OPENED (~83–86° on an open), back to the cover only at CLOSED (~2°).
- **Hinge sensors.** Public `hinge_angle` is stops-only, sampled at ~5 Hz;
  its 0° arrives at ~10°. Samsung's `folding_angle` and `lid_angle_fusion`
  sample at 25 Hz while the hinge moves and need
  `com.samsung.permission.SSENSOR` (signature|privileged, not grantable over
  ADB). The sensor HAL logs them every 11° and 10° respectively. The last
  line of a close lands at ~5–10°; the hall sensor's
  `device_mode(0 -> 1)` line marks the actual shut.
- **Postures.** Opening goes CLOSED → TENT (~5°) → HALF_OPENED (~85°) →
  OPENED; closing goes from HALF_OPENED straight to CLOSED when shut.
- **Samsung's panel transition.** Samsung plays its own transition on the
  inner panel for ~0.4 s after it lights up (launcher fading in, no bars),
  so captures from then aren't used.
- **GPU time.** With the shader pyramid at 1/2.5 resolution, GPU time is ~5
  ms median and ~8 ms at the 99th percentile, on the inner panel at 120 Hz.

## Galaxy Z Fold 8 notes

- Measured 2026-09-26 (SM-F971B, Android 17). Display 1 is the inner panel
  (2448×1848), flagged `FLAG_PRESENTATION` and off whenever the phone is
  closed. On a normal open the OS turns the cover off and puts the inner
  panel under display 0. Its `CONCURRENT_INNER/OUTER_DEFAULT` states are
  only entered when an app asks for dual screen. The service accepts the
  second built-in display when that happens, because it matches the default
  display's name, "Built-in Screen".
- One UI has SurfaceFlinger window blur disabled, so **Live blur** is greyed
  out and only Snapshot is available.

## Related projects

- [marcoazeem/duo-open](https://github.com/marcoazeem/duo-open): the
  original Duo Open, for foldables in general, with a live-wallpaper mode
  and a lite edition.
- [joeconsorti/duo-fold-live](https://github.com/joeconsorti/duo-fold-live):
  a Fold-8-only take on the same idea.
- [ServerReset/duo-open](https://github.com/ServerReset/duo-open) and
  [nihal711/Z-Fold-Duo-TEST](https://github.com/nihal711/Z-Fold-Duo-TEST):
  other Galaxy Z Fold forks.
- [iamkeeler/FoldFX](https://github.com/iamkeeler/FoldFX): compositor blur
  behind a transparent overlay, no screenshots.

## Credits

- **[Duo Open](https://github.com/marcoazeem/duo-open)**: the app this fork
  is based on.
- **[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation)**:
  the frosted-glass fold shader (Metal → AGSL port).
- **[joeconsorti/duo-fold-live](https://github.com/joeconsorti/duo-fold-live)**
  (MIT): worked out shell-side display capture with layer exclusion, and
  reading Samsung's fold angle from the system log (through the Fold
  interactive wallpaper). Duo Open's Shizuku mode re-implements the capture
  and builds on the log idea. Thank you.
- **[Shizuku](https://github.com/RikkaApps/Shizuku)** (Apache-2.0): the
  ADB-assisted access behind live tracking and capture.
- **[AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)**
  (Apache-2.0): lets the app read its own window's layer id for capture
  exclusion, and reach the device-state API.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). These credits don't
imply the upstream authors endorse this app.

## License

MIT — see [LICENSE](LICENSE).
