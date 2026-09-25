package io.luftaquila.hometty.commands

import android.provider.Settings as AndroidSettings
import io.luftaquila.hometty.core.CliUsageException
import io.luftaquila.hometty.core.Commands
import io.luftaquila.hometty.platform.DeviceAccess
import io.luftaquila.hometty.resources.R as TextR
import javax.inject.Inject
import javax.inject.Provider

/** `help` section Device: radios, screen, sound, torch, location and status. */
class DeviceCommands
@Inject
constructor(
    private val device: Provider<DeviceAccess>,
) : CommandGroup {
    override val commands =
        listOf(
            // Apps can no longer switch these: each opens its settings.
            command(Commands.wifi) { device.get().settings(AndroidSettings.Panel.ACTION_WIFI) },
            command(Commands.bluetooth) {
                device.get().settings(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)
            },
            command(Commands.data) { device.get().settings(AndroidSettings.ACTION_DATA_ROAMING_SETTINGS) },
            command(Commands.airplane) {
                device.get().settings(AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS)
            },
            command(Commands.brightness) { out(device.get().brightness(operand())) },
            command(Commands.volume) { out(device.get().volume(operands.firstOrNull())) },
            command(Commands.flash) { out(device.get().flash(operands.firstOrNull())) },
            command(Commands.vibrate) {
                val milliseconds =
                    operands.firstOrNull()?.let {
                        it.toLongOrNull() ?: throw CliUsageException("vibrate: expected 1-5000 milliseconds")
                    } ?: 100
                device.get().vibrate(milliseconds)
                out(string(TextR.string.message_vibrated))
            },
            command(Commands.location) { out(device.get().location()) },
            command(Commands.status) {
                out(device.get().status().entries.joinToString("\n") { "${it.key}: ${it.value}" })
            },
        )
}
