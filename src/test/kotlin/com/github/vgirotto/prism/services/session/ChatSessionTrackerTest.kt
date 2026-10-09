package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.model.AgentSession
import com.github.vgirotto.prism.services.ResolvedCliCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The tab-update rules, driven synchronously: title events in, labels, tooltips and identity out. */
class ChatSessionTrackerTest {

    /** Runs UI and background work at once; periodic tasks run only when the test ticks them. */
    private class ManualScheduler : ChatSessionTracker.Scheduler {
        val periodic = mutableListOf<Pair<Long, () -> Unit>>()
        override fun onUi(task: () -> Unit) = task()
        override fun inBackground(task: () -> Unit) = task()
        override fun every(periodMs: Long, task: () -> Unit): () -> Unit {
            val entry = periodMs to task
            periodic += entry
            return { periodic.remove(entry) }
        }
        fun tick() = periodic.toList().forEach { it.second() }
    }

    /** A strategy over the real parsers, with the stores replaced by maps the test fills in. */
    private class FakeStrategy(private val parser: (String) -> TitleReading?) : AgentSessionStrategy {
        val ids = mutableMapOf<String, SessionIdentity>()
        val names = mutableMapOf<String, String>()
        var events: IdentityEventSource? = null
        var resolveCalls = 0

        override fun launchCommand(tab: TabSessionFiles, command: ResolvedCliCommand) = command
        override fun launchEnvironment() = emptyMap<String, String?>()
        override fun parseTitle(title: String) = parser(title)
        override fun reportsSwitches() = events != null
        override fun identityEvents(tab: TabSessionFiles) = events
        override fun resolveIdentity(hint: IdHint): SessionIdentity? {
            resolveCalls++
            return ids.values.filter { hint.matches(it.sessionId) }.singleOrNull()
        }
        override fun fullName(identity: SessionIdentity, shown: String) =
            CodexSessionStrategy.fullNameBehind(names[identity.sessionId], shown)
    }

    private val scheduler = ManualScheduler()
    private val shown = mutableListOf<TabTitle>()
    private val sessions = mutableListOf<AgentSession>()

    @AfterEach
    fun disposeSessions() = sessions.forEach { it.dispose() }

    private val renamed = mutableListOf<String>()

    private fun tracker(strategy: AgentSessionStrategy) =
        ChatSessionTracker(strategy, "Chat #3", { shown += it }, { renamed += it.name }, scheduler)

    private fun session(): AgentSession =
        AgentSession(name = "Chat #3", cli = com.github.vgirotto.prism.model.AgentCli.CLAUDE).apply {
            tabFiles = TabSessionFiles(java.nio.file.Path.of("/nonexistent"))
            sessions += this
        }

    // ── Claude ──

    @Test
    fun `a Claude title names the tab and its placeholder puts the number back`() {
        val tracker = tracker(FakeStrategy(ClaudeTitleParser::parse))
        tracker.onApplicationTitleChanged("✳ Review the branch")
        tracker.onApplicationTitleChanged("✳ Claude Code")
        assertEquals(
            listOf(TabTitle("Review the branch", "Review the branch"), TabTitle("Chat #3", null)),
            shown,
        )
    }

    @Test
    fun `unrecognized titles and glyph changes leave the tab alone`() {
        val tracker = tracker(FakeStrategy(ClaudeTitleParser::parse))
        tracker.onApplicationTitleChanged("greg@Sage: ~/x")
        tracker.onApplicationTitleChanged("◐ Review the branch")
        tracker.onApplicationTitleChanged("◑ Review the branch")
        tracker.onApplicationTitleChanged("✳ Review the branch")
        tracker.onApplicationTitleChanged("greg@Sage: ~/x")
        assertEquals(listOf(TabTitle("Review the branch", "Review the branch")), shown)
    }

    @Test
    fun `the latest title wins, whatever named it`() {
        val tracker = tracker(FakeStrategy(ClaudeTitleParser::parse))
        tracker.onApplicationTitleChanged("✳ my-own-name")
        tracker.onApplicationTitleChanged("✳ A generated title")
        assertEquals("A generated title", shown.last().label)
    }

    @Test
    fun `a long name is clipped on the label and whole in the tooltip`() {
        val tracker = tracker(FakeStrategy(ClaudeTitleParser::parse))
        tracker.onApplicationTitleChanged("✳ Add quick action buttons to the Codex chat window")
        assertEquals(
            TabTitle("Add quick action buttons to…", "Add quick action buttons to the Codex chat window"),
            shown.single(),
        )
    }

    @Test
    fun `hook events move the identity, the title keeps the name`() {
        val events = ArrayDeque<SessionIdentity>()
        val strategy = FakeStrategy(ClaudeTitleParser::parse).apply {
            this.events = IdentityEventSource { events.removeLastOrNull().also { events.clear() } }
        }
        val tracker = tracker(strategy)
        val session = session().apply { identity = SessionIdentity("launch", null) }
        tracker.attach(session)
        assertEquals(SessionIdentity("launch", null), tracker.identity)

        events += SessionIdentity("startup-id", "/p/startup-id.jsonl")
        scheduler.tick()
        assertEquals("startup-id", session.identity?.sessionId)

        tracker.onApplicationTitleChanged("✳ Named chat")
        // /clear: a new session under the same title. Only the hook shows it.
        events += SessionIdentity("cleared", "/p/cleared.jsonl")
        tracker.onApplicationTitleChanged("✳ Named chat")
        assertEquals(SessionIdentity("cleared", "/p/cleared.jsonl"), session.identity)
        assertEquals(listOf(TabTitle("Named chat", "Named chat")), shown)
    }

    @Test
    fun `without the hook the identity stays unknown across switches, and the title still names the tab`() {
        // The user's own --settings: no hook, so no launch identity and no events (see launchIdentity).
        val events = IdentityEventSource { null }
        val tracker = tracker(FakeStrategy(ClaudeTitleParser::parse).apply { this.events = events })
        val session = session()
        tracker.attach(session)

        tracker.onApplicationTitleChanged("✳ First chat")
        scheduler.tick()
        tracker.onApplicationTitleChanged("✳ Resumed chat") // /resume
        tracker.onApplicationTitleChanged("✳ Claude Code") // /clear
        scheduler.tick()

        assertNull(session.identity)
        assertNull(tracker.identity)
        assertEquals(
            listOf(TabTitle("First chat", "First chat"), TabTitle("Resumed chat", "Resumed chat"), TabTitle("Chat #3", null)),
            shown,
        )
    }

    @Test
    fun `with the hook a switch replaces the launch identity`() {
        val events = ArrayDeque<SessionIdentity>()
        val strategy = FakeStrategy(ClaudeTitleParser::parse).apply {
            this.events = IdentityEventSource { events.removeLastOrNull().also { events.clear() } }
        }
        val tracker = tracker(strategy)
        val session = session().apply { identity = SessionIdentity("launch", null) }
        tracker.attach(session)

        events += SessionIdentity("resumed", "/p/resumed.jsonl") // /resume
        tracker.onApplicationTitleChanged("✳ Resumed chat")
        assertEquals(SessionIdentity("resumed", "/p/resumed.jsonl"), session.identity)
        events += SessionIdentity("cleared", "/p/cleared.jsonl") // /clear
        scheduler.tick()
        assertEquals(SessionIdentity("cleared", "/p/cleared.jsonl"), session.identity)
    }

    @Test
    fun `overlapping polls apply events in the order they were read`() {
        val insideFirstPoll = java.util.concurrent.CountDownLatch(1)
        val releaseFirstPoll = java.util.concurrent.CountDownLatch(1)
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val strategy = FakeStrategy(ClaudeTitleParser::parse).apply {
            events = IdentityEventSource {
                if (polls.incrementAndGet() == 1) {
                    // The first poll reads the older event, then stalls before applying it.
                    insideFirstPoll.countDown()
                    releaseFirstPoll.await()
                    SessionIdentity("older", "/p/older.jsonl")
                } else {
                    SessionIdentity("resumed", "/p/resumed.jsonl")
                }
            }
        }
        val tracker = tracker(strategy)
        val session = session()
        tracker.attach(session)

        val first = Thread { tracker.pollEvents() }.apply { start() }
        insideFirstPoll.await()
        val second = Thread { tracker.pollEvents() }.apply { start() }
        second.join(300) // Unserialized, the second poll applies its newer event here.
        releaseFirstPoll.countDown()
        first.join(5_000)
        second.join(5_000)

        assertEquals("resumed", session.identity?.sessionId)
    }

    // ── Codex ──

    private val idA = "01a0edbb-4501-7591-82b7-36c4c0a1b2c3"
    private val idB = "01a0edcc-0000-7000-8000-000000000001"
    private fun codexTitle(id: String, name: String? = null, spinner: String? = null) =
        listOfNotNull(id.take(29) + "...", name).joinToString(" | ") { item -> spinner?.let { "$item $it" } ?: item }

    private fun codexStrategy() = FakeStrategy(CodexTitleParser::parse).apply {
        ids[idA] = SessionIdentity(idA, "/r/$idA.jsonl")
        ids[idB] = SessionIdentity(idB, "/r/$idB.jsonl")
    }

    @Test
    fun `a Codex id prefix resolves to the exact thread`() {
        val tracker = tracker(codexStrategy())
        tracker.attach(session())
        tracker.onApplicationTitleChanged(codexTitle(idA, "Reply ok"))
        assertEquals(SessionIdentity(idA, "/r/$idA.jsonl"), tracker.identity)
        assertEquals(TabTitle("Reply ok", "Reply ok"), shown.last())
    }

    @Test
    fun `a resume into another thread resets the identity and resolves it again`() {
        val tracker = tracker(codexStrategy())
        tracker.attach(session())
        tracker.onApplicationTitleChanged(codexTitle(idA, "First"))
        tracker.onApplicationTitleChanged(codexTitle(idB, "Second"))
        assertEquals(idB, tracker.identity?.sessionId)
    }

    @Test
    fun `an identity found before attach moves to the session and can be cleared there`() {
        val tracker = tracker(codexStrategy())
        tracker.onApplicationTitleChanged(codexTitle(idA, "First"))
        val session = session()
        tracker.attach(session)
        assertEquals(idA, session.identity?.sessionId)

        tracker.onApplicationTitleChanged(codexTitle("01a0eeee-0000-7000-8000-000000000009", "Unknown"))
        assertNull(session.identity)
        assertNull(tracker.identity)
    }

    @Test
    fun `new in Codex restores the placeholder`() {
        val tracker = tracker(codexStrategy())
        tracker.onApplicationTitleChanged(codexTitle(idA, "First"))
        tracker.onApplicationTitleChanged(codexTitle(idB))
        assertEquals(TabTitle("Chat #3", null), shown.last())
        assertEquals(idB, tracker.identity?.sessionId)
    }

    @Test
    fun `spinner frames neither republish nor resolve again`() {
        val strategy = codexStrategy()
        val tracker = tracker(strategy)
        tracker.onApplicationTitleChanged(codexTitle(idA, "Reply ok"))
        tracker.onApplicationTitleChanged(codexTitle(idA, "Reply ok", spinner = "⠋"))
        tracker.onApplicationTitleChanged(codexTitle(idA, "Reply ok", spinner = "⠙"))
        assertEquals(1, shown.size)
        assertEquals(1, strategy.resolveCalls)
    }

    @Test
    fun `an unresolvable prefix stays unknown and is retried on the tick`() {
        val strategy = FakeStrategy(CodexTitleParser::parse)
        val tracker = tracker(strategy)
        tracker.attach(session())
        tracker.onApplicationTitleChanged(codexTitle(idA))
        assertNull(tracker.identity)

        strategy.ids[idA] = SessionIdentity(idA, null) // Codex wrote the thread's first record.
        scheduler.tick()
        assertEquals(idA, tracker.identity?.sessionId)
    }

    @Test
    fun `a transcript path that did not exist yet is found once it does`() {
        val strategy = FakeStrategy(CodexTitleParser::parse).apply { ids[idA] = SessionIdentity(idA, null) }
        val tracker = tracker(strategy)
        tracker.attach(session())
        tracker.onApplicationTitleChanged(codexTitle(idA, "Renamed before the first turn"))
        assertEquals(SessionIdentity(idA, null), tracker.identity)

        strategy.ids[idA] = SessionIdentity(idA, "/r/$idA.jsonl") // The first turn wrote the rollout.
        scheduler.tick()
        assertEquals(SessionIdentity(idA, "/r/$idA.jsonl"), tracker.identity)

        val calls = strategy.resolveCalls
        scheduler.tick()
        assertEquals(calls, strategy.resolveCalls) // Complete: no more lookups.
    }

    @Test
    fun `a resolved identity is checked again, and follows a revert to a new transcript`() {
        val strategy = codexStrategy()
        val tracker = tracker(strategy)
        val session = session()
        tracker.attach(session)
        tracker.onApplicationTitleChanged(codexTitle(idA, "Reply ok"))
        assertEquals(SessionIdentity(idA, "/r/$idA.jsonl"), session.identity)

        // thread/revert: same thread id, so the same title, but a new rollout file.
        strategy.ids[idA] = SessionIdentity(idA, "/r/${idA}_reverted.jsonl")
        val calls = strategy.resolveCalls
        repeat(ChatSessionTracker.RECHECK_TICKS - 1) { tracker.slowTick() }
        assertEquals(calls, strategy.resolveCalls)
        tracker.slowTick()
        assertEquals(SessionIdentity(idA, "/r/${idA}_reverted.jsonl"), session.identity)
    }

    @Test
    fun `an ambiguous prefix leaves the identity unknown but the name correct`() {
        val strategy = FakeStrategy(CodexTitleParser::parse).apply {
            ids[idA] = SessionIdentity(idA, null)
            ids["${idA.take(29)}ffffff0"] = SessionIdentity("${idA.take(29)}ffffff0", null)
        }
        val tracker = tracker(strategy)
        tracker.onApplicationTitleChanged(codexTitle(idA, "Reply ok"))
        assertNull(tracker.identity)
        assertEquals("Reply ok", shown.last().label)
    }

    @Test
    fun `a cut-off name takes the full name from the store, for the tooltip only`() {
        val strategy = codexStrategy()
        val full = "Investigate the flaky integration tests in the payments service"
        strategy.names[idA] = full
        val tracker = tracker(strategy)
        tracker.attach(session())
        val visible = full.take(45) + "..."
        tracker.onApplicationTitleChanged(codexTitle(idA, visible))
        assertEquals(TabTitle("Investigate the flaky…", full), shown.last())
    }

    @Test
    fun `a rename past the visible part reaches the tooltip without a new title`() {
        val strategy = codexStrategy()
        val head = "Investigate the flaky integration tests in th"
        strategy.names[idA] = "${head}e payments service"
        val tracker = tracker(strategy)
        tracker.attach(session())
        tracker.onApplicationTitleChanged(codexTitle(idA, "$head..."))
        assertEquals("${head}e payments service", shown.last().name)

        strategy.names[idA] = "${head}e billing service"
        scheduler.tick()
        assertEquals("${head}e billing service", shown.last().name)
    }

    @Test
    fun `a cut-off name with collapsed whitespace still takes the full name`() {
        val strategy = codexStrategy()
        val stored = "Investigate  flaky  integration  tests  in  the  payments  service"
        strategy.names[idA] = stored
        val tracker = tracker(strategy)
        tracker.attach(session())
        // Codex cut the name to 48 graphemes, then collapsed its double spaces: 43 are left.
        val visible = "Investigate flaky integration tests in t..."
        tracker.onApplicationTitleChanged(codexTitle(idA, visible))
        assertEquals(TabTitle("Investigate flaky…", CodexTitleParser.normalize(stored)), shown.last())
    }

    @Test
    fun `a user name ending in dots keeps its own name`() {
        val strategy = codexStrategy()
        strategy.names[idA] = "Wait for it..."
        val tracker = tracker(strategy)
        tracker.attach(session())
        tracker.onApplicationTitleChanged(codexTitle(idA, "Wait for it..."))
        scheduler.tick()
        assertEquals(listOf(TabTitle("Wait for it...", "Wait for it...")), shown)
    }

    @Test
    fun `a stored name that does not extend the visible text is not taken`() {
        val strategy = codexStrategy()
        val visible = "B".repeat(45) + "..."
        strategy.names[idA] = "An older name that does not match the title at all, at all"
        val tracker = tracker(strategy)
        tracker.attach(session())
        tracker.onApplicationTitleChanged(codexTitle(idA, visible))
        scheduler.tick()
        assertEquals(visible, shown.last().name)
    }

    @Test
    fun `a name that is not cut off never reads the store`() {
        val strategy = codexStrategy()
        strategy.names[idA] = "Reply ok, and more that should never show"
        val tracker = tracker(strategy)
        tracker.attach(session())
        tracker.onApplicationTitleChanged(codexTitle(idA, "Reply ok"))
        scheduler.tick()
        assertEquals(TabTitle("Reply ok", "Reply ok"), shown.last())
    }

    // ── Session name ──

    @Test
    fun `the session takes each name and gets its placeholder back when there is none`() {
        val tracker = tracker(FakeStrategy(ClaudeTitleParser::parse))
        val session = session()
        tracker.attach(session)
        assertEquals(emptyList<String>(), renamed)

        tracker.onApplicationTitleChanged("✳ Investigate renamed conversation")
        assertEquals("Investigate renamed conversation", session.name)
        tracker.onApplicationTitleChanged("✳ Claude Code")
        assertEquals("Chat #3", session.name)
        assertEquals(listOf("Investigate renamed conversation", "Chat #3"), renamed)
    }

    @Test
    fun `a name shown before attach reaches the session when it attaches`() {
        val tracker = tracker(FakeStrategy(ClaudeTitleParser::parse))
        tracker.onApplicationTitleChanged("✳ Named early")
        val session = session()
        tracker.attach(session)
        assertEquals("Named early", session.name)
        assertEquals(listOf("Named early"), renamed)
    }

    @Test
    fun `the session takes the full name behind a cut-off title, and its later changes`() {
        val strategy = codexStrategy()
        val head = "Investigate the flaky integration tests in th"
        strategy.names[idA] = "${head}e payments service"
        val tracker = tracker(strategy)
        val session = session()
        tracker.attach(session)
        tracker.onApplicationTitleChanged(codexTitle(idA, "$head..."))
        assertEquals("${head}e payments service", session.name)

        strategy.names[idA] = "${head}e billing service"
        scheduler.tick()
        assertEquals("${head}e billing service", session.name)
        assertEquals(listOf("$head...", "${head}e payments service", "${head}e billing service"), renamed)
    }

    @Test
    fun `nothing is shown and no work is scheduled after dispose`() {
        val tracker = tracker(codexStrategy())
        tracker.attach(session())
        tracker.dispose()
        assertEquals(0, scheduler.periodic.size)
        tracker.onApplicationTitleChanged(codexTitle(idA, "Reply ok"))
        assertEquals(emptyList<TabTitle>(), shown)
    }
}
