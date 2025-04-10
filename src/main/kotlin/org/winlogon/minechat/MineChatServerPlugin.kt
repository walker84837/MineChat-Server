package org.winlogon.minechat

import com.github.benmanes.caffeine.cache.Caffeine
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
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

import java.io.File
import java.net.ServerSocket
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule

data class Config(
    val port: Int
)

class MineChatServerPlugin : JavaPlugin() {
    private var serverSocket: ServerSocket? = null
    private val connectedClients = CopyOnWriteArrayList<ClientConnection>()
    private lateinit var linkCodeStorage: LinkCodeStorage
    private lateinit var clientStorage: ClientStorage
    private var isFolia = false

    private var port: Int = 25575
    private var expiryCodeMs = 300_000 // 5 minutes
    private var serverThread: Thread? = null
    @Volatile private var isServerRunning = false
    private val executorService = Executors.newCachedThreadPool()
    val gson = Gson()
    val miniMessage = MiniMessage.miniMessage()

    private fun generateLinkCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
    }

    fun generateAndSendLinkCode(player: Player) {
        val code = generateLinkCode()

        val link = LinkCode(
            code = code,
            minecraftUuid = player.uniqueId,
            minecraftUsername = player.name,
            expiresAt = System.currentTimeMillis() + expiryCodeMs
        )
        linkCodeStorage.add(link)

        val codeComponent = Component.text(code, NamedTextColor.DARK_AQUA)
        val timeComponent = Component.text("${expiryCodeMs / 60000} minutes", NamedTextColor.DARK_GREEN)
        player.sendRichMessage(
            "<gray>Your link code is: <code>. Use it in the client within <expiry_time>.</gray>",
            Placeholder.component("code", codeComponent),
            Placeholder.component("expiry_time", timeComponent)
        )
    }

    fun registerCommands() {
        val linkCommand = Commands.literal("link")
            .requires { sender -> sender.getExecutor() is Player } 
            .executes { ctx ->
                val sender = ctx.source.sender
                generateAndSendLinkCode(sender as Player)
                Command.SINGLE_SUCCESS
            }
            .build()

        val reloadCommand = Commands.literal("minechatreload")
            .requires { sender -> sender.getSender().hasPermission("minechat.reload") }
            .executes { ctx ->
                val sender = ctx.source.sender
                reloadConfig()
                port = config.getInt("port", 25575)
                expiryCodeMs = config.getInt("expiry-code-minutes", 5) * 60_000
                linkCodeStorage.load()
                clientStorage.load()
                sender.sendRichMessage("<gray>MineChat config and storage reloaded.</gray>")
                Command.SINGLE_SUCCESS
            }
            .build()

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            registrar.register(linkCommand)
            registrar.register(reloadCommand)
        }
    }

    override fun onEnable() {
        isFolia = try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

        saveResource("config.yml", false)
        reloadConfig()

        port = config.getInt("port", 25575)
        expiryCodeMs = config.getInt("expiry-code-minutes", 5) * 60_000

        dataFolder.mkdirs()

        linkCodeStorage = LinkCodeStorage(dataFolder, gson)
        clientStorage = ClientStorage(dataFolder, gson)
        linkCodeStorage.load()
        clientStorage.load()

        registerCommands()

        serverSocket = ServerSocket(port)
        logger.info("Starting MineChat server on port $port")

        val saveTask = Runnable {
            linkCodeStorage.cleanupExpired()
            linkCodeStorage.save()
            clientStorage.save()
        }

        if (isFolia) {
            val scheduler = server.getAsyncScheduler()
            scheduler.runAtFixedRate(this, { _ -> saveTask.run() }, 1, 1, TimeUnit.MINUTES)
        } else {
            server.scheduler.runTaskTimer(this, saveTask, 0, 20 * 60)
        }

        isServerRunning = true

        serverThread = Thread {
            while (isServerRunning) {
                try {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        logger.info("Client connected: ${socket.inetAddress}")
                        val connection = ClientConnection(socket, this, gson, miniMessage)
                        connectedClients.add(connection)
                        executorService.submit(connection)
                    }
                } catch (e: Exception) {
                    if (!isServerRunning) break
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
                broadcastToClients(gson.toJson(message))
            }
        }, this)
    }

    override fun onDisable() {
        isServerRunning = false
        serverThread?.interrupt()
        serverSocket?.close()
        connectedClients.forEach { it.close() }
        executorService.shutdownNow()
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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

class LinkCodeStorage(private val dataFolder: File, private val gson: Gson) {
    private val file = File(dataFolder, "link_codes.json")
    private val linkCodeCache = Caffeine.newBuilder().build<String, LinkCode>().asMap()
    private var isDirty = AtomicBoolean(false)

    fun add(linkCode: LinkCode) {
        linkCodeCache[linkCode.code] = linkCode
        isDirty.set(true)
    }

    fun find(code: String): LinkCode? = linkCodeCache[code]

    fun remove(code: String) {
        linkCodeCache.remove(code)
        isDirty.set(true)
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        var modified = false
        val iterator = linkCodeCache.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.expiresAt <= now) {
                iterator.remove()
                modified = true
            }
        }
        if (modified) isDirty.set(true)
    }

    fun load() {
        if (!file.exists()) {
            file.writeText("[]")
            return
        }
        val json = file.readText()
        if (json.isNotBlank()) {
            val type = object : TypeToken<List<LinkCode>>() {}.type
            val codes: List<LinkCode> = gson.fromJson(json, type)
            linkCodeCache.putAll(codes.associateBy { it.code })
        }
        isDirty.set(false)
    }

    fun save() {
        if (!isDirty.get()) return
        val codes = linkCodeCache.values.toList()
        file.writeText(gson.toJson(codes))
        isDirty.set(false)
    }
}

class ClientStorage(private val dataFolder: File, private val gson: Gson) {
    private val file = File(dataFolder, "clients.json")
    private val clientCache = Caffeine.newBuilder().build<String, Client>().asMap()
    private var isDirty = AtomicBoolean(false)

    fun find(clientUuid: String): Client? = clientCache[clientUuid]

    fun add(client: Client) {
        clientCache[client.clientUuid] = client
        isDirty.set(true)
    }

    fun load() {
        if (!file.exists()) {
            file.writeText("[]")
            return
        }
        val json = file.readText()
        if (json.isNotBlank()) {
            val type = object : TypeToken<List<Client>>() {}.type
            val clients: List<Client> = gson.fromJson(json, type)
            clientCache.putAll(clients.associateBy { it.clientUuid })
        }
        isDirty.set(false)
    }

    fun save() {
        if (!isDirty.get()) return
        val clients = clientCache.values.toList()
        file.writeText(gson.toJson(clients))
        isDirty.set(false)
    }
}

class ClientConnection(
    private val socket: java.net.Socket,
    private val plugin: MineChatServerPlugin,
    private val gson: Gson,
    private val miniMessage: MiniMessage
) : Runnable {
    object ChatGradients {
        val JOIN = Pair("#27AE60", "#2ECC71")
        val LEAVE = Pair("#C0392B", "#E74C3C")
        val AUTH = Pair("#8E44AD", "#9B59B6")
        val INFO = Pair("#2980B9", "#3498DB")
    }

    companion object {
        const val MINECHAT_PREFIX_STRING = "&8[&3MineChat&8]"
        val MINECHAT_PREFIX_COMPONENT: Component = LegacyComponentSerializer.legacyAmpersand().deserialize(MINECHAT_PREFIX_STRING)
    }

    private val reader = socket.getInputStream().bufferedReader()
    private val writer = socket.getOutputStream().bufferedWriter()
    private var client: Client? = null
    private var running = true

    private fun broadcastMinecraft(colors: Pair<String, String>?, message: String) {
        val formattedMessage = colors?.let { "<gradient:${it.first}:${it.second}>$message</gradient>" } ?: message
        val finalMessage = miniMessage.deserialize(formattedMessage)
        Bukkit.broadcast(formatPrefixed(finalMessage))
    }

    override fun run() {
        try {
            while (running) {
                val line = reader.readLine() ?: break
                val json = gson.fromJson(line, JsonObject::class.java)
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
                broadcastMinecraft(ChatGradients.LEAVE, "${it.minecraftUsername} has left the chat.")
                plugin.broadcastToClients(
                    gson.toJson(
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
                    gson.toJson(
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
                broadcastMinecraft(ChatGradients.AUTH, "${link.minecraftUsername} has successfully authenticated.")
                plugin.broadcastToClients(
                    gson.toJson(
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
                    gson.toJson(
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
                    gson.toJson(
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
                broadcastMinecraft(ChatGradients.JOIN,"${client.minecraftUsername} has joined the chat.")
                plugin.broadcastToClients(
                    gson.toJson(
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
                    gson.toJson(
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
            val usernamePlaceholder = Component.text(it.minecraftUsername, NamedTextColor.DARK_GREEN)
            val messagePladeholder = Component.text(message)
            val formattedMsg = miniMessage.deserialize(
                "<gray><sender><dark_gray>:</dark_gray> <message></gray>",
                Placeholder.component("sender", usernamePlaceholder),
                Placeholder.component("message", messagePladeholder)
            )
            val finalMsg = formatPrefixed(formattedMsg)
            Bukkit.broadcast(finalMsg)
            plugin.broadcastToClients(
                gson.toJson(
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

    fun formatPrefixed(message: Component): Component {
        return MINECHAT_PREFIX_COMPONENT
            .append(Component.space())
            .append(message)
    }
}
