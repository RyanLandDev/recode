@file:JvmName("DF")
@file:JvmMultifileClass

package io.github.homchom.recode.server.state

import io.github.homchom.recode.mc
import io.github.homchom.recode.server.*
import io.github.homchom.recode.sys.networking.LegacyState
import io.github.homchom.recode.ui.equalsUnstyled
import io.github.homchom.recode.ui.matchEntireUnstyled
import io.github.homchom.recode.ui.matchesUnstyled
import io.github.homchom.recode.util.*
import net.minecraft.network.chat.Component
import org.intellij.lang.annotations.Language
import org.intellij.lang.annotations.RegExp

val isOnDF get() = currentDFState != null

sealed interface DFState : LocateState {
    val isInSession: Boolean

    fun withState(state: LocateState) = when (state) {
        is SpawnState -> AtSpawn(state.node, isInSession)
        is PlayState -> OnPlot(state, isInSession)
    }

    class AtSpawn(override val node: Node, override val isInSession: Boolean) : DFState, SpawnState
    class OnPlot(state: PlayState, override val isInSession: Boolean) : DFState, PlayState by state
}

@JvmInline
value class Node(private val id: String) {
    val displayName get() = when {
        id.startsWith("node") -> "Node ${id.drop(4)}"
        id == "beta" -> "Node Beta"
        else -> id.capitalize()
    }

    override fun toString() = displayName
}

fun nodeByName(name: String): Node {
    val node = name.removePrefix("Node ")
    val id = node.toIntOrNull()?.let { "node${node}" } ?: node.uncapitalize()
    return Node(id)
}

//fun nodeOf(id: String) = Node.values().singleOrNull { it.id == id } ?: Node.UNKNOWN

data class Plot(
    val name: String,
    val owner: String,
    @get:JvmName("getId") val id: UInt
)

private val playModeRegex =
    Regex("""$GREEN_ARROW_CHAR Joined game: $PLOT_NAME_PATTERN by $USERNAME_PATTERN.""")

enum class PlotMode(val descriptor: String) : Matcher<Component, Unit> {
    Play("playing") {
        override fun match(input: Component) =
            playModeRegex.matchesUnstyled(input).unitOrNull()
    },
    Build("building") {
        override fun match(input: Component) =
            input.equalsUnstyled("$GREEN_ARROW_CHAR You are now in build mode.").unitOrNull()
    },
    Dev("coding") {
        override fun match(input: Component) =
            input.equalsUnstyled("$GREEN_ARROW_CHAR You are now in dev mode.").unitOrNull()
    };

    val id get() = name.lowercase()

    companion object : GroupMatcher<Component, Unit> by enumMatcher<PlotMode, _, _>()
}

fun plotModeByDescriptor(descriptor: String) =
    PlotMode.values().single { it.descriptor == descriptor }
fun plotModeByDescriptorOrNull(descriptor: String) =
    PlotMode.values().singleOrNull { it.descriptor == descriptor }

@Deprecated("Use DFState, not LegacyState")
fun LegacyState.toDFState(): DFState? {
    if (mode == null || mode == LegacyState.Mode.OFFLINE) return null
    val newNode = Node(node.raw)
    return if (mode == LegacyState.Mode.SPAWN) DFState.AtSpawn(newNode, session) else {
        val newPlot = Plot(plot.name, plot.owner, plot.id.toUInt())
        val newMode = plotModeByDescriptor(mode.continuousVerb.uncapitalize())
        val locateState = LocateState.OnPlot(newNode, newPlot, newMode, plot.status)
        DFState.OnPlot(locateState, session)
    }
}

data class LocateMessage(val username: String, val state: LocateState) {
    companion object : RequestHolder<Matchable<Component>, Case<String>, LocateMessage> {
        private val regex = defaultedRegex<Case<String>> { username ->
            @RegExp fun bullet(name: String, @RegExp pattern: String) = """\n$RIGHT_ARROW_CHAR $name: $pattern"""

            @Language("regexp") val player = when {
                username == null -> "(?:You are|(?<player>$USERNAME_PATTERN) is) currently"
                username.content == null -> "(?<player>You) are currently"
                else -> "(?<player>${Regex.escape(username.content)}) is currently"
            }
            @Language("regexp") val mode = """(?<mode>playing|building|coding) on:\n"""
            @Language("regexp") val plot =
                """\n$RIGHT_ARROW_CHAR (?<plotName>$PLOT_NAME_PATTERN) \[(?<plotID>\d+)]"""
            @Language("regexp") val owner =
                bullet("Owner", """(?<owner>$USERNAME_PATTERN) (?:\[Whitelisted])?""")
            @Language("regexp") val status = """(?:${bullet("Status", """(?<status>.+)""")})?"""
            @Language("regexp") val server = bullet("Server", """(?<node>[A-Za-z\d ]+)""")

            Regex(""" {39}\n$player (?:at spawn|$mode$plot$owner$status)$server\n {39}""")
        }

        override val request by defineRequest(
            ReceiveChatMessageEvent,
            executor = { username: Case<String> ->
                sendCommand(if (username() == null) "locate" else "locate ${username()}")
            },
            matcher = { text, username ->
                nullable {
                    val values = regex(username).matchEntireUnstyled(text)?.namedGroupValues ?: fail()
                    val player = values["player"].let { if (it == "You") mc.player!!.username else it }
                    val node = nodeByName(values["node"])
                    val state = if (values["mode"].isEmpty()) {
                        LocateState.AtSpawn(node)
                    } else {
                        val mode = plotModeByDescriptorOrNull(values["mode"]) ?: fail()
                        val plotName = values["plotName"]
                        val plotID = values["plotID"].toUIntOrNull() ?: fail()
                        val owner = values["owner"]
                        val status = values["status"].takeUnless(String::isEmpty)
                        LocateState.OnPlot(node, Plot(plotName, owner, plotID), mode, status)
                    }
                    LocateMessage(player, state)
                }
            }
        )
    }
}

// TODO: more comprehensive executors for plot modes
// TODO: combine in some way?

val PlayMode by defineNullaryRequest(
    ReceiveChatMessageEvent,
    executor = { sendCommand("play") },
    matcher = { playModeRegex.matchesUnstyled(it).unitOrNull() }
)

val BuildMode by defineNullaryRequest(
    ReceiveChatMessageEvent,
    executor = { sendCommand("build") },
    matcher = { it.equalsUnstyled("$GREEN_ARROW_CHAR You are now in build mode.") }
)

val DevMode by defineNullaryRequest(
    ReceiveChatMessageEvent,
    executor = { sendCommand("dev") },
    matcher = { it.equalsUnstyled("$GREEN_ARROW_CHAR You are now in dev mode.") }
)