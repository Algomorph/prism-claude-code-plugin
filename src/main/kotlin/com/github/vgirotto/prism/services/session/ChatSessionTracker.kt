package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.model.AgentSession
import com.github.vgirotto.prism.services.ChatName
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.jediterm.terminal.model.TerminalApplicationTitleListener
import java.util.concurrent.TimeUnit

/** What a chat tab shows: its [label], and the chat's whole [name] for the tooltip (null: none). */
data class TabTitle(val label: String, val name: String?)

/**
 * Keeps one chat tab's name and session identity in step with its agent CLI.
 *
 *  - **Name.** The terminal title decides it. The latest title the [strategy] recognizes wins: a
 *    named reading sets the label, an unnamed one restores [placeholder] (`Chat #N`), and a title
 *    it does not recognize (the shell's, a transient frame) changes nothing. No file is read for
 *    the label.
 *  - **Identity.** Follows the strategy's identity events (Claude's hook) and the id the title
 *    carries (Codex): a title whose id disagrees with the known identity clears it, and it is
 *    resolved again off the terminal thread. It is stored on the attached [AgentSession], for
 *    other features to read; see [AgentSession.identity] for how current it is. A resolved identity is resolved again every [RECHECK_TICKS] slow
 *    ticks, since its transcript can change under the same id (Codex's `thread/revert`).
 *  - **Full name.** While the title shows a name the CLI may have cut off, the full name is read
 *    from the CLI's store every [slowTickMs], independently of title events: Codex writes no new
 *    title when only the hidden end of a long name changes. The strategy takes a stored name only
 *    when the CLI would show it as the visible text; it changes the tooltip, never the label.
 *  - **Session name.** The attached [AgentSession.name] is the whole name, or [placeholder] while
 *    the chat has none, for what else shows the chat (the status bar, notifications, the next
 *    interaction in Changes). [renamed] follows each change.
 *
 * Title events arrive on the terminal emulator thread; [show] and [renamed] run on the UI thread,
 * in order.
 */
class ChatSessionTracker(
    private val strategy: AgentSessionStrategy,
    private val placeholder: String,
    private val show: (TabTitle) -> Unit,
    private val renamed: (AgentSession) -> Unit = {},
    private val scheduler: Scheduler = PlatformScheduler,
    private val fastTickMs: Long = 500,
    private val slowTickMs: Long = 2_000,
) : TerminalApplicationTitleListener, Disposable {

    /** Where the tracker runs its work; replaced in tests. */
    interface Scheduler {
        fun onUi(task: () -> Unit)
        fun inBackground(task: () -> Unit)
        /** Runs [task] every [periodMs] until the returned function is called. */
        fun every(periodMs: Long, task: () -> Unit): () -> Unit
    }

    private val lock = Any()
    /**
     * Held across one read-and-apply of identity events, so they apply in the order they were
     * read. Never taken while holding [lock]; the file read stays outside [lock].
     */
    private val pollLock = Any()
    @Volatile private var disposed = false

    private var session: AgentSession? = null
    private var detachedIdentity: SessionIdentity? = null
    private var events: IdentityEventSource? = null
    private val cancels = mutableListOf<() -> Unit>()

    private var reading: TitleReading? = null
    private var fullName: String? = null
    private var shown: TabTitle? = null
    /** Bumped whenever a title invalidates the identity, so a stale resolution is dropped. */
    private var generation = 0
    private var slowTicks = 0

    /** The session this tab shows, when known. */
    val identity: SessionIdentity?
        get() = synchronized(lock) { currentIdentity() }

    /** The title the tab was last told to show, if any. */
    val title: TabTitle?
        get() = synchronized(lock) { shown }

    /**
     * Binds the tracker to its [session] once that exists, and starts following identity events
     * and full names. Call it before the terminal starts, so no title is missed.
     */
    fun attach(session: AgentSession) {
        synchronized(lock) {
            if (disposed) return
            this.session = session
            syncSessionName()
            if (session.identity == null) session.identity = detachedIdentity
            detachedIdentity = null
            events = session.tabFiles?.let(strategy::identityEvents)
            if (events != null) cancels += scheduler.every(fastTickMs, ::pollEvents)
            cancels += scheduler.every(slowTickMs, ::slowTick)
        }
    }

    override fun onApplicationTitleChanged(title: String) {
        if (disposed) return
        val parsed = try { strategy.parseTitle(title) } catch (_: Exception) { null } ?: return
        var resolve: Pair<IdHint, Int>? = null
        synchronized(lock) {
            if (parsed != reading) {
                reading = parsed
                fullName = null
                val hint = parsed.idHint
                val known = currentIdentity()
                if (hint != null && (known == null || !hint.matches(known.sessionId))) {
                    setIdentity(null)
                    generation++
                    resolve = hint to generation
                }
                publish(titleFor(parsed))
            }
        }
        resolve?.let { (hint, gen) -> scheduler.inBackground { resolve(hint, gen) } }
        // A title change often follows a session switch: read the hook events now, not at the tick.
        if (events != null) scheduler.inBackground(::pollEvents)
    }

    /**
     * Takes the newest identity event, if any arrived. The tick and title changes both call it;
     * without [pollLock], a poll that read an older event could apply it after a later poll
     * applied a newer one, and since both events are consumed, nothing would repair it.
     */
    fun pollEvents() = synchronized(pollLock) {
        val source = synchronized(lock) { events } ?: return
        val latest = try { source.poll() } catch (_: Exception) { null } ?: return
        synchronized(lock) {
            if (!disposed) setIdentity(latest)
        }
    }

    /**
     * Retries an identity the title's id hint has not fully resolved, and refreshes a cut-off
     * name. Unresolved includes a known id without a transcript path: a Codex thread renamed
     * before its first turn has no rollout file until that turn starts. A resolved identity is
     * checked again every [RECHECK_TICKS] ticks: `thread/revert` moves a Codex thread to a new
     * rollout file, and the title, which shows only the thread id, does not change.
     */
    fun slowTick() {
        val target = synchronized(lock) {
            val hint = reading?.idHint
            val known = currentIdentity()
            val incomplete = known == null || known.transcriptPath == null
            val recheck = ++slowTicks % RECHECK_TICKS == 0
            if (!disposed && hint != null && (incomplete || recheck)) hint to generation else null
        }
        if (target != null) resolve(target.first, target.second) else refreshFullName()
    }

    private fun resolve(hint: IdHint, gen: Int) {
        if (disposed) return
        val resolved = try { strategy.resolveIdentity(hint) } catch (_: Exception) { null }
        synchronized(lock) {
            if (disposed || gen != generation || resolved == null) return
            if (!hint.matches(resolved.sessionId)) return
            setIdentity(resolved)
        }
        refreshFullName()
    }

    private fun refreshFullName() {
        val (visible, known) = synchronized(lock) {
            val current = reading as? TitleReading.Named
            if (disposed || current == null || !current.mayBeCutOff) return
            current to (currentIdentity() ?: return)
        }
        // Null until the store holds a name that matches the title: keep what the title shows.
        val stored = try { strategy.fullName(known, visible.name) } catch (_: Exception) { null } ?: return
        synchronized(lock) {
            if (disposed || reading != visible || currentIdentity() != known) return
            if (fullName == stored) return
            fullName = stored
            publish(titleFor(visible))
        }
    }

    private fun titleFor(reading: TitleReading): TabTitle = when (reading) {
        is TitleReading.Unnamed -> TabTitle(placeholder, null)
        is TitleReading.Named -> TabTitle(ChatName(reading.name).display(), fullName ?: reading.name)
    }

    /** Must hold [lock]. */
    private fun publish(title: TabTitle) {
        if (title == shown) return
        shown = title
        scheduler.onUi { if (!disposed) show(title) }
        syncSessionName()
    }

    /** Must hold [lock]. */
    private fun syncSessionName() {
        val attached = session ?: return
        val name = shown?.name ?: placeholder
        if (attached.name == name) return
        attached.name = name
        scheduler.onUi { if (!disposed) renamed(attached) }
    }

    /** Must hold [lock]. */
    private fun currentIdentity(): SessionIdentity? = session.let { if (it != null) it.identity else detachedIdentity }

    /** Must hold [lock]. */
    private fun setIdentity(identity: SessionIdentity?) {
        val attached = session
        if (attached != null) attached.identity = identity else detachedIdentity = identity
    }

    override fun dispose() {
        val toCancel = synchronized(lock) {
            disposed = true
            cancels.toList().also { cancels.clear() }
        }
        toCancel.forEach { it() }
    }

    companion object {
        /** Slow ticks between checks of a resolved identity (30 s at the default tick). */
        const val RECHECK_TICKS = 15
    }

    /** The IDE's UI thread, pooled threads and scheduled executor. */
    object PlatformScheduler : Scheduler {
        override fun onUi(task: () -> Unit) {
            ApplicationManager.getApplication().invokeLater(task)
        }

        override fun inBackground(task: () -> Unit) {
            ApplicationManager.getApplication().executeOnPooledThread(task)
        }

        override fun every(periodMs: Long, task: () -> Unit): () -> Unit {
            val future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                { try { task() } catch (_: Exception) { /* next tick retries */ } },
                periodMs, periodMs, TimeUnit.MILLISECONDS,
            )
            return { future.cancel(false) }
        }
    }
}
