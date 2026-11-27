# Troubleshooting & backup

## Notifications do not appear

1. Check **Permissions and setup → Notification access** in `config`.
2. Enable **Notifications in the terminal** in `config notifications`.
3. Check **Include apps without rules** and the app's notification rule. An exclude filter or disabled rule can hide it.
4. Check whether the next notification from that app appears in the terminal.

## An app will not launch

Run `apps -al` and copy the app's full `id` value, including the component and profile. Replace `APP_ID` below with that value:

```sh
apps launch 'APP_ID'
```

Check whether the app is hidden or an alias is using its name. `apps launch` can still open hidden apps. See [apps](./reference/commands/apps.md#apps) for other ways to launch an app.

## Weather is missing

- Choose a service in `config weather` and allow location access.
- For OpenWeather, check the saved API key.
- Run `weather` to check for an error message.
- Include `{weather}` in your status lines if you want it above the terminal.

## A folder cannot be opened

Check **Home folder** in `config terminal`. Android restricts access to some shared-storage folders. Choose the app folder for a writable location, or use `cd "$HOMETTY_DATA"` at the prompt.

## The screen is hard to read

Long-press the terminal, open **Settings**, and use **Reset display settings**. For status lines alone, use **Reset appearance only** in their editor.

## Back up and restore

Open **Backup & restore** from `config`. Export a ZIP file before moving to another phone or resetting the app.

The backup includes settings, aliases, notes, RSS feeds, notification rules, app preferences, and your imported font. It excludes API keys and shell history. Save your `.shrc` and other shell files separately.

To restore, choose **Import backup**, review the summary, and confirm. This replaces your current settings and saved data, then restarts the shell with root disabled. On a new phone, re-enter your weather key and grant permissions again.

## Report a problem

Open a [GitHub issue](https://github.com/luftaquila/hometty/issues) with your app version, Android version, steps to reproduce, and the exact error. Remove personal notifications, API keys, and other private content from screenshots or logs.
