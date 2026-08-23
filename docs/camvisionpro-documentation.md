# CamVisionPro — Project Documentation

A Bluetooth-connected camera control stick paired with a custom Android app. The app applies real-time computational imaging looks (built on adjustable parameters like tone curve, saturation, contrast, and warmth) to the phone's own camera, while the hardware provides tactile, in-the-moment control — browsing presets, tuning their values, and capturing/saving — without needing to touch the phone screen.

---

## 1. Product Vision

- The phone's own camera captures the photo — no separate camera sensor in the stick.
- All image processing happens in software, on the phone.
- The hardware (ESP32 + OLED + joystick/encoder/buttons) is a pure input/output control surface — it holds no meaningful state of its own and has no concept of "presets."
- **Presets are created and permanently saved through the app** (deliberate, precise work). **Hardware tuning is live, in-memory only** (reactive, in-the-moment experimentation) — nothing is written to persistent storage unless the user explicitly saves it through the app.
- Why hardware matters: photography is physical. Serious tools use dials and buttons during the act of shooting, not touchscreen menus. The app is the studio where looks are built; the stick is how you react to a scene and shoot without breaking focus.

---

## 2. System Architecture

```
┌─────────────────────┐         BLE (two-way)         ┌──────────────────────┐
│   ESP32-CAM          │ ─────────────────────────────▶│  Android App          │
│   (control hardware) │◀───────────────────────────── │  (camera + processing │
│                       │                                │   + storage)          │
└─────────────────────┘                                └──────────────────────┘
   OLED, PCF8574                                          CameraX, OpenCV,
   buttons, rotary                                         Room database
   encoder — no local
   preset state
```

**Division of responsibility**
- **ESP32 (hardware):** reads physical inputs, sends discrete control events over BLE, renders whatever text the phone sends it. No processing, no preset storage, no decision-making about what values mean.
- **Android app (software):** owns the camera, all image processing (OpenCV), all preset data (Room database), and all interaction logic (what a button press currently means, depending on menu state).

---

## 3. Hardware

### 3.1 Board
ESP32-CAM (WiFi + BT SoC, based on an ESP32-D0WD-V3 chip). Chosen for its low cost and built-in BLE/WiFi — the onboard camera module is **not used**; the phone's camera is the only image source.

### 3.2 Final Pin Configuration

**Direct ESP32 GPIOs:**
| Function | Pin | Notes |
|---|---|---|
| OLED SDA | GPIO13 | Shared I2C bus |
| OLED SCL | GPIO15 | Shared I2C bus |
| Rotary encoder CLK | GPIO14 | Direct, for fast/reliable quadrature reads |
| Rotary encoder DT | GPIO2 | Direct |

**PCF8574 I2C GPIO Expander (address 0x20, shares the OLED's SDA/SCL bus):**
| Function | Expander Pin |
|---|---|
| Up (navigate) | P0 |
| Down (navigate) | P1 |
| Select | P2 |
| Exit | P3 |
| Encoder click | P4 (wired, currently unused in firmware) |
| Shutter button | P5 |

### 3.3 Pins deliberately avoided, and why
- **GPIO16** — confirmed crash trigger. Caused a `LoadProhibited` Guru Meditation panic at boot when used as a digital input (shutter button). Root cause traced to code-structure issues in early BLE setup, compounded by this pin; abandoned entirely once found.
- **GPIO4** — tied internally to the ESP32-CAM's onboard flash LED. Its driver circuit fights the weak internal pull-up resistor, causing unreliable "stuck LOW" reads (manifested as a shutter button that appeared permanently pressed). Unusable as a general digital input on this board.
- **GPIO12** — controls flash voltage selection at boot (MTDI strapping pin). External circuitry (OLED SDA) pulling it HIGH during boot caused the ESP32 to assume the wrong flash voltage, breaking the upload/flash process entirely (`Failed to communicate with the flash chip`). Avoided for any use.
- **GPIO0, GPIO1, GPIO3** — reserved for flashing/Serial (boot mode select, UART TX/RX). Never used for peripherals, to preserve the ability to reflash and use Serial Monitor for debugging.

### 3.4 Power
- **During development:** powered via the USB-to-serial programmer board (5V/GND), which also provides the TX/RX/GPIO0 connections needed for flashing and Serial Monitor.
- Only one power source should ever be connected at a time (avoid powering from both the programmer board and a separate source simultaneously — this caused instability during testing).
- A smoothing capacitor (100–470µF) across 5V/GND, placed directly at the ESP32-CAM's power pins, was tested as a mitigation for a BLE-init crash; it did not resolve that particular crash (which turned out to be a code-structure issue), but remains reasonable general practice for stability, especially once on battery power.
- **Planned for the finished stick:** LiPo battery + TP4056 charge/protection module + inline power switch. A boost converter to a clean 5V may be needed depending on the specific ESP32-CAM board's battery input tolerance.

### 3.5 Physical interaction model
- **Up / Down (PCF8574 buttons):** context-sensitive navigation.
  - In preset-browsing mode: scrolls through the preset list; each highlighted preset is auto-applied live to the current photo preview.
  - In parameter-tuning mode: scrolls through which parameter (Curve, Saturation, Contrast, Warmth) is currently selected.
- **Select:** enters parameter-tuning mode for the currently highlighted preset.
- **Exit:** returns to preset-browsing mode.
- **Rotary encoder:** adjusts the value of whichever parameter is currently highlighted, live.
- **Shutter button:** short press = capture (or retake, if currently viewing a result); long press (~800ms) = save the current result to the phone's gallery, with "Saved!" confirmation on both the OLED and the app.
- **OLED:** always reflects the app's current state — "Not connected" when idle, "Connected!" briefly on pairing, the live preset/parameter list during use, "Saved!" briefly on save, and whatever text the app most recently pushed to it otherwise.

### 3.6 Rotary encoder notes
Cheap mechanical rotary encoders exhibit **contact bounce** — the physical contacts don't make/break cleanly, causing rapid spurious HIGH/LOW flicker for microseconds around each detent. This can cause a naive edge-detection read to misjudge direction or duplicate steps.

Mitigations applied, in order of effectiveness:
1. **Full quadrature state-table decoding** (tracking CLK+DT as a combined 2-bit state each read, only counting a step on specific valid transition patterns) instead of only checking DT at the instant CLK changes.
2. **Double-read with a ~50µs settle delay**, discarding the read entirely if the two samples disagree (filters out mid-bounce noise).
3. **Deprioritizing PCF8574 button polling** to every ~50ms instead of every loop iteration, so the encoder (on direct, fast GPIOs) gets far more uninterrupted CPU time between the comparatively slow I2C button reads.

Residual imperfection remains (rare missed step or reversed read at fast turns) — judged acceptable given the low stakes of adjusting a preset value versus the cost of chasing full elimination of a mechanical hardware limitation.

**Important technical finding:** reading a rotary encoder's CLK/DT lines *through* the PCF8574 I2C expander did not work reliably at all (stuck-HIGH reads), even with external pull-up resistors added. I2C transaction latency is too slow relative to encoder signal speed. The encoder must be on direct, fast GPIOs; only simple, human-timescale buttons are suitable for the I2C expander.

---

## 4. Firmware (ESP32)

### 4.1 Libraries used
- `Wire.h` — I2C
- `Adafruit_GFX` / `Adafruit_SSD1306` — OLED display
- `BLEDevice` / `BLEServer` / `BLEUtils` / `BLE2902` — BLE peripheral (Espressif's ESP32 BLE Arduino library)
- `PCF8574` (Renzo Mischianti's library) — I2C GPIO expander

### 4.2 BLE service structure
Two characteristics under one service:
- **Control characteristic** (`READ | NOTIFY`) — ESP32 → phone. Carries all hardware input events.
- **Display characteristic** (`WRITE`) — phone → ESP32. Carries text for the OLED to render.

A `BLE2902` descriptor is required on the notify characteristic to enable push notifications (rather than requiring the phone to poll).

### 4.3 Message protocol (ESP32 → phone, over the control characteristic)
Plain text, colon-delimited:
- `nav:up` / `nav:down` — navigation event
- `select:1` — select pressed
- `exit:1` — exit pressed
- `encoder_delta:N` — encoder moved, N is +1 or -1
- `shutter:1` — shutter short-press (capture/retake)
- `save:1` — shutter long-press (save)

### 4.4 Display protocol (phone → ESP32, over the display characteristic)
Plain UTF-8 text, written directly; the ESP32 renders whatever it receives verbatim via `onWrite()`. No structure or parsing on the firmware side — all formatting (preset lists, highlighting with `>`, rounded parameter values) is done on the phone before sending.

### 4.5 Key firmware behaviors
- `onConnect`/`onDisconnect` callbacks update a `deviceConnected` flag and briefly show "Connected!" / revert to "Not connected" on the OLED.
- `loop()` returns early (with a short delay) whenever `deviceConnected` is false, so no polling/BLE work happens while idle.
- Encoder polling is unconditional and delay-free every loop iteration; PCF8574 button polling is time-gated to ~50ms intervals to avoid stealing CPU time from the encoder.
- Shutter short/long-press logic uses edge detection plus a press-duration timer (800ms threshold) to distinguish capture/retake from save.

---

## 5. Android App

### 5.1 Stack
- Kotlin, traditional Views (XML layouts + `ActivityMainBinding`), not Jetpack Compose.
- CameraX for camera capture (`ImageCapture` use case; a `PreviewView` for live framing).
- OpenCV (Android SDK, via Maven Central) for all image processing.
- Kotlin Coroutines for background processing (`Dispatchers.Default` for OpenCV work, `Dispatchers.IO` for database work), keeping the UI thread unblocked.
- Room for persistent preset storage.
- Android's native `BluetoothGatt`/`BluetoothLeScanner` APIs for BLE (no third-party BLE library).

### 5.2 Capture & live-tuning pipeline
1. User captures a photo via CameraX (`ImageCapture`).
2. The saved JPEG is loaded, downscaled (half resolution) for responsive live processing, and converted to an OpenCV `Mat`, then held in memory (`capturedMat`) — **captured once**, never re-read from disk during a session.
3. Every time the active preset or a parameter value changes, `reprocessAndShow()` re-runs the *entire* processing pipeline against the same in-memory `Mat`, on a background coroutine, and updates the displayed `ImageView` on the main thread.
4. This is what makes tuning feel live: no re-capture, no disk I/O per adjustment — just re-running OpenCV math on a frame already in RAM.

### 5.3 Image processing model
A single parameterized function, `applyProcessing(src, params)`, replaces earlier separate per-mode functions. `ProcessingParams` holds:
- `curveStrength` (Double) — tone-curve exponent; higher = more aggressive S-curve (crushed shadows, rolled-off highlights).
- `saturationMultiplier` (Double) — HSV saturation channel scaled via `Core.multiply` (not `Mat.mul`, which squares values and causes 8-bit overflow/visual corruption — a real bug encountered and fixed early on).
- `contrastValue` (Int, -100 to 100) — applied via `Mat.convertTo` with an alpha multiplier.
- `warmthValue` (Int, -100 to 100) — shifts the Red channel up and Blue channel down (or the reverse) via per-channel `Core.add`/`Core.subtract`.

A preset is simply a named instance of these four values — built-in presets (Moody, Colorful) and user-created ones are structurally identical.

### 5.4 Data model & persistence
- `Preset` — a Room `@Entity` (id, name, curveStrength, saturationMultiplier, contrastValue, warmthValue).
- `PresetDao` — insert/update/delete/getAll.
- `AppDatabase` — Room database, singleton via `getInstance(context)`.
- **Working memory:** `presetList` (a `MutableList<Preset>` loaded from Room on launch) is what the hardware reads and mutates live during a session. Changes made via the encoder/buttons are **not** written back to Room automatically.
- **Persistence boundary:** only explicit actions in the in-app Preset Manager screen (Save to Selected / Save as New / Delete) write to Room. This is a deliberate design decision — hardware tuning is disposable/exploratory; app-side edits are the only way to make a change permanent.
- On first launch, if the database is empty, default presets (Moody, Colorful) are seeded automatically. Deleting all presets and reopening the app will re-seed defaults (current behavior; flagged as a possible future refinement if "don't re-seed after user deletes everything" is desired).

### 5.5 Menu state machine (drives both the OLED and the live preview)
```kotlin
enum class MenuMode { BROWSING_PRESETS, TUNING_PARAMETERS }
```
- **BROWSING_PRESETS:** Up/Down move `highlightedPresetIndex`; each move immediately sets `currentMode` and calls `reprocessAndShow()` (live preview updates as you browse — no explicit "apply" step). Select enters tuning mode for the highlighted preset.
- **TUNING_PARAMETERS:** Up/Down move `highlightedParameterIndex` (cycling Curve → Saturation → Contrast → Warmth). The encoder adjusts the currently highlighted parameter's value directly on the in-memory `Preset` object, immediately reflected in both the OLED text and the live photo preview. Exit returns to browsing mode.
- `updateOledMenu()` formats the current state into a display string (with a `>` prefix marking the highlighted line) and sends it via `sendDisplayUpdate()`. Numeric values are rounded (`String.format("%.2f", ...)`) before display to avoid floating-point noise cluttering the small OLED screen.
- Menu state resets to `BROWSING_PRESETS` on every new capture and on retake, keeping hardware and OLED in sync with the actual photo lifecycle.

### 5.6 BLE integration (`BleManager.kt`)
- Scans specifically for a device named `"CamStick"`; connect is user-triggered via an explicit **Pair button** (not fully automatic), with hidden/shown state driven by an `onConnectionChanged` callback.
- On connect: negotiates a larger MTU (`requestMtu(512)`) *before* calling `discoverServices()` — the default ~20-23 byte MTU was silently truncating longer messages (an early, hard-to-diagnose bug: messages like `mode:moody,intensity:100` were being cut to the first ~20 bytes).
- Auto-reattempts scanning on unexpected disconnect.
- All Bluetooth calls are guarded with explicit runtime permission checks, version-aware (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on API 31+, legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` below that), since the project's minSdk is below 31 and needed to support both permission models.
- `parseAndNotify()` routes incoming text messages to typed callbacks (`onNavUp`, `onNavDown`, `onSelect`, `onExit`, `onEncoderDelta`, `onShutter`, `onSave`), wrapped in try/catch so a malformed or unexpected payload logs an error instead of crashing the app.
- `sendDisplayUpdate()` writes to the display characteristic, using the modern `writeCharacteristic(characteristic, data, writeType)` API on API 33+ and falling back to the deprecated single-argument form below that.

### 5.7 Preset Manager screen
A separate `PresetManagerActivity`, opened via a paintbrush icon next to the capture button (launched with `registerForActivityResult` so `MainActivity` can reliably refresh its in-memory preset list only when returning from this screen — not on every generic `onResume()`, which was found to incorrectly overwrite unsaved hardware-tuning sessions).

Current UI: a spinner to pick an existing preset, editable text fields for name and all four parameters, and three actions — Save to Selected Preset, Save as New Preset, Delete Selected Preset. Functional and tested end-to-end; visual polish (proper sliders, preview thumbnails) deferred.

### 5.8 Saving photos
Uses `MediaStore` (not app-specific storage) so saved photos appear in the phone's actual Gallery app, under `Pictures/CamVisionPro/`. Triggered either by the on-screen download button or the hardware's long-press-shutter gesture; confirmed via a `Snackbar` on the app and a "Saved!" message on the OLED.

---

## 6. Notable Bugs Found & Fixed (chronological highlights)

| Symptom | Root cause | Fix |
|---|---|---|
| Neon/glitched, noisy processed image | `Mat.mul(other, scale)` used for saturation multiplies channel by itself (squares values), overflowing 8-bit range | Switched to `Core.multiply(channel, Scalar(value), channel)` |
| Persistent reboot crash, `LoadProhibited` panic right at BLE init | Advertising started via `pAdvertising->start()` instead of the documented `BLEDevice::startAdvertising()`; missing `setScanResponse`/`setMinPreferred` calls present in working examples | Matched code structure exactly to Espressif's official `BLE_notify` example |
| Shutter button reads "always pressed" | GPIO4 shares circuitry with the onboard flash LED, which fights the pin's weak internal pull-up | Moved shutter button off GPIO4 |
| ESP32 fails to boot at all, `LoadProhibited` crash right after `pinMode()` on the shutter pin | GPIO16 — later confirmed as a specific crash trigger tied to that pin | Moved off GPIO16 entirely, never reused |
| Received BLE text is garbled (`P????`, then 4-byte garbage) | Leftover demo counter code (`value++`, 4-byte payload) from a copied reference example was still running in `loop()`, overwriting real messages | Deleted the leftover block |
| Long messages truncated to ~20 characters on the Android side | Default BLE MTU (~20-23 bytes) too small for messages like `mode:moody,intensity:100` | `gatt.requestMtu(512)` before `discoverServices()` |
| App crash: `SecurityException: Need BLUETOOTH permission` | `startScan()` called before runtime permission was granted | Moved the call to only fire inside the permission-granted callback |
| App crash: `NullPointerException` on `BluetoothLeScanner.startScan` | Phone's Bluetooth was off, or scanner object was null | Added `isEnabled` and null checks before scanning |
| Preset intensity appeared shared across presets | Both the ESP32 firmware and the Android app originally tracked a single global intensity value, not one per preset/mode | Redesigned as per-preset stored parameters (later evolved into the full `ProcessingParams`/`Preset` model), with hardware made "dumb" (sends raw deltas, not mode-aware absolute values) |
| Upload fails: `Failed to communicate with the flash chip` | GPIO12 (flash-voltage boot-strap pin) was carrying OLED SDA, interfering with the flash handshake | Moved OLED off GPIO12 entirely; GPIO12 avoided for all future use |
| Rotary encoder unreadable through the PCF8574 (stuck HIGH) even with external pull-ups | I2C read latency too slow to catch fast encoder signal transitions — a timing problem, not a signal-strength problem | Moved encoder CLK/DT to direct ESP32 GPIOs; kept only simple buttons on the PCF8574 |
| Encoder direction occasionally misread | Mechanical contact bounce | Full quadrature state-table decoding + double-read debounce + deprioritized button polling |
| Encoder direction consistently backwards | Quadrature logic was internally correct but mapped to the physically opposite direction for this specific encoder's wiring orientation | Swapped which transition pattern increments vs. decrements |
| Unsaved hardware-tuned values silently reverted | `onResume()` unconditionally reloaded presets from Room every time the app regained foreground, overwriting in-memory changes | Removed the automatic `onResume()` reload; presets now only reload right after explicitly visiting the Preset Manager screen |
| First-install permission popups showed inconsistently / needed reopening the app | Camera and Bluetooth permissions were requested sequentially in separate calls, confusing the OS permission dialog queue on a fresh install | Combined into a single `RequestMultiplePermissions()` call covering camera + Bluetooth + location together |
| App appears connected to hardware but `BleManager`'s own connection never completes; had to toggle phone Bluetooth off/on | Manually pairing "CamStick" through the phone's native Bluetooth settings creates an OS-level bond that occupies the ESP32's single BLE connection slot, separate from the app's own `connectGatt()` | Forget/unpair the device from phone Bluetooth settings; always connect exclusively via the app's own Pair button |

---

## 7. Current Status (as of this document)

**Fully working, end to end:**
- Camera capture, in-memory live reprocessing, full `Preset`/`ProcessingParams` model
- Room-backed persistent preset storage, with a functional (if visually basic) create/edit/delete screen
- Complete hardware control surface: OLED (I2C), PCF8574-based buttons (Up/Down/Select/Exit/Shutter), direct-GPIO rotary encoder
- Two-way BLE: hardware events flow to the phone; phone-formatted menu text flows back to the OLED
- Full interaction loop: browse presets (live preview updates as you scroll) → Select → tune parameters live via encoder → Exit → capture/retake (short press) → save to Gallery (long press, with confirmation on both OLED and phone)
- Reliable pairing (via explicit Pair button) and permission handling across Android versions

**Deferred / not yet built:**
- **Night mode** — the originally planned fourth processing mode (multi-frame burst capture + alignment + stacking); acknowledged as the harder computational-imaging piece and intentionally deferred in favor of validating the full hardware/software loop first with simpler single-frame modes.
- Physical enclosure / final stick housing; move from breadboard to a soldered veroboard, then eventually a custom PCB (KiCad/EasyEDA identified as candidate free tools, deliberately not started until the circuit is fully finalized).
- Battery power integration (LiPo + TP4056 + power switch) — currently desk-powered via the programmer board only.
- Visual/UX polish: custom download icon, nicer Preset Manager UI (sliders instead of raw text fields), possible preset preview thumbnails.
- Version control: a git repository has been recommended (two folders — `android-app/` and `esp32-firmware/` — under one repo) but not yet confirmed set up.

---

## 8. Positioning / Pitch Notes

**Why hardware, in one line:** *"The app is where you decide — the hardware is where you feel."* Preset creation is deliberate work suited to a screen; using a preset while actually shooting is reactive, in-the-moment work suited to physical dials your hand already knows.

**One-paragraph pitch:**
> CamVisionPro pairs a phone's camera with a physical control stick that lets creators apply and fine-tune computational photography looks — like film-grade tone curves and color grading — in real time, without touching a screen. Presets are built and saved in the companion app, then browsed and tuned live on the hardware itself, so shooting stays a tactile, in-the-moment experience rather than a menu-diving one. It's the physical control layer serious content creators want, built on a phone they already own instead of a $1,000 camera body.

**Honest positioning note:** this is a tool for people who want real control, not a "simpler than your phone's camera" mass-market pitch. Onboarding (pairing, menu navigation, physical dials) has more steps than tap-and-shoot — that's a deliberate tradeoff for a serious-creator audience, not an oversight.
