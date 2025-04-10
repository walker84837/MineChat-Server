package org.winlogon.minechat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.mojang.brigadier.Command

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

import java.io.File
import java.net.ServerSocket
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

import kotlin.concurrent.schedule

class MineChatServerPlugin : JavaPlugin() {

    companion object {
        // The raw legacy string prefix is stored as a constant and we pre-convert it into a Component.
        const val MINECHAT_PREFIX_STRING = "&8[&3MineChat&8]"
        val MINECHAT_PREFIX_COMPONENT: Component = LegacyComponentSerializer.legacyAmpersand().deserialize(MINECHAT_PREFIX_STRING)
    }

    private var serverSocket: ServerSocket? = null
    private val connectedClients = CopyOnWriteArrayList<ClientConnection>()
    private lateinit var linkCodeStorage: LinkCodeStorage
    private lateinit var clientStorage: ClientStorage

    private var port: Int = 25575

    private var serverThread: Thread? = null
    @Volatile private var isServerRunning = false

    private fun generateLinkCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
    }

    fun generateAndSendLinkCode(player: Player) {
        val code = generateLinkCode()
        val expiryMs = 300_000 // 5 minutes
        
        linkCodeStorage.add(
            LinkCode(
                code = code,
                minecraftUuid = player.uniqueId,
                minecraftUsername = player.name,
                expiresAt = System.currentTimeMillis() + expiryMs
            )
        )

        val codeComponent = Component.text(code, NamedTextColor.DARK_AQUA)
        val timeComponent = Component.text("${expiryMs / 60000} minutes", NamedTextColor.DARK_GREEN)
        player.sendRichMessage(
            "<gray>Your link code is: <code>. Use it in the client within <expiry_time>.</gray>",
            Placeholder.component("code", codeComponent),
            Placeholder.component("expiry_time", timeComponent)
        )
    }

    fun registerLinkCommand() {
        val linkCommand = Commands.literal("link")
            .executes { ctx ->
                val sender = ctx.source.sender
                if (sender is Player) {
                    generateAndSendLinkCode(sender)
                    Command.SINGLE_SUCCESS
                } else {
                    sender.sendMessage(
                        Component.text("Only players can use this command!", NamedTextColor.RED)
                    )
                    0
                }
            }
            .build()

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(linkCommand)
        }
    }

    override fun onEnable() {
        saveResource("config.yml", false)
        saveDefaultConfig()

        port = config.getInt("port", 25575)
        
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        linkCodeStorage = LinkCodeStorage(dataFolder)
        clientStorage = ClientStorage(dataFolder)
        linkCodeStorage.load()
        clientStorage.load()

        registerLinkCommand()

        serverSocket = ServerSocket(port)
        logger.info("Starting MineChat server on port $port")

        // 60 000 ms = 1 minute
        Timer().schedule(0, 60_000) {
            linkCodeStorage.cleanupExpired()
        }

        isServerRunning = true

        serverThread = Thread {
            while (isServerRunning) {
                try {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        logger.info("Client connected: ${socket.inetAddress}")
                        val connection = ClientConnection(socket, this@MineChatServerPlugin)
                        connectedClients.add(connection)
                        connection.start()
                    }
                } catch (e: Exception) {
                    if (!isServerRunning) {
                        break
                    }
                    logger.warning("Error accepting client: ${e.message}")
                }
            }
            logger.info("MineChat server socket thread stopped.")
        }
        serverThread?.start()

        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onChat(event: AsyncChatEvent) {
                val plainMsg = PlainTextComponentSerializer.plainText().serialize(event.message())
                val message = mapOf(
                    "type" to "BROADCAST",
                    "payload" to mapOf(
                        "from" to event.player.name,
                        "message" to plainMsg
                    )
                )
                broadcastToClients(Gson().toJson(message))
            }
        }, this)
    }

    override fun onDisable() {
        isServerRunning = false
        serverThread?.interrupt()
        serverSocket?.close()
        connectedClients.forEach { it.close() }
        linkCodeStorage.save()
        clientStorage.save()
        try {
            serverThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun broadcastToClients(message: String) {
        connectedClients.forEach { client ->
            try {
                client.sendMessage(message)
            } catch (e: Exception) {
                logger.warning("Error sending message to client: ${e.message}")
                connectedClients.remove(client)
            }
        }
    }

    fun broadcastMinecraft(component: Component) {
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(component) }
    }

    fun getLinkCodeStorage(): LinkCodeStorage = linkCodeStorage
    fun getClientStorage(): ClientStorage = clientStorage
    fun removeClient(client: ClientConnection) = connectedClients.remove(client)

    /**
     * Format a message using Adventure.
     * If the string contains MiniMessage tags (<...>) then MiniMessage is used;
     * otherwise the legacy ampersand formatter is applied.
     */
    fun formatMessage(message: String): Component {
        return if (message.contains('<') && message.contains('>')) {
            MiniMessage.miniMessage().deserialize(message)
        } else {
            LegacyComponentSerializer.legacyAmpersand().deserialize(message)
        }
    }

    /**
     * Helper to prepend the MineChat prefix to a message.
     */
    fun formatPrefixed(message: String): Component {
        return MINECHAT_PREFIX_COMPONENT
            .append(Component.space())
            .append(formatMessage(message))
    }
}

data class LinkCode(
    val code: String,
    val minecraftUuid: UUID,
    val minecraftUsername: String,
    val expiresAt: Long
)

data class Client(
    val clientUuid: String,
    val minecraftUuid: UUID,
    val minecraftUsername: String
)

/**
 * Uses the plugin’s data folder to store the link_codes.json file.
 * If the file does not exist, it is created with an empty JSON array.
 */
class LinkCodeStorage(private val dataFolder: File) {
    private val linkCodes = mutableListOf<LinkCode>()
    private val file = File(dataFolder, "link_codes.json")

    fun add(code: LinkCode) {
        linkCodes.add(code)
        save()
    }

    fun find(code: String): LinkCode? {
        return linkCodes.find { it.code == code }
    }

    fun remove(code: String) {
        linkCodes.removeIf { it.code == code }
        save()
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        linkCodes.removeIf { it.expiresAt < now }
        save()
    }

    fun load() {
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText("[]")
            return
        }
        val json = file.readText()
        if (json.isNotBlank()) {
            val type = object : TypeToken<List<LinkCode>>() {}.type
            linkCodes.addAll(Gson().fromJson(json, type))
        }
    }

    fun save() {
        file.writeText(Gson().toJson(linkCodes))
    }
}

/**
 * Uses the plugin’s data folder to store the clients.json file.
 * If the file does not exist, it is created with an empty JSON array.
 */
class ClientStorage(private val dataFolder: File) {
    private val clients = mutableListOf<Client>()
    private val file = File(dataFolder, "clients.json")

    fun find(clientUuid: String): Client? {
        return clients.find { it.clientUuid == clientUuid }
    }

    fun add(client: Client) {
        clients.add(client)
        save()
    }

    fun load() {
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText("[]")
            return
        }
        val json = file.readText()
        if (json.isNotBlank()) {
            val type = object : TypeToken<List<Client>>() {}.type
            clients.addAll(Gson().fromJson(json, type))
        }
    }

    fun save() {
        file.writeText(Gson().toJson(clients))
    }
}

class ClientConnection(
    private val socket: java.net.Socket,
    private val plugin: MineChatServerPlugin
) : Thread() {
    private val reader = socket.getInputStream().bufferedReader()
    private val writer = socket.getOutputStream().bufferedWriter()
    private var client: Client? = null
    private var running = true

    override fun run() {
        try {
            while (running) {
                val line = reader.readLine() ?: break
                val json = Gson().fromJson(line, JsonObject::class.java)
                when (json.get("type").asString) {
                    "AUTH" -> handleAuth(json.getAsJsonObject("payload"))
                    "CHAT" -> handleChat(json.getAsJsonObject("payload"))
                    "DISCONNECT" -> break
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Client error: ${e.message}")
        } finally {
            client?.let {
                plugin.broadcastMinecraft(plugin.formatPrefixed("&a${it.minecraftUsername} has left the chat."))
                plugin.broadcastToClients(
                    Gson().toJson(
                        mapOf(
                            "type" to "SYSTEM",
                            "payload" to mapOf(
                                "event" to "leave",
                                "username" to it.minecraftUsername,
                                "message" to "${it.minecraftUsername} has left the chat."
                            )
                        )
                    )
                )
            }
            close()
            plugin.removeClient(this)
        }
    }

    private fun handleAuth(payload: JsonObject) {
        val clientUuid = payload.get("client_uuid").asString
        val linkCode = payload.get("link_code").asString

        if (linkCode.isNotEmpty()) {
            val link = plugin.getLinkCodeStorage().find(linkCode)
            if (link != null && link.expiresAt > System.currentTimeMillis()) {
                val client = Client(clientUuid, link.minecraftUuid, link.minecraftUsername)
                plugin.getClientStorage().add(client)
                plugin.getLinkCodeStorage().remove(link.code)
                this.client = client
                sendMessage(
                    Gson().toJson(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "success",
                                "message" to "Linked to ${link.minecraftUsername}",
                                "minecraft_uuid" to link.minecraftUuid.toString(),
                                "username" to link.minecraftUsername
                            )
                        )
                    )
                )
                plugin.broadcastMinecraft(plugin.formatPrefixed("&a${link.minecraftUsername} has successfully authenticated."))
                plugin.broadcastToClients(
                    Gson().toJson(
                        mapOf(
                            "type" to "SYSTEM",
                            "payload" to mapOf(
                                "event" to "join",
                                "username" to link.minecraftUsername,
                                "message" to "${link.minecraftUsername} has joined the chat."
                            )
                        )
                    )
                )
            } else {
                sendMessage(
                    Gson().toJson(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "failure",
                                "message" to "Invalid or expired link code"
                            )
                        )
                    )
                )
            }
        } else {
            val client = plugin.getClientStorage().find(clientUuid)
            if (client != null) {
                this.client = client
                sendMessage(
                    Gson().toJson(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "success",
                                "message" to "Welcome back, ${client.minecraftUsername}",
                                "minecraft_uuid" to client.minecraftUuid.toString(),
                                "username" to client.minecraftUsername
                            )
                        )
                    )
                )
                plugin.broadcastMinecraft(plugin.formatPrefixed("&a${client.minecraftUsername} has joined the chat."))
                plugin.broadcastToClients(
                    Gson().toJson(
                        mapOf(
                            "type" to "SYSTEM",
                            "payload" to mapOf(
                                "event" to "join",
                                "username" to client.minecraftUsername,
                                "message" to "${client.minecraftUsername} has joined the chat."
                            )
                        )
                    )
                )
            } else {
                sendMessage(
                    Gson().toJson(
                        mapOf(
                            "type" to "AUTH_ACK",
                            "payload" to mapOf(
                                "status" to "failure",
                                "message" to "Client not registered"
                            )
                        )
                    )
                )
            }
        }
    }

    private fun handleChat(payload: JsonObject) {
        client?.let {
            val message = payload.get("message").asString
            plugin.broadcastMinecraft(plugin.formatPrefixed("&2${it.minecraftUsername}&8: &7$message"))
            plugin.broadcastToClients(
                Gson().toJson(
                    mapOf(
                        "type" to "BROADCAST",
                        "payload" to mapOf(
                            "from" to "[MineChat] ${it.minecraftUsername}",
                            "message" to message
                        )
                    )
                )
            )
        }
    }

    fun sendMessage(message: String) {
        try {
            writer.write(message)
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            plugin.logger.warning("Error sending message: ${e.message}")
        }
    }

    fun close() {
        running = false
        socket.close()
    }
}
