package dev.emortal.mandem

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.proxy.Player
import dev.emortal.mandem.MandemPlugin.Companion.mandemConfig
import dev.emortal.mandem.MandemPlugin.Companion.server
import dev.emortal.mandem.MandemPlugin.Companion.storage
import dev.emortal.mandem.RelationshipManager.channel
import dev.emortal.mandem.RelationshipManager.friendPrefix
import dev.emortal.mandem.RelationshipManager.getFriendsAsync
import dev.emortal.mandem.RelationshipManager.leavingTasks
import dev.emortal.mandem.RelationshipManager.party
import dev.emortal.mandem.RelationshipManager.partyPrefix
import dev.emortal.mandem.channel.ChatChannel
import dev.emortal.mandem.utils.PermissionUtils.displayName
import dev.emortal.mandem.utils.RedisStorage.redisson
import dev.emortal.mandem.utils.plainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.LoggerFactory
import java.time.Duration

class EventListener(val plugin: MandemPlugin) {

    val logger = LoggerFactory.getLogger("EventListener")
    val chatLogger = LoggerFactory.getLogger("Chat")
    val commandLogger = LoggerFactory.getLogger("Command")
    val miniMessage = MiniMessage.miniMessage()

    @Subscribe
    fun playerJoin(e: LoginEvent) {
        if (mandemConfig.enabled) {
            CoroutineScope(Dispatchers.IO).launch {
                Audience.audience(e.player.getFriendsAsync().mapNotNull { server.getPlayer(it).orElseGet { null } })
                    .sendMessage(
                        Component.text()
                            .append(friendPrefix)
                            .append(Component.text(e.player.username, NamedTextColor.GREEN))
                            .append(Component.text(" joined the server", NamedTextColor.GRAY))
                    )
            }
        }

        leavingTasks[e.player.uniqueId]?.cancel()
        leavingTasks.remove(e.player.uniqueId)

        val cachedUsername = redisson.getBucket<String>("${e.player.uniqueId}username")

        RelationshipManager.partyInviteMap[e.player.uniqueId] = mutableListOf()
        RelationshipManager.friendRequestMap[e.player.uniqueId] = mutableListOf()

        // If cache is outdated, re-set
        // May be better to use SQL instead of redis for this... :P
        if (cachedUsername.get() != e.player.username) {
            cachedUsername.set(e.player.username)
        }
    }

    @Subscribe
    fun playerLeave(e: DisconnectEvent) {
        val player = e.player

        RelationshipManager.partyInviteMap.remove(player.uniqueId)
        RelationshipManager.friendRequestMap.remove(player.uniqueId)

        if (mandemConfig.enabled) {
            CoroutineScope(Dispatchers.IO).launch {
                Audience.audience(storage!!.getFriendsAsync(e.player.uniqueId).mapNotNull { server.getPlayer(it).orElseGet { null } })
                    .sendMessage(
                        Component.text()
                            .append(friendPrefix)
                            .append(Component.text(player.username, NamedTextColor.RED))
                            .append(Component.text(" left the server", NamedTextColor.GRAY))
                    )
            }
        }

        RelationshipManager.partyInviteMap.remove(player.uniqueId)

        if (player.party != null) leavingTasks[player.uniqueId] =
            server.scheduler.buildTask(plugin) {
                player.party?.playerAudience?.sendMessage(
                    Component.text(
                        "${player.username} was kicked from the party because they were offline",
                        NamedTextColor.GOLD
                    )
                )
                player.party?.remove(player, false)
            }.delay(Duration.ofMinutes(5)).schedule()
    }

    @Subscribe
    fun playerJoinServer(e: ServerConnectedEvent) {

    }

    @Subscribe
    fun onCommand(e: CommandExecuteEvent) {
        if (e.commandSource !is Player) return
        val username = (e.commandSource as? Player)?.username
        commandLogger.info("${username} ran command: ${e.command}")
    }

    @Subscribe
    fun onChat(e: PlayerChatEvent) {
        val player = e.player
        e.result = PlayerChatEvent.ChatResult.denied()

        chatLogger.info("${player.displayName.plainText()}: ${e.message}")

        if (player.channel == ChatChannel.PARTY) {
            if (player.party == null) {
                player.sendMessage(
                    Component.text(
                        "Set your channel to global because you are not in a party",
                        NamedTextColor.GOLD
                    )
                )

                player.channel = ChatChannel.GLOBAL
                return
            }

            player.party!!.playerAudience.sendMessage(
                Component.text()
                    .append(partyPrefix)
                    .append(player.displayName)
                    .append(Component.text(": "))
                    .append(Component.text(e.message))
                    .build()
            )
        }


    }

}