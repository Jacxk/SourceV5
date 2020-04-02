package net.sourcebot.api.command

class CommandMap {
    private val labels = HashMap<String, Command>()
    private val aliases = HashMap<String, Command>()

    fun register(command: Command) {
        labels[command.name] = command
        command.aliases.forEach { aliases[it] = command }
    }

    fun get(identifier: String) = labels[identifier] ?: aliases[identifier]
}