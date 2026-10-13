Genius Bit PRO v4.18 — source project

Siri-style voice update included in this source:
- Custom assistant wake name saved in Settings (default Genius Bit).
- Floating orb service with Android overlay permission.
- Background command dispatcher for common app launches, Home, dialer, and PhoneAgent tasks when Accessibility is enabled.
- Complex chat/AI requests fall back to the existing MainActivity pipeline; this is intentional because many existing AI/chat functions depend on the activity.

Beginner setup on Android:
1. Install the APK built from this source and open Genius Bit once.
2. Settings > Offline Brain: enable Hands-free, grant microphone permission, and leave the foreground mic notification enabled.
3. Enable Floating voice shortcut and allow Display over other apps when Android opens that settings page.
4. Optionally save a custom assistant name. Say “Hey Genius Bit” (or your chosen name), wait for the orb, then speak the command.
5. For screen taps/typing/multi-step automation, enable Genius Bit under Android Settings > Accessibility.
6. If listening stops, check Android battery settings and allow Genius Bit background activity where the device offers that option.

Build notes:
- LITE: gradle assembleDebug -PliteBuild=true
- PRO: gradle assembleDebug
- This source archive does not include Gradle wrapper scripts or all native llama.cpp source files. Use the project's configured Android/Gradle environment and restore any expected native source dependencies before building.
- Do not treat this source edit as a verified APK build: build and test on a real Android device before distribution.


VOICE / ACCESSIBILITY / REMINDERS ADDITIONS
------------------------------------------
- Accessibility service is registered with the system binding permission. The user must enable it manually in Android Settings > Accessibility > Genius Bit. Screen reading is limited to text exposed by the active app's accessibility tree; secure screens and some canvas/video content cannot be read.
- Background commands now include: read screen, scroll up/down, back, tap <visible label>, type <text>, screenshot, and WhatsApp voice/video call <contact>. WhatsApp calling opens the matched contact and asks PhoneAgent to use the visible call control; it depends on Contacts permission, Accessibility being enabled, WhatsApp UI, and a functioning AI backend. If unavailable, the contact chat opens and the user can place the call manually.
- One-time reminders support examples: 'remind me in 10 minutes to drink water', 'remind me tomorrow at 6 pm to study', 'remind me to study at 6 pm', and 'remind me on 2026-10-20 at 5:30 pm to study'. Use 'list reminders' or 'cancel all reminders'. Android notification permission and exact-alarm settings can affect delivery.
- No automated build or physical-device test was possible in this environment because an Android SDK/Gradle installation is not present. Build and test on a development machine before installing.
