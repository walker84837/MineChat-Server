package org.winlogon.minechat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.CommandAPIConfig
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.schedule

class MineChatServerPlugin : JavaPlugin() {
    private var serverSocket: ServerSocket? = null
    private val connectedClients = CopyOnWriteArrayList<ClientConnection>()
    private val linkCodeStorage = LinkCodeStorage()
    private val clientStorage = ClientStorage()
    private val port = 25575
    private var serverThread: Thread? = null
    @Volatile private var isServerRunning = false

    override fun onEnable() {
        saveDefaultConfig()
        linkCodeStorage.load()
        clientStorage.load()

        CommandAPICommand("link")
            .executesPlayer(PlayerCommandExecutor { player, _ ->
                val code = generateLinkCode()
		val fiveMinutesInMs = 300_000
                linkCodeStorage.add(
                    LinkCode(
                        code = code,
                        minecraftUuid = player.uniqueId,
                        minecraftUsername = player.name,
                        expiresAt = System.currentTimeMillis() + fiveMinutesInMs
                    )
                )
                player.sendMessage("§7Your link code is: §3$code§7. Use it in the client within §25 minutes.")
            })
            .register()

        serverSocket = ServerSocket(port)
        logger.info("Starting MineChat server on port $port")

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

    private fun generateLinkCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
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

    fun broadcastMinecraft(message: String) {
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(message) }
    }

    fun getLinkCodeStorage(): LinkCodeStorage = linkCodeStorage
    fun getClientStorage(): ClientStorage = clientStorage
    fun removeClient(client: ClientConnection) = connectedClients.remove(client)
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

class LinkCodeStorage {
    private val linkCodes = mutableListOf<LinkCode>()
    private val file = File("plugins/MineChat/link_codes.json")

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
        if (!file.exists()) return
        val json = file.readText()
        val type = object : TypeToken<List<LinkCode>>() {}.type
        linkCodes.addAll(Gson().fromJson(json, type))
    }

    fun save() {
        file.writeText(Gson().toJson(linkCodes))
    }
}

class ClientStorage {
    private val clients = mutableListOf<Client>()
    private val file = File("plugins/MineChat/clients.json")

    fun find(clientUuid: String): Client? {
        return clients.find { it.clientUuid == clientUuid }
    }

    fun add(client: Client) {
        clients.add(client)
        save()
    }

    fun load() {
        if (!file.exists()) return
        val json = file.readText()
        val type = object : TypeToken<List<Client>>() {}.type
        clients.addAll(Gson().fromJson(json, type))
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
                plugin.broadcastMinecraft("§8[§3MineChat§8] §a${it.minecraftUsername} has left the chat.")
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
                plugin.broadcastMinecraft("§8[§3MineChat§8] §a${link.minecraftUsername} has successfully authenticated.")
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
                plugin.broadcastMinecraft("§8[§3MineChat§8] §a${client.minecraftUsername} has joined the chat.")
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
            plugin.broadcastMinecraft("§8[§3MineChat§8] §2${it.minecraftUsername}§8: §7$message")
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
