# Architectural Blueprint: Automated WhatsApp Message & Media Dispatch Engine

A production-ready technical blueprint documenting the end-to-end architecture, state machines, Android OS workarounds, security vault, cloned-app routing, and live contact/group picker implemented in **Sandesh**, structured so it can be ported directly into any external Android (Kotlin / Jetpack Compose) application.

---

## 1. Core Architectural Challenges & Solutions

Building reliable unattended WhatsApp automation across modern Android (API 24–36) and Indian OEM ROMs (MIUI/HyperOS, One UI, ColorOS, Realme UI, OxygenOS, FuntouchOS/OriginOS) requires overcoming five platform barriers:

| Platform Barrier | Why Naive Approaches Fail | Architectural Solution Used |
| :--- | :--- | :--- |
| **Doze & OEM Sleep Guards** | `WorkManager` and `setExactAndAllowWhileIdle()` are throttled by 10–60+ mins by MIUI PowerKeeper, ColorOS Athena, and Samsung Sleeping Apps. | Schedule via **`AlarmManager.setAlarmClock()`**, which Android and OEM ROMs treat as a user-visible alarm immune to Doze deferral, backed by a boot/update rescheduler. |
| **Android 12–16 Background Activity Launch (BAL) Restrictions** | Calling `startActivity()` directly from a `BroadcastReceiver` in the background is silently blocked on Android 12+. | Bridge `AlarmReceiver` into a **`shortService` Foreground Service** (`DispatchForegroundService`) with a high-priority **`fullScreenIntent`** notification and `WakeUnlockActivity`. |
| **Locked Screen & Keyguard Barriers** | `requestDismissKeyguard()` works for swipe-only locks, but cannot bypass PIN, Password, or Pattern locks unattended. | Encrypt user-supplied PIN/Password/3×3 Pattern inside **Android Keystore (AES-256-GCM)**, swipe up via `AccessibilityService` gesture, enter PIN/Pattern/Password, zero memory immediately, and re-lock via `GLOBAL_ACTION_LOCK_SCREEN` after sending. |
| **Cloned WhatsApp & Multi-Account Variants** | Standard `whatsapp://send` intents target only User 0 (`com.whatsapp`) or hang on OEM "Choose Dual App" bottom sheets. | Enumerate all `UserHandle` profiles via `LauncherApps` + `UserManager`, route clones/groups/media via `Intent.ACTION_SEND`, and auto-tap the selected clone inside OEM Dual-App pickers via Accessibility. |
| **Media URI Expiry Across Reboots** | Raw Photo Picker (`PickVisualMedia`) or SAF `content://` grants expire across device reboots or gallery updates. | Copy selected media into app-private `filesDir/scheduled_media/` on schedule creation, serve via `FileProvider` `content://` URI at dispatch time, and garbage-collect unreferenced files after one-time sends. |

---

## 2. End-to-End Dispatch Execution Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                 AUTOMATED DISPATCH ENGINE — EXECUTION PIPELINE               │
└──────────────────────────────────────────────────────────────────────────────┘

  [ Room Database (SSOT) ]
   ScheduledMessageEntity (id, phone, name, isGroup, targetPackage,
                           targetUserSerial, body, mediaPath, mime, recurrence)
            │
            ▼
  ┌──────────────────────────────────────────┐
  │ 1. AlarmScheduler                        │  AlarmManager.setAlarmClock()
  │    + BootRescheduleReceiver              │  Re-arms on BOOT_COMPLETED,
  └──────────────────┬───────────────────────┘  QUICKBOOT_POWERON, UPDATE
                     │ Exact Trigger Millisecond
                     ▼
  ┌──────────────────────────────────────────┐
  │ 2. AlarmReceiver (BroadcastReceiver)     │  Acquires 15s PARTIAL_WAKE_LOCK
  └──────────────────┬───────────────────────┘  Calls startForegroundService()
                     │
                     ▼
  ┌──────────────────────────────────────────┐
  │ 3. DispatchForegroundService             │  foregroundServiceType="shortService"
  │    (BAL-Safe Bridge + 38s Watchdog)      │  • Verifies Accessibility enabled
  │                                          │  • Decrypts Keystore credentials
  │                                          │  • Registers DispatchCoordinator
  │                                          │  • Starts WakeUnlockActivity
  └─────────┬────────────────────┬───────────┘
            │                    │ After screen wake / unlock wait (<= 8.5s)
            ▼                    ▼
  ┌──────────────────┐  ┌──────────────────────────────────────────────────────┐
  │ WakeUnlock       │  │ 4. Target Intent Launcher                            │
  │ Activity         │  │  • Text-only Primary Contact:                        │
  │ • ShowWhenLocked │  │      ACTION_VIEW "whatsapp://send?phone=...&text=..."│
  │ • TurnScreenOn   │  │  • Media / Group / Cloned Profile:                   │
  │ • DismissKeyguard│  │      ACTION_SEND + FileProvider URI + EXTRA_TEXT     │
  │ • FULL_WAKE_LOCK │  └──────────────────────────┬───────────────────────────┘
  └──────────────────┘                             │
                                                   ▼
  ┌────────────────────────────────────────────────────────────────────────────┐
  │ 5. WhatsAppAccessibilityService (Event-Driven State Machine)               │
  │                                                                            │
  │  [Keyguard Active?] ──► Swipe Up ──► PIN / 3x3 Pattern Gesture / Password  │
  │         │                             └─► Immediately zero CharArrays      │
  │         ▼                                                                  │
  │  [OEM Dual-App Sheet?] ──► Match Primary vs Clone ("Dual/Clone/2nd") & Tap │
  │         │                                                                  │
  │         ▼                                                                  │
  │  [WhatsApp App Lock?] ──► Enter encrypted WhatsApp Lock PIN                │
  │         │                                                                  │
  │         ▼                                                                  │
  │  [Contact Picker?] ────► Set search text ──► Wait 550ms ──► Tap row + FAB  │
  │         │                                                                  │
  │         ▼                                                                  │
  │  [Chat / Media Preview?] ► Verify/fill caption or text                     │
  │                          ► Find real Send button (exclude voice/mic)       │
  │                          ► ACTION_CLICK (fallback: dispatchGesture center) │
  │         │                                                                  │
  │         ▼                                                                  │
  │  [Post-Send Cleanup] ──► Wait 800ms socket flush                           │
  │                      ──► performGlobalAction(GLOBAL_ACTION_HOME)           │
  │                      ──► If initially locked: GLOBAL_ACTION_LOCK_SCREEN    │
  └──────────────────────────────────────────┬─────────────────────────────────┘
                                             │
                                             ▼
  ┌────────────────────────────────────────────────────────────────────────────┐
  │ 6. Outcome Persistence & Recurrence Engine                                 │
  │  • Insert DispatchLogEntity (SUCCESS/FAILED, durationMs, diagnostic)       │
  │  • If ONCE + SUCCESS: mark SENT & delete unreferenced staged media file    │
  │  • If DAILY/WEEKLY/MONTHLY: compute next slot & re-arm AlarmScheduler      │
  └────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Live WhatsApp Contact & Group Picker Architecture

To eliminate manual typing of unsaved numbers or emoji-laden group names across Personal, Business, and Cloned WhatsApp accounts, the engine supports a dedicated **Picker Mode** (`WhatsAppPickerCoordinator` + `WhatsAppPickerHandler`) inside the same `AccessibilityService`:

```
  [ Composer Sheet ]
   User taps "Pick from WhatsApp" (Phone Mode or Group Mode)
          │
          ▼
  WhatsAppPickerCoordinator.launchSelectedWhatsAppForPicking()
   • Records targetPackage, targetUserSerial, isClone, isGroupMode (90s timeout)
   • Launches clone via LauncherApps.startMainActivity(component, userHandle)
     or primary via PackageManager.getLaunchIntentForPackage()
          │
          ▼
  User searches & opens any Chat or Group inside WhatsApp
          │
          ▼
  WhatsAppAccessibilityService -> WhatsAppPickerHandler
   ├── OEM Dual-App Picker? ──► Auto-selects Primary or Clone
   ├── Group Mode? ───────────► Reads conversation_contact_name ──► Emit + Return
   └── Contact Mode:
        ├── Header is raw phone? ──► Emit phone immediately ──────► Return
        └── Header is contact name:
             • Stores capturedChatName
             • Auto-peek: taps conversation_contact header
             • On Contact Info screen: scans phone_number / about_and_phone_number
               & visible text nodes (scrolls once if needed)
             • Extracts E.164 / 10+ digit number ──► Emit Name + Phone ──► Return
          │
          ▼
  MainActivity brought back (FLAG_ACTIVITY_REORDER_TO_FRONT | SINGLE_TOP)
   • SandeshViewModel collects SharedFlow<PickedWhatsAppRecipient>
   • Auto-populates ComposerDraft without losing typed message, media, or time
```

---

## 4. Detailed Component Specifications

### 4.1 Manifest, Permissions & XML Configs

#### Required Permissions
- **Alarms & Wake**: `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `VIBRATE`
- **Foreground & Alerts**: `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`
- **OEM Reliability**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- **Optional Helper**: `READ_CONTACTS` (only if offering a device phonebook picker alongside the WhatsApp picker)
- **Play-Policy Avoided Permissions**: Do **not** declare `READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, or `QUERY_ALL_PACKAGES`. Use `PickVisualMedia`, `OpenDocument`, and `<queries>`.

#### Package Visibility (`<queries>` for Android 11+ / API 30+)
```xml
<queries>
    <package android:name="com.whatsapp" />
    <package android:name="com.whatsapp.w4b" />
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="whatsapp" />
    </intent>
    <intent>
        <action android:name="android.intent.action.SEND" />
        <data android:mimeType="*/*" />
    </intent>
</queries>
```

#### Registered Components
| Component | Key Attributes | Purpose |
| :--- | :--- | :--- |
| `WakeUnlockActivity` | `showWhenLocked="true"`, `turnScreenOn="true"`, `excludeFromRecents="true"`, `launchMode="singleInstance"`, translucent theme | Turns display on over lock screen and invokes `KeyguardManager.requestDismissKeyguard()` |
| `AlarmReceiver` | `exported="false"` | Entry point for `AlarmManager`; acquires 15s `PARTIAL_WAKE_LOCK` and calls `startForegroundService()` |
| `BootRescheduleReceiver` | `exported="true"`, filters: `BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `com.htc.intent.action.QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`, `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` | Re-arms all `PENDING` schedules from Room after reboot, OEM fast-boot, app update, or alarm permission grant |
| `DispatchForegroundService` | `exported="false"`, `foregroundServiceType="shortService"` | BAL-safe foreground execution bridge with `onTimeout()` and 38s watchdog |
| `WhatsAppAccessibilityService` | `permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, `exported="true"` | Event-driven keyguard unlock, clone chooser, WhatsApp UI automation, and live recipient picker |
| `FileProvider` | `authorities="${applicationId}.fileprovider"`, `grantUriPermissions="true"` | Exposes staged media files from `filesDir/scheduled_media/` to WhatsApp packages |

#### `accessibility_service_config.xml`
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewClicked"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagReportViewIds|flagIncludeNotImportantViews"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="50"
    android:packageNames="com.whatsapp,com.whatsapp.w4b,com.android.systemui,com.android.keyguard,com.miui.aod,com.vivo.upslide,android,com.miui.securitycore,com.oplus.multiapp,com.coloros.safecenter,com.oplus.safecenter,com.samsung.android.da.daagent,com.vivo.doubleinstance" />
```
> **Banking-Safe Scoping**: Restricting `android:packageNames` strictly to WhatsApp, Business, SystemUI/OEM keyguards, and OEM Dual-App choosers (while omitting `flagRetrieveInteractiveWindows`) ensures the service never inspects unrelated apps such as banking or UPI apps. Cloned WhatsApp profiles keep the same package name under a secondary `UserHandle`, so they remain covered automatically.

---

### 4.2 Exact Alarm & Boot Recovery (`AlarmScheduler` & `BootRescheduleReceiver`)

1. **Primary Trigger API**:
   - When `AlarmManager.canScheduleExactAlarms()` is true (or `SDK_INT < S`), schedule with:
     ```kotlin
     val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
     alarmManager.setAlarmClock(info, pendingIntent)
     ```
   - Fallback to `setExactAndAllowWhileIdle` / `setAndAllowWhileIdle` if exact alarm permission is not yet granted.
2. **Unique `PendingIntent` Per Schedule**:
   - Action: `"<app>.ACTION_DISPATCH_$scheduleId"`, RequestCode: `scheduleId.toInt()`, Flags: `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`.
   - Allows independent Pause, Edit, Cancel, and multi-schedule arming without `PendingIntent` collisions.
3. **Reboot Catch-Up Policy**:
   - Future `PENDING` schedules -> re-arm directly.
   - Elapsed recurring schedules -> advance via `RecurrenceCalculator.computeNextOccurrence()` until strictly `> now`, persist updated timestamp, and arm.
   - Elapsed one-time schedules -> if missed within a **15-minute** grace window (e.g., phone rebooting at trigger time), arm for `now + 5s`; otherwise record `FAILED` ("Device was powered off during scheduled window").

---

### 4.3 BAL-Safe Foreground Bridge (`DispatchForegroundService` & `WakeUnlockActivity`)

1. **Immediate Foreground Promotion**: Calls `ServiceCompat.startForeground(...)` with `FOREGROUND_SERVICE_TYPE_SHORT_SERVICE` on API 34+ (`NotificationCompat.CATEGORY_ALARM` + `setFullScreenIntent(wakePendingIntent, true)`).
2. **Accessibility Pre-Check**: Before waking or launching WhatsApp, verifies `PermissionManager.isAccessibilityServiceEnabled()`. If disabled, logs `FAILED` immediately with actionable error text and stops.
3. **Keyguard Wait Loop**: Launches `WakeUnlockActivity` and polls `KeyguardManager.isKeyguardLocked` every 350ms for up to **8.5 seconds** while `WhatsAppAccessibilityService` dismisses the lock screen.
4. **38-Second Watchdog & `onTimeout()`**: If `DispatchCoordinator.current()` is still active after 38s (or `shortService` triggers `onTimeout()`), marks the schedule `FAILED`, clears sensitive state, posts a failure notification, and calls `stopSelf()`.

---

### 4.4 Hardware-Backed Keystore Credential Vault (`KeystoreCryptoManager` & `VaultPreferences`)

1. **Key Generation**: Creates a 256-bit AES key inside `AndroidKeyStore` (`BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, `randomizedEncryptionRequired = true`).
2. **Storage Format**: Encrypts raw PIN, Password, 3×3 Pattern node sequence (digits `0`–`8`), and optional WhatsApp App Lock PIN, persisting `"$ivBase64:$ciphertextBase64"` in Jetpack DataStore. Plaintext credentials are **never** written to disk, logs, or JSON backups.
3. **In-Memory Zeroing**: `VaultPreferences.decryptForDispatch()` returns `CharArray?` inside `DecryptedDispatchCredentials`. Immediately after submitting the PIN, Password, or Pattern gesture in `WhatsAppAccessibilityService`, `Arrays.fill(chars, '\u0000')` wipes every credential array from RAM.

---

### 4.5 Cloned WhatsApp Discovery & Intent Routing Matrix

#### Discovery (`WhatsAppInstanceDiscovery`)
Iterates `UserManager.userProfiles` and queries `LauncherApps.getActivityList(pkg, userHandle)` for `com.whatsapp` and `com.whatsapp.w4b`. Any profile where `userHandle != Process.myUserHandle()` is tagged `isClone = true` alongside its `userSerialNumber`. This detects:
- Xiaomi / Redmi / POCO **Dual Apps** (typically User 999)
- Samsung **Dual Messenger** (typically User 95)
- Oppo / Realme / OnePlus **App Cloner**
- Vivo / iQOO **App Clone**

#### Launch Strategy Matrix
| Target Scenario | Launch Intent Used | Accessibility Resolution Path |
| :--- | :--- | :--- |
| **Primary Profile + Individual Phone + Text Only** | `ACTION_VIEW` with `whatsapp://send?phone=<E164>&text=<encoded>` and `setPackage(pkg)` | Lands directly in Chat Screen $\rightarrow$ Verify text $\rightarrow$ Tap Send |
| **Primary Profile + Group OR Media Attachment** | `ACTION_SEND` (`EXTRA_STREAM` `FileProvider` URI + `EXTRA_TEXT` + `FLAG_GRANT_READ_URI_PERMISSION`) with `setPackage(pkg)` | Contact Picker search $\rightarrow$ Tap row (+ Next FAB) $\rightarrow$ Media Preview / Chat $\rightarrow$ Tap Send |
| **Cloned WhatsApp Profile (Any OEM)** | `ACTION_SEND` (`text/plain` or media MIME) with `setPackage(pkg)` | If OEM shows Dual-App chooser (`miui.securitycore`, `oplus.multiapp`, `daagent`, `doubleinstance`), auto-tap Clone entry $\rightarrow$ Contact Picker search $\rightarrow$ Send |

---

### 4.6 Event-Driven Accessibility State Machine (`WhatsAppAccessibilityService` & `AccessibilityNodeHelpers`)

Every `AccessibilityEvent` passes through a strict ordered guard pipeline with a **35-second session ceiling** and per-step debounce (`280–450ms`):

1. **Keyguard Unlock (`com.android.systemui`)**:
   - Dispatches upward swipe gesture (`82% Y -> 25% Y` at `50% X`, `260ms`) to reveal the bouncer.
   - **PIN**: Resolves digit buttons via view IDs (`key0`..`key9`, `digit_0`..`digit_9`, `num_0`..`num_9`) or visible text/contentDescription, taps digits at `160ms` intervals, then taps Enter/OK (`key_enter`, `ok`, `confirm_button`, `"Enter"`, `"OK"`, `"Done"`) if still locked.
   - **3×3 Pattern**: Locates `lockPatternView` / `lockPattern` / `pattern_view` bounds, computes the 3×3 cell centers for digits `0..8`, and dispatches one continuous multi-point `GestureDescription.StrokeDescription`.
   - **Password**: Locates editable `EditText`, invokes `ACTION_SET_TEXT`, and taps Enter/OK.
2. **OEM Dual-App Chooser**:
   - Scans visible nodes containing `"whatsapp"`. Selects nodes containing `"dual"`, `"clone"`, `"2nd"`, `"second"`, `"parallel"`, `"work"`, or `"twin"` when targeting a clone; excludes those keywords when targeting primary.
3. **WhatsApp App Lock**:
   - Detects `"WhatsApp locked"` or `fingerprint_prompt_title` and enters the encrypted WhatsApp PIN.
4. **Contact / Forward Picker**:
   - Detects `menuitem_search`, `search_src_text`, `contactpicker_row_name`, `"Send to"`, or `"Frequently contacted"`.
   - Opens search if collapsed, sets query (`recipientName.ifBlank { normalizedPhone }`), waits `550ms`, taps the matching non-`EditText` row, and checks `450ms` later for a forward/next FAB (`send` / `next_btn`).
5. **Send Verification & Multi-OEM Trigger**:
   - Verifies/fills `entry` or `caption` if blank.
   - Searches view IDs (`send`, `conversation_send_button`, `btn_send`, `entry_send` across `com.whatsapp` and `com.whatsapp.w4b`) and localized labels (`Send`, `Enviar`, `Envoyer`, `Kirim`, `Senden`, `भेजें`), **explicitly rejecting** any node whose `contentDescription` contains `"voice"`, `"audio"`, or `"mic"`.
   - Clicks via parent-walking `ACTION_CLICK` (up to 3 ancestor levels) with `dispatchGesture()` center-coordinate tap fallback.
6. **Post-Send Cleanup**:
   - Waits `800ms` for WhatsApp socket transmission $\rightarrow$ `performGlobalAction(GLOBAL_ACTION_HOME)` $\rightarrow$ waits `350ms` $\rightarrow$ if `wasLockedInitially && autoRelockAfterSend` (API 28+), invokes `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` $\rightarrow$ persists `DispatchLogEntity` and arms next recurring alarm.

---

### 4.7 Hybrid Banking & UPI Conflict-Prevention (`JitAccessibilityController` & `SandeshQuickTileService`)

Indian banking and UPI apps (GPay, PhonePe, Paytm, YONO, HDFC, ICICI) frequently query `AccessibilityManager.getEnabledAccessibilityServiceList()` and warn users if an automation service is enabled. Sandesh solves this with a 3-layer defense:

1. **Just-In-Time (JIT) Auto-Toggle (`WRITE_SECURE_SETTINGS`)**:
   - Granted once via ADB (`adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`) or Shizuku.
   - Keeps the Accessibility Service **always OFF when idle** (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`).
   - Uses a non-destructive read-modify-write helper (`addComponentToEnabledList` / `removeComponentFromEnabledList`) that preserves TalkBack, Switch Access, and password autofill services.
   - `DispatchForegroundService` and `SandeshViewModel.startWhatsAppPicker()` auto-enable the component right before launch (polling up to 1.5s for OS binding), and `WhatsAppAccessibilityService`, `WhatsAppPickerHandler`, `DispatchForegroundService`, and `MainActivity` `ON_RESUME` tear it back down immediately via `disableSelf()` + Settings removal.
2. **1-Tap Quick Settings Tile (`SandeshQuickTileService` & `QuickTileInstaller`)**:
   - Labeled `"Sandesh Auto-Dispatch"`. Works with zero ADB permissions via `AccessibilityService.disableSelf()` (API 24+) to pause automation in 1 tap before opening any UPI/banking app, or opens Accessibility Settings directly in Manual mode.
   - Includes a 1-tap **"Add to Quick Settings"** action inside the **Banking & UPI Conflict Shield** card using `StatusBarManager.requestAddTileService()` (Android 13+) or a guided 3-step fallback dialog (Android 12 & older), syncing placement state via `onTileAdded()` / `onTileRemoved()` into a live `Quick Tile Added ✓` badge.
3. **Package Scoping & Minimal Flags**:
   - Strict `android:packageNames` allowlist + removal of `flagRetrieveInteractiveWindows`.

---

### 4.8 High-Volume Scale & Reliability Hardening (50+ Messages/Day, 2-Year Horizon)

1. **Serialized FIFO Dispatch Queue (`DispatchCoordinator` + `DispatchForegroundService`)**:
   - Same-minute or burst alarms enqueue schedule IDs onto a deduplicated thread-safe FIFO queue (`DispatchCoordinator.enqueueSchedule()`).
   - `DispatchForegroundService` runs a single `workerRunning` drain loop that holds JIT Accessibility armed across the entire burst, processes each schedule sequentially (`35s` timeout each), and tears down Accessibility + refreshes the alarm window only after the queue empties.
2. **Rolling 250-Alarm OS Window (`AlarmScheduler.hydrateAlarmWindow`)**:
   - Android enforces `MAX_ALARMS_PER_UID = 500`. Instead of registering every future schedule, Room queries `getUpcomingPendingWindow(limit = 250)` ordered by `effectiveTriggerMillis`, recovers stale `DISPATCHING` rows (> 5 min), reconciles missed alarms, and registers at most 250 alarms with `AlarmManager`. Every dispatch, edit, delete, or boot re-hydrates the next slot automatically.
3. **Smart 1-Time Auto-Retry (`now + 60s`)**:
   - On a transient failure (`retryCount == 0 && allowRetry`), `SandeshRepository.recordDispatchOutcome()` keeps `status = PENDING`, increments `retryCount = 1`, sets `retryAtMillis = now + 60_000L` (leaving `scheduledTimeMillis` untouched so recurring anchors never drift), and re-arms the alarm. A second failure transitions cleanly to `FAILED` (or advances recurring schedules).
4. **Indexed Room v2 Schema & 90d/30d Storage Hygiene (`MIGRATION_1_2`)**:
   - Composite index `(status, scheduledTimeMillis)` on `scheduled_messages` and `(executedAtMillis)` on `dispatch_logs` keep 36,500+ row queries sub-10ms.
   - `SandeshRepository.runStorageHygiene()` (throttled to every 6 hours) deletes `dispatch_logs` older than **90 days** and cleans up staged media files for `FAILED` schedules older than **30 days**.
5. **Streaming JSON Backup & Month-31 Recurrence Anchor**:
   - `exportBackupToStream()` and `importBackupFromStream()` stream via Moshi `JsonWriter`/`JsonReader` + Okio `BufferedSink`/`BufferedSource` with constant heap usage across 2+ years of history.
   - `anchorDayOfMonth` prevents monthly day-31 schedules from permanently collapsing to day 28 after February (`Jan 31 -> Feb 28/29 -> Mar 31`).
   - `AccessibilityNodeHelpers.findRealSendButton()` includes a structural bottom-right composer Send button fallback (`[width >= 68%][height >= 72%]`) for WhatsApp UI updates that rename Send IDs.

---

## 5. Indian OEM Compatibility & Sideloading Notes

### Manufacturer Deep Links (`OemDetector`)
- **Xiaomi / Redmi / POCO (MIUI / HyperOS)**: `com.miui.securitycenter/...AutoStartManagementActivity` & `miui.intent.action.APP_PERM_EDITOR` (Autostart + Background Pop-ups + Battery "No restrictions").
- **Oppo / Realme / OnePlus (ColorOS / Realme UI / OxygenOS)**: `com.coloros.safecenter/...StartupAppListActivity` / `com.oplus.safecenter/...` (Auto-launch + Background Activity + Disable Sleep Standby Optimization).
- **Vivo / iQOO (FuntouchOS / OriginOS)**: `com.vivo.permissionmanager/...BgStartUpManagerActivity` / `com.iqoo.secure/...AddWhiteListActivity` (Background Power Consumption + Autostart + Lock Screen Display).
- **Samsung (One UI)**: `com.samsung.android.lool/...BatteryActivity` (Unrestricted Battery + Remove from Sleeping/Deep Sleeping Apps + Appear on Top).

### Android 13+ Accessibility & Sideloading Behavior
1. **Restricted Settings (Android 13+)**: Sideloaded APKs show *"Restricted setting"* on Accessibility until the user opens **App Info $\rightarrow$ `⋮` menu $\rightarrow$ Allow restricted settings**. Expose a direct App Info button inside Settings.
2. **Google Play Protect Enhanced Fraud Protection (India)**: Browser/File-Manager sideloads declaring `BIND_ACCESSIBILITY_SERVICE` are blocked by Play Protect unless installed via **ADB** (`adb install -r <apk>`) or Play Store distribution (with prominent Accessibility disclosure & Play Console declaration), or after pausing Play Protect scan during local testing.

---

## 6. Integration Checklist for Porting to Another App

1. **Manifest & Resources**: Copy permissions, `<queries>`, `WakeUnlockActivity`, `AlarmReceiver`, `BootRescheduleReceiver`, `DispatchForegroundService` (`shortService`), `WhatsAppAccessibilityService`, `SandeshQuickTileService`, `FileProvider`, `accessibility_service_config.xml`, `ic_tile_shield.xml`, and `file_paths.xml`.
2. **Core Engine Packages**: Port `PhoneNormalizer`, `RecurrenceCalculator`, `MediaStorageManager`, `KeystoreCryptoManager`, `VaultPreferences`, `WhatsAppInstanceDiscovery`, `OemDetector`, `PermissionManager`, `AlarmScheduler`, `DispatchCoordinator`, `WhatsAppPickerCoordinator`, `AccessibilityNodeHelpers`, `WhatsAppPickerHandler`, `JitAccessibilityController`, `SandeshQuickTileService`, and `WhatsAppAccessibilityService`.
3. **Database Hooks**: Wire `DispatchForegroundService`, `WhatsAppAccessibilityService`, and `BootRescheduleReceiver` to your target app's database/repository (`getScheduleById`, `getPendingSchedules`, `markDispatching`, `recordDispatchOutcome`).
4. **UI Entry Points**: Add the live **Permission Manager** screen (re-evaluated on `Lifecycle.Event.ON_RESUME`), **Banking & UPI Conflict Shield** card, Keystore Vault setup (PIN/Password/3×3 Pattern), target WhatsApp/Business/Clone selector chips, and the side-by-side **Pick from WhatsApp** buttons in your schedule composer.
