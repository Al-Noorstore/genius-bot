# Hands-free accessibility update (experimental)

This source includes an explicit WhatsApp Send accessibility action.

## User flow
1. Enable Genius Bot Accessibility in Android Settings > Accessibility.
2. Open the intended WhatsApp chat and review recipient + message.
3. Invoke the exact voice/text command: `confirm whatsapp send`.
4. The command only attempts a click if the foreground package is WhatsApp or WhatsApp Business and an accessible, enabled Send control is found.

## Important
- It does not auto-send on its own, on a timer, or merely because a chat opened.
- The user must explicitly invoke the send-confirmation command after reviewing the recipient and message.
- WhatsApp UI accessibility labels differ by app version/language. If the Send control is not exposed, it returns a failure instead of guessing a screen coordinate.
- Delivery is not verified by this click; check WhatsApp's sent/delivered status.
- Accessibility must be explicitly enabled by the device owner.
- This archive has not been built or tested on a physical Android device in this environment.

## Build flavors
From the project root, use Android Gradle tooling:
- PRO/GGUF: `gradle assembleDebug`
- Lite: `gradle assembleDebug -PliteBuild=true`

The project must have its configured Android SDK/NDK and dependencies available.
