# Device commands

## wifi

Open Android's Wi-Fi panel to choose a network or turn Wi-Fi on or off.

```sh
wifi
```

## bluetooth

Open Android's Bluetooth settings to manage connections and paired devices.

```sh
bluetooth
```

## data

Open Android's mobile network settings.

```sh
data
```

## airplane

Open Android's airplane mode settings.

```sh
airplane
```

## brightness

Set screen brightness from 0 to 100%, or enable automatic brightness.

```sh
brightness 50
brightness auto
```

A number switches brightness to manual mode. This command requires **Modify system settings** access. If Android opens the permission screen, grant access and run the command again.

## volume

Check or change media volume. With no argument, print the current and maximum volume levels.

```sh
volume
volume 50
```

A number sets media volume from 0 to 100%. To change the ringer mode:

```sh
volume silent
volume vibrate
volume normal
```

These modes require **Do Not Disturb access**. If Android opens the access settings, grant access and run the command again.

## flash

Turn the flashlight on or off. With no argument, toggle it.

```sh
flash
flash on
flash off
```

The phone must have a flashlight.

## vibrate

Vibrate for a duration in milliseconds. The default is 100 ms, and the allowed range is 1–5,000 ms.

```sh
vibrate
vibrate 500
```

## location

Print latitude, longitude, and estimated accuracy in meters.

```sh
location
```

Location permission is required. The result depends on the location available from Android and may be approximate.

## status

Print the battery percentage, free and total memory, free and total storage, network type, and device model.

```sh
status
```

For a display that updates above the terminal, use [Status lines](../../status-lines.md).
