package io.luftaquila.hometty.commands

import io.luftaquila.hometty.core.Commands
import io.luftaquila.hometty.platform.DeviceAccess
import javax.inject.Inject
import javax.inject.Provider

/** `help` section People: call, contacts. */
class PeopleCommands @Inject constructor(private val device: Provider<DeviceAccess>) : CommandGroup {
    override val commands =
        listOf(
            command(Commands.call) { out(device.get().call(text)) },
            command(Commands.contacts) {
                when (action.ifEmpty { "list" }) {
                    "list" -> out(device.get().contacts(text))
                    "add" -> device.get().contactEditor(null)
                    "edit" -> device.get().contactEditor(operand())
                    else -> usage()
                }
            },
        )
}
