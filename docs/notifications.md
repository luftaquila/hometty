# Notifications

## Enable notifications

1. Run `config`, open **Permissions and setup → Notification access**, and allow hometty.
2. Run `config notifications` and enable **Notifications in the terminal**.

Check that the next notification you receive appears in the terminal.

Notifications appear alongside shell output. Enable **Tap to open** to open them with a tap. Long-press a notification to choose **Open app** or **Hide this app's notifications**.

## Change the format

The default is:

```text
{app}: {title} - {text}
```

For example, a Messages notification can appear as `Messages: Alex - See you at 6!`.

| Placeholder | Content |
| --- | --- |
| `{app}` | App name |
| `{package}` | Package name |
| `{title}` | Notification title |
| `{text}` | Short text, falling back to expanded text |
| `{bigtext}` | Expanded text, falling back to short text |
| `{subtext}` | Additional notification text |

```sh
config set notifications.format '{app} · {title}: {text}'
```

The terminal adds a timestamp automatically.

## Per-app rules

Open **Notification rules** in the notification settings. Select an app to show or hide its notifications, change their color, or set a custom format. Leave the format empty to use the default.

The **Include** and **Exclude** fields match regular expressions against the title and message. For example, `(?i)meeting` matches “meeting” regardless of case. A matching exclude rule always hides the notification.

Turn off **Include apps without rules** to show only apps explicitly allowed by your rules.

If nothing appears, see [Notification troubleshooting](./troubleshooting.md#notifications-do-not-appear).
