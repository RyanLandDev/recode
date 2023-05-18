package io.github.homchom.recode.server

import io.github.homchom.recode.event.*
import io.github.homchom.recode.mc
import io.github.homchom.recode.render.runOnRenderThread
import io.github.homchom.recode.server.message.LocateMessage
import io.github.homchom.recode.server.message.TipMessage
import io.github.homchom.recode.ui.equalsUnstyled
import io.github.homchom.recode.ui.matchEntireUnstyled
import kotlinx.coroutines.async
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Disconnect
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Join
import net.fabricmc.fabric.api.networking.v1.PacketSender
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.chat.Component

object JoinServerEvent :
    WrappedEvent<ServerJoinContext, Join> by
        wrapFabricEvent(ClientPlayConnectionEvents.JOIN, { listener ->
            Join { handler, sender, client -> listener(ServerJoinContext(handler, sender, client)) }
        })

object DisconnectFromServerEvent :
    WrappedEvent<ServerDisconnectContext, Disconnect> by
        wrapFabricEvent(ClientPlayConnectionEvents.DISCONNECT, { listener ->
            Disconnect { handler, client -> listener(ServerDisconnectContext(handler, client)) }
        })

data class ServerJoinContext(val handler: ClientPacketListener, val sender: PacketSender, val client: Minecraft)
data class ServerDisconnectContext(val handler: ClientPacketListener, val client: Minecraft)

private val patchRegex = Regex("""Current patch: (.+). See the patch notes with /patch!""")

object JoinDFDetector :
    Detector<Unit, JoinDFInfo> by detector(nullaryTrial(JoinServerEvent) {
        requireFalse(isOnDF) // if already on DF, this is a node switch and should not be tested
        requireTrue(ipMatchesDF)

        // pre-register TipMessage as an implicit dependency
        TipMessage.getNotificationsFrom(module)

        suspending {
            enforceOn<_, Unit>(DisconnectFromServerEvent) { null } // TODO: nicer syntax?
            +testBooleanOn(ReceiveChatMessageEvent, 3u) { (text) ->
                text.equalsUnstyled("◆ Welcome back to DiamondFire! ◆")
            }
            val patch = +testOn(ReceiveChatMessageEvent) { (text) ->
                patchRegex.matchEntireUnstyled(text)?.groupValues?.get(1)
            }

            // TODO: a lot of this nuance should be abstracted and/or strengthened somehow
            // so the test starts before the tip message is processed
            runOnRenderThread {
                val canTip = async { testBy(TipMessage, null).value?.canTip ?: false }
                val state = mc.player?.run {
                    val message = +awaitBy(
                        LocateMessage,
                        LocateMessage.Request(username, true)
                    )
                    message.state
                } ?: fail()
                JoinDFInfo(state.node, patch, canTip.await())
            }
        }
    })

data class JoinDFInfo(val node: Node, val patch: String, val canTip: Boolean)

object ReceiveChatMessageEvent :
    SimpleValidatedEvent<Component> by createValidatedEvent()

object SendCommandEvent :
    SimpleValidatedEvent<String> by createValidatedEvent()