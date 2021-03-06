package net.sourcebot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.GenericEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.EventListener
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent.DIRECT_MESSAGE_TYPING
import net.dv8tion.jda.api.requests.GatewayIntent.GUILD_MESSAGE_TYPING
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder
import net.sourcebot.api.command.CommandHandler
import net.sourcebot.api.database.MongoDB
import net.sourcebot.api.database.MongoSerial
import net.sourcebot.api.event.EventSystem
import net.sourcebot.api.event.SourceEvent
import net.sourcebot.api.module.InvalidModuleException
import net.sourcebot.api.module.ModuleDescription
import net.sourcebot.api.module.ModuleHandler
import net.sourcebot.api.permission.*
import net.sourcebot.api.properties.JsonSerial
import net.sourcebot.api.properties.Properties
import net.sourcebot.impl.BaseModule
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import uk.org.lidalia.sysoutslf4j.context.SysOutOverSLF4J
import java.io.File
import java.io.FileFilter
import java.io.FileReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter.ofPattern
import java.util.*
import kotlin.Comparator
import kotlin.collections.ArrayList

class Source internal constructor(val properties: Properties) {
    private val ignoredIntents = EnumSet.of(
        GUILD_MESSAGE_TYPING, DIRECT_MESSAGE_TYPING
    )

    val sourceEventSystem = EventSystem<SourceEvent>()
    val jdaEventSystem = EventSystem<GenericEvent>()

    val mongodb = MongoDB(properties.required("mongodb"))
    val permissionHandler = PermissionHandler(mongodb)

    val moduleHandler = ModuleHandler(this)

    val commandHandler = CommandHandler(
        properties.required("commands.prefix"),
        properties.required("commands.delete-seconds"),
        permissionHandler
    )

    private val baseModule = BaseModule().apply {
        moduleDescription = Source::class.java.getResourceAsStream("/module.json").use {
            if (it == null) throw InvalidModuleException("Could not find module.json!")
            else JsonParser.parseReader(InputStreamReader(it)) as JsonObject
        }.let(::ModuleDescription)
        source = this@Source
        logger = Source.logger
    }

    val shardManager = DefaultShardManagerBuilder.create(
        properties.required("token"),
        EnumSet.complementOf(ignoredIntents)
    ).addEventListeners(
        EventListener(jdaEventSystem::fireEvent),
        object : ListenerAdapter() {
            override fun onMessageReceived(event: MessageReceivedEvent) {
                commandHandler.onMessageReceived(event)
            }
        }
    ).setActivityProvider {
        Activity.watching("TSC. Shard $it")
    }.build()

    private fun registerSerial() {
        MongoSerial.register(SourcePermission.Serial())
        MongoSerial.register(SourceGroup.Serial(permissionHandler))
        MongoSerial.register(SourceUser.Serial(permissionHandler))
        MongoSerial.register(SourceRole.Serial(permissionHandler))
    }

    private fun loadModules() {
        logger.debug("Indexing Modules...")
        moduleHandler.moduleIndex["Source"] = baseModule
        moduleHandler.enableModule(baseModule)
        val modulesFolder = File("modules")
        if (!modulesFolder.exists()) modulesFolder.mkdir()
        val indexed = modulesFolder.listFiles(FileFilter {
            it.name.endsWith(".jar")
        })!!.sortedWith(Comparator.comparing(File::getName)).mapNotNull { moduleHandler.indexModule(it) }
        logger.debug("Loading Modules...")
        val errored = ArrayList<String>()
        val loaded = indexed.mapNotNull {
            try {
                moduleHandler.loadModule(it)
            } catch (ex: Throwable) {
                when (ex) {
                    is StackOverflowError -> logger.error("Cyclic dependency problem for module '$it' !")
                    else -> logger.error("Error loading module '$it' !", ex)
                }
                errored.add(it)
                null
            }
        }
        errored.forEach(moduleHandler::unloadModule)
        logger.debug("All modules have been loaded, now enabling...")
        loaded.forEach(moduleHandler::enableModule)
    }

    companion object {
        @JvmStatic val TIME_ZONE: ZoneId = ZoneId.of("America/New_York")
        @JvmStatic val DATE_FORMAT = ofPattern("MM/dd/yyyy")
        @JvmStatic val TIME_FORMAT = ofPattern("hh:mm:ss a z")
        @JvmStatic val DATE_TIME_FORMAT = ofPattern("MM/dd/yyyy hh:mm:ss a z")
        @JvmStatic val logger: Logger = LoggerFactory.getLogger(Source::class.java)
        private var enabled = false

        @JvmStatic fun main(args: Array<String>) {
            start()
        }

        @JvmStatic fun start(): Source {
            if (enabled) throw IllegalStateException("Source is already enabled!")
            enabled = true

            SysOutOverSLF4J.sendSystemOutAndErrToSLF4J()
            JsonSerial.register(Properties.Serial())
            val configFile = File("config.json")
            if (!configFile.exists()) {
                Source::class.java.getResourceAsStream("/config.example.json").use {
                    Files.copy(it, Path.of("config.json"))
                }
            }
            val properties = FileReader(configFile).use {
                JsonParser.parseReader(it) as JsonObject
            }.let(::Properties)
            return Source(properties).apply {
                registerSerial()
                loadModules()
                logger.info("Source is now online!")
            }
        }
    }
}