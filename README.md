# Sandesh Auto-Dispatch

Sandesh is an offline-first Android WhatsApp and WhatsApp Business scheduling app built with Kotlin and Jetpack Compose. It schedules text, image, video, and document dispatches, supports recurring deliveries, discovers cloned WhatsApp profiles, and includes accessibility-assisted automation for devices with different Android and OEM behaviors.

> **Important:** Sandesh automates third-party apps through Android Accessibility. It is intended for lawful, authorized personal or business use only. WhatsApp automation may conflict with WhatsApp terms or regional policy, and accessibility automation can stop working when WhatsApp or OEM software changes.

## Capabilities

- Schedule text-only or media messages for contacts and groups
- Discover and target:
  - WhatsApp
  - WhatsApp Business
  - Dual-app or cloned WhatsApp installations
  - Separate Android work profiles where available
- Choose once, daily, weekly, or monthly recurrence
- Pause, resume, edit, delete, and send scheduled entries immediately
- Maintain reusable message templates
- View dispatch history, outcomes, duration, and diagnostics
- Stage image, video, and document media inside app-private storage
- Capture live recipients from WhatsApp contact or group screens
- Select phonebook contacts with the optional contacts permission
- Use a hardware-backed Android Keystore vault for device lock credentials
- Unlock supported PIN, password, and 3×3 pattern locks during dispatch
- Use the Banking & UPI Conflict Shield to temporarily disable accessibility for safer banking app use
- Export and import schedules and templates as streaming JSON backups
- Re-arm pending alarms after reboot or app update
- Recover missed recurring schedules and retry selected transient failures
- Show a dedicated OEM checklist for Xiaomi, Samsung, Oppo/Realme/OnePlus, and Vivo/iQOO devices

## Screens

The Compose UI contains four main sections:

- **Queue** — schedule management and readiness status
- **Templates** — reusable categorized message bodies
- **History** — dispatch logs and failures
- **Settings** — permissions, WhatsApp instances, lock vault, backup/restore, and banking safety

## How Dispatch Works

1. `AlarmManager` registers exact alarm clocks for upcoming schedules.
2. `AlarmReceiver` starts the foreground dispatch bridge and acquires a short wake lock.
3. The app wakes and, if configured, unlocks the device.
4. The selected WhatsApp target is launched and its media or text is shared.
5. `WhatsAppAccessibilityService` navigates the WhatsApp UI, recipient picker, media preview, and send button.
6. The result is persisted in Room and recurring schedules are advanced to their next run.

For the full execution pipeline and state-machine design, see [`WHATSAPP_AUTO_DISPATCH_BLUEPRINT.md`](WHATSAPP_AUTO_DISPATCH_BLUEPRINT.md).

## Architecture

```text
app/src/main/java/com/example/
├── MainActivity.kt                 # Compose app shell and navigation
├── SandeshApplication.kt           # Room, repository, media, and preferences container
├── data/
│   ├── local/                      # Room entities, DAOs, and database
│   ├── media/                      # App-private staged-media storage
│   ├── model/                      # Recurrence, status, and backup models
│   └── repository/                 # Schedule, template, log, and backup operations
├── dispatch/
│   ├── AlarmScheduler.kt           # Exact alarms and rolling alarm hydration
│   ├── AlarmReceiver.kt            # Dispatch alarm entry point
│   ├── BootRescheduleReceiver.kt   # Reboot/update recovery
│   ├── DispatchForegroundService.kt# Wake/unlock and foreground execution bridge
│   ├── DispatchCoordinator.kt      # FIFO dispatch queue
│   └── RecurrenceCalculator.kt     # Daily, weekly, monthly, and month-anchor logic
├── oem/                            # OEM and Android permission detection
├── security/                       # Android Keystore encryption and credential vault
├── service/                        # Accessibility automation and Quick Settings tile
└── ui/                             # Compose screens, components, theme, and ViewModel
```

### Main Technologies

| Area | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose / Material 3 |
| Android SDK | Compile/target API 36; minimum API 24 |
| Build | Gradle Kotlin DSL / Android Gradle Plugin 9.1.1 |
| Kotlin | 2.2.10 |
| Persistence | Room 2.7.0 |
| Preferences | DataStore Preferences |
| Media | Coil 2.7.0 and app-private `FileProvider` storage |
| Serialization | Moshi 1.15.2 |
| Concurrency | Kotlin Coroutines |
| Navigation | Compose Navigation |
| Testing | JUnit, AndroidX Test, Espresso, Robolectric |
| Code generation | KSP |

## Requirements

- Android Studio with Android SDK 36
- JDK 11 or newer compatible with the project
- Android device/emulator running Android 7.0 (API 24) or newer
- WhatsApp or WhatsApp Business installed for dispatch testing
- A physical device is strongly recommended for exact alarms, accessibility, lock-screen, and OEM behavior

## Build and Run

Clone and open the repository in Android Studio:

```bash
git clone https://github.com/Senterprisehub/Sandesh-Auto-Dispatch.git
cd Sandesh-Auto-Dispatch
```

Build and install the debug APK:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

A successful Gradle build also copies the generated APK to:

```text
WA-Auto-Scheduler.apk
```

Build a release variant:

```bash
./gradlew assembleRelease
```

The release signing configuration expects:

- `my-upload-key.jks` at the project root, or
- `KEYSTORE_PATH` environment variable
- `STORE_PASSWORD`
- `KEY_ALIAS` environment variable? 

The build currently fixes the alias as `upload` and reads the store/key passwords from `STORE_PASSWORD` and `KEY_PASSWORD`.

Example:

```bash
export KEYSTORE_PATH=/absolute/path/to/my-upload-key.jks
export STORE_PASSWORD='your-store-password'
export KEY_PASSWORD='your-key-password'
./gradlew assembleRelease
```

## Configuration

No API key is required for the core local scheduling implementation.

The Secrets Gradle Plugin reads values from `.env`, with `.env.example` used as the default properties file. Add local secrets to `.env`; do not commit private credentials. The app currently declares Firebase services in Gradle only as an optional strategy and uses a warning for missing `google-services.json`.

## Permissions

Sandesh requests or checks the following capabilities:

| Capability | Purpose |
|---|---|
| Exact alarms | Schedule dispatches at precise times |
| Wake lock | Keep execution alive briefly while dispatching |
| Boot completed / package replace | Re-arm alarms after reboot or app update |
| Notifications | Display foreground and dispatch status |
| Full-screen intent | Wake a locked device on supported Android versions |
| Battery optimization exemption | Reduce OEM/Doze suppression |
| Contacts | Optional phonebook recipient selection |
| Accessibility | Navigate WhatsApp, unlock supported locks, and send messages |
| Quick Settings tile | Temporarily pause accessibility for banking/UPI apps |
| `WRITE_SECURE_SETTINGS` | Optional just-in-time accessibility toggling via ADB/Shizuku |

The app declares package visibility for WhatsApp and WhatsApp Business and reads image, video, and document files through Android document/media picker and app-private staging instead of broad storage permissions.

## Initial Setup

1. Install the app and grant notification access when prompted.
2. Open **Settings** and complete the live Permission Manager.
3. Grant **Accessibility** to `Sandesh`.
4. Grant exact-alarm and unrestricted-battery permissions if available.
5. Complete the OEM checklist for your phone manufacturer.
6. If Android 13+ reports **Restricted setting**, open App Info → ⋮ → **Allow restricted settings** → enable Accessibility.
7. Optionally configure the encrypted lock credential for unattended dispatch.
8. Optionally save a WhatsApp in-app lock PIN.
9. Discover WhatsApp, WhatsApp Business, and cloned instances.
10. Create a schedule from the Queue or Templates screen.

## Encrypted Lock-Screen Vault

Sandesh can store one of:

- No lock / swipe
- Numeric device PIN
- Text password
- 3×3 device pattern
- Optional separate WhatsApp App Lock PIN

Credentials are encrypted with AES-256-GCM using an Android Keystore key and stored as an initialization-vector/ciphertext pair. Decrypted values are used as character arrays and cleared after submission where possible. Lock credentials are not included in JSON exports.

> You should test locked-screen dispatch on a non-critical device. PIN/password/pattern automation is sensitive, device-specific, and must only be used with credentials you control.

## Banking and UPI Safety

Automation services can trigger warnings in banking and UPI applications. Sandesh includes:

- Package-scoped accessibility configuration
- A Quick Settings tile for a one-tap accessibility pause
- Optional just-in-time accessibility toggling
- Preservation of unrelated accessibility services
- Banking and UPI shield instructions

JIT toggling requires `WRITE_SECURE_SETTINGS`, typically granted with ADB or Shizuku. The app is designed to return the Sandesh accessibility service to idle OFF after a dispatch or test cycle.

## WhatsApp Clones and OEM Compatibility

The app discovers supported app profiles through `LauncherApps` and Android `UserManager`. Its launch and accessibility logic accounts for common MIUI/HyperOS, Samsung, ColorOS/Realme UI, OxygenOS, FuntouchOS/OriginOS, and standard Android variants.

Reliable behavior depends on the device’s OEM settings. You may need to enable:

- Autostart
- Unrestricted battery usage
- Background pop-up or background activity
- Lock-screen or appear-on-top permissions
- “No restrictions” for both battery and sleep settings
- WhatsApp/WhatsApp Business notification and overlay permissions where applicable

## Reliability and Storage

- A rolling alarm window limits OS-level alarms to avoid excessive registrations.
- A serialized dispatch queue processes same-time schedules in order.
- Android boot and package replacement receivers re-arm pending work.
- Recurring schedules advance until their next valid future occurrence.
- Missed one-time schedules follow the built-in reboot grace window.
- Room indexes optimize large queues and logs.
- Dispatch logs older than 90 days may be pruned.
- Old staged media left by failed schedules may be cleaned after 30 days.
- Backups are streamed with Moshi to avoid loading the entire history into memory.

## Testing

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

Run instrumentation tests when a device or emulator is attached:

```bash
./gradlew connectedAndroidTest
```

The project includes Robolectric, JUnit, Compose UI test, Espresso, and example instrumented test sources.

## Security and Privacy

- Schedule and template data is stored locally in the app’s Room database.
- Media is copied into app-private storage; staged files are linked with `FileProvider` only for dispatch sharing.
- Lock credentials are encrypted on-device by Android Keystore.
- JSON backups contain schedules and templates, not encrypted device credentials or secret data.
- The app includes no server-side message queue for the core offline implementation.
- Third-party WhatsApp or WhatsApp Business content remains subject to each app’s own privacy and security behavior.

Do not use credentials or accounts you do not own or are not authorized to control.

## Limitations

- WhatsApp UI automation is less robust than an official messaging API.
- WhatsApp UI updates, regional builds, app locks, and OEM overlays can break node matching or send actions.
- Android background-launch restrictions require Accessibility and foreground bridge support.
- A screen may remain visible or require a lock credential even with alarm wake support.
- Cloned apps and work profiles vary substantially between OEM implementations.
- Automation may fail if the required permissions or OEM settings are changed.
- WhatsApp may update or reject automated behavior independently of this project.
- The project is a local Android application, not an official WhatsApp integration.

## Troubleshooting

### Accessibility is restricted or will not enable

Open **App Info → ⋮ → Allow restricted settings**, then reopen Accessibility settings and enable Sandesh.

### Exact alarms do not fire

Confirm exact alarm permission, disable battery restrictions, and complete your device’s OEM autostart/background checklist.

### WhatsApp is not detected

Confirm WhatsApp or WhatsApp Business is visible in Android package visibility and installed in a discoverable profile. Rescan from Settings.

### A schedule is marked failed

Check dispatch history, Accessibility status, the selected WhatsApp instance, media availability, lock credentials, and recipient details.

### Dispatched media cannot be shared

Verify the staged media still exists and that WhatsApp has permission to open the provided MIME type. Re-select the file if the source URI became stale.

### Banking app warns about accessibility

Use the one-tap Sandesh Quick Tile or disable the Sandesh Accessibility service before using banking or UPI apps. Re-enable it only when you are ready to dispatch.

## APK

The Gradle build is configured to synchronize every successful debug or release assembly into:

```text
WA-Auto-Scheduler.apk
```

At the project root.

To inspect or install an existing APK:

```bash
adb install -r WA-Auto-Scheduler.apk
```

## Contributing

Contributions should preserve the app’s offline, privacy, accessibility-safety, and OEM-compatibility goals.

When contributing:

1. Keep scheduling and dispatch state transitions in the repository/coordinator layers.
2. Add unit coverage for recurrence, phone normalization, storage, and dispatch outcomes.
3. Keep Accessibility package scope strict and avoid broad interactive-window retrieval.
4. Never commit credentials, keystores, Firebase secrets, or `.env` files.
5. Test both locked and unlocked dispatch paths when possible.

## License

No license is currently declared in this repository. Until a license is added, ownership and reuse rights remain with the repository owner; obtaining explicit permission is required for redistribution or commercial use.
