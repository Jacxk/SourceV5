package net.sourcebot.impl.command.help

import net.dv8tion.jda.api.entities.Message
import net.sourcebot.api.alert.Alert
import net.sourcebot.api.alert.ErrorAlert
import net.sourcebot.api.alert.InfoAlert
import net.sourcebot.api.command.Command
import net.sourcebot.api.command.CommandHandler
import net.sourcebot.api.command.RootCommand
import net.sourcebot.api.command.argument.ArgumentInfo
import net.sourcebot.api.command.argument.Arguments
import net.sourcebot.api.command.argument.OptionalArgument
import net.sourcebot.api.module.ModuleHandler

class HelpCommand(
    private val moduleHandler: ModuleHandler,
    private val commandHandler: CommandHandler
) : RootCommand() {
    override val name = "help"
    override val description = "Shows command / module information."
    override val argumentInfo = ArgumentInfo(
        OptionalArgument(
            "topic",
            "The command or module to show help for. Empty to show the module listing."
        ),
        OptionalArgument(
            "children...",
            "The sub-command(s) to get help for, in the case that `topic` is a command."
        )
    )

    override fun execute(message: Message, args: Arguments): Alert {
        val topic = args.next()
        return if (topic != null) {
            val asCommand = commandHandler.getCommand(topic)
            if (asCommand != null) {
                var command: Command = asCommand
                do {
                    val nextArg = args.next() ?: break
                    val nextCommand = asCommand.getChild(nextArg)
                    if (nextCommand == null) {
                        args.backtrack()
                        break
                    }
                    command = nextCommand
                } while (true)
                object : InfoAlert(
                    "Command Information:",
                    "Arguments surrounded by <> are required, those surrounded by () are optional."
                ) {
                    init {
                        addField("Description:", command.description, false)
                        addField("Usage:", commandHandler.getSyntax(command), false)
                        addField("Detail:", command.argumentInfo.getParameterDetail(), false)
                        val aliases = command.aliases.joinToString(", ") { it }
                        val aliasList = if (aliases.isEmpty()) {
                            "N/A"
                        } else {
                            aliases
                        }
                        addField("Aliases:", aliasList, false)
                    }
                }
            } else {
                val asModule = moduleHandler.getModule(topic)
                if (asModule != null) {
                    val desc = asModule.moduleDescription
                    object : InfoAlert(
                        "${desc.name} Module Assistance",
                        "Below are a list of commands provided by this module."
                    ) {
                        init {
                            val commands = commandHandler.getCommands(asModule)
                                .sortedBy(RootCommand::name)
                            val listing = if (commands.isEmpty()) {
                                "This module does not have any commands."
                            } else {
                                commands.joinToString("\n") {
                                    "**${commandHandler.getSyntax(it)}**: ${it.description}"
                                }
                            }
                            addField("Commands:", listing, false)
                        }
                    }
                } else {
                    ErrorAlert(
                        "Invalid Topic!",
                        "There is no such module or command named `$topic` !"
                    )
                }
            }
        } else {
            object : InfoAlert(
                "Module Listing",
                "Below are valid module names and descriptions.\n" +
                "Module names may be passed into this command for more detail.\n" +
                "Command names may be passed into this command for usage information."
            ) {
                init {
                    val index = moduleHandler.getModules()
                        .sortedBy { it.moduleDescription.name }
                        .filter { it.enabled }
                        .joinToString("\n") {
                            val desc = it.moduleDescription
                            "**${desc.name}**: ${desc.description}"
                        }
                    val listing = if (index.isEmpty()) {
                        "There are currently no modules enabled."
                    } else {
                        index
                    }
                    addField("Modules", listing, false)
                }
            }
        }
    }
}