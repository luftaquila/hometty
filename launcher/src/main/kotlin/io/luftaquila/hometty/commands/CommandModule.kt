package io.luftaquila.hometty.commands

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.luftaquila.hometty.shell.CommandLinks
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CommandModule {
    @Binds @IntoSet abstract fun apps(group: AppsCommands): CommandGroup

    @Binds @IntoSet abstract fun people(group: PeopleCommands): CommandGroup

    @Binds @IntoSet abstract fun device(group: DeviceCommands): CommandGroup

    @Binds @IntoSet abstract fun tools(group: ToolCommands): CommandGroup

    @Binds @IntoSet abstract fun launcher(group: LauncherCommands): CommandGroup

    @Binds abstract fun texts(texts: ContextTexts): Texts

    @Binds abstract fun names(links: CommandLinks): CommandNames

    companion object {
        /** Every definition has exactly one implementation, or the app does not start. */
        @Provides
        @Singleton
        fun registry(groups: Set<@JvmSuppressWildcards CommandGroup>): CommandRegistry =
            CommandRegistry(groups.flatMap { it.commands }).also {
                check(it.missing.isEmpty()) { "Commands without an implementation: ${it.missing}" }
            }
    }
}
