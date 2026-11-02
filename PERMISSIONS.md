# Permissions

Ciel asks for each permission only when you use the feature that needs it. The in-app **System → Permissions** screen shows live status and explanations. Nothing here is required for text conversation.

| Permission | Why | Asked when |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talk to your AI/search providers; detect offline mode | Install time (normal permissions) |
| `RECORD_AUDIO` | Voice commands; optional wake word | First time you tap the mic |
| `POST_NOTIFICATIONS` (Android 13+) | Reminders, confirmations while in other apps, task reports | Permissions screen |
| Accessibility service (*Ciel screen control*) | Read the screen and tap/type/scroll for tasks you request; floating STOP pill; accessibility-button stop | You enable it in Android Settings → Accessibility |
| `SYSTEM_ALERT_WINDOW` | Floating Ciel above other apps | When you turn on Floating Ciel |
| Notification access | Summarise/reply to incoming messages (kept in memory only) | Permissions screen |
| `READ_CONTACTS` | Resolve "message John" to a number/email | Permissions screen |
| `SEND_SMS` | Send texts after you approve them | Permissions screen |
| `SCHEDULE_EXACT_ALARM` | Reminders on time (otherwise up to a few minutes late) | Permissions screen |
| `FOREGROUND_SERVICE_MICROPHONE` | Wake-word listening as a visible foreground service | When you enable the wake word |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Floating Ciel bubble with emergency stop | When you enable Floating Ciel |
| `RECEIVE_BOOT_COMPLETED` | Re-arm reminders after restart | Install time |

## What Ciel will not do

- Bypass Android security, grant itself permissions, or hide its activity (the accessibility service, mic and overlay are always visibly indicated).
- Act on instructions found in web pages, screens, notifications, games or documents.
- Perform purchases, send messages, delete things, or change security/account settings without your explicit approval of that specific action.
- Solve CAPTCHAs or human-verification checks, bypass anti-cheat, or play competitive multiplayer for you.

## Android limitations (honest list)

- **Always-on wake word**: Android 14+ only allows starting a microphone foreground service while the app is in the foreground, so after a reboot you must open Ciel once. Battery savers may stop the service. Recognition of the word "Ciel" depends on the offline model; extra spellings ("seal") can be configured.
- **Global double-tap gesture**: Android does not let an accessibility service detect an arbitrary double-tap anywhere without taking over all touch input (touch exploration). Ciel therefore provides: double-tap on the floating orb, a floating STOP pill during automation, the system Accessibility button/shortcut, a notification action, "Ciel, stop", and in-app buttons.
- **Lock screen**: the assistant gesture works where your phone's launcher allows assistants on the lock screen; Ciel does not unlock the device.
- **Speech recognition in the background** after the wake word depends on your phone's recognition service and must be verified on each device.
