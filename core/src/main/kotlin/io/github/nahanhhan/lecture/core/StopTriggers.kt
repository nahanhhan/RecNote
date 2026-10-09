package io.github.nahanhhan.lecture.core

/** 录音停止的原因。当前只有手动会真正产生停止，其余为后续触发器预留。 */
enum class StopReason { MANUAL, SCHEDULE_END, MOTION, KEYWORD }

/** 一次录音会话的上下文；[occurrence] 为空表示该录音没有关联日程。 */
data class SessionContext(val lessonId: String, val occurrence: Occurrence?, val startedAtMs: Long)

/** 触发器何时开始监听。 */
sealed interface ArmingPolicy {
    /** 会话开始即挂载。 */
    object Immediately : ArmingPolicy

    /**
     * 关联日程的出现项结束后才挂载，防止课堂互动误触发；没有关联日程的录音永不挂载，
     * 需要其他规则的触发器应使用 [Custom]。
     */
    object AfterScheduleEnd : ArmingPolicy

    /** 由触发器自行判断是否应当挂载。 */
    class Custom(val isReady: (SessionContext, Long) -> Boolean) : ArmingPolicy
}

/** 停止触发器扩展点：到达挂载条件后被启动，条件成立时调用 requestStop 请求停止录音。 */
interface StopTrigger {
    val id: String
    val arming: ArmingPolicy

    /** 是否为自动触发器（需要监听、调度）。手动触发器为 false。 */
    val automatic: Boolean get() = true

    fun start(context: SessionContext, requestStop: (StopReason) -> Unit)
    fun stop()
}

/** 手动停止：不监听任何信号，用户操作直接经注册表请求停止。 */
class ManualStopTrigger : StopTrigger {
    override val id = "manual"
    override val arming: ArmingPolicy = ArmingPolicy.Immediately
    override val automatic = false
    override fun start(context: SessionContext, requestStop: (StopReason) -> Unit) {}
    override fun stop() {}
}

/**
 * 停止请求的统一入口与触发器挂载管理。[onStop] 在每次会话中至多被调用一次。
 * 一次会话以 [beginSession] 开始、[endSession] 结束；会话间状态（含「本次改为手动」）互不影响。
 */
class StopTriggerRegistry(private val onStop: (StopReason) -> Unit) {
    private val triggers = mutableListOf<StopTrigger>()
    private val running = linkedSetOf<String>()
    private var session: SessionContext? = null
    private var manualOnly = false
    private var stopRequested = false

    /** 是否注册了需要调度的自动触发器；为 false 时无需定时调用 [tick]。 */
    @get:Synchronized val hasAutomatic: Boolean get() = triggers.any { it.automatic }
    @get:Synchronized val isManualOnly: Boolean get() = manualOnly

    @Synchronized fun register(trigger: StopTrigger) {
        require(triggers.none { it.id == trigger.id }) { "触发器已注册：${trigger.id}" }
        triggers.add(trigger)
    }

    @Synchronized fun beginSession(context: SessionContext) {
        stopAll()
        session = context
        manualOnly = false
        stopRequested = false
    }

    /** 按挂载条件启动尚未启动的触发器；已改为手动、已请求停止或没有会话时不做任何事。 */
    @Synchronized fun tick(nowMs: Long) {
        val current = session ?: return
        if (manualOnly || stopRequested) return
        for (trigger in triggers.toList()) {
            if (trigger.id in running || !isReady(trigger.arming, current, nowMs)) continue
            running.add(trigger.id)
            trigger.start(current) { reason -> requestStop(reason) }
            if (stopRequested) return
        }
    }

    /** 请求停止。幂等；改为手动后只接受手动原因。返回本次请求是否被接受。 */
    @Synchronized fun requestStop(reason: StopReason): Boolean {
        if (stopRequested || (manualOnly && reason != StopReason.MANUAL)) return false
        stopRequested = true
        stopAll()
        onStop(reason)
        return true
    }

    /** 本次改为手动：停止并不再启动所有自动触发器，直到本次会话结束。不影响手动停止。 */
    @Synchronized fun switchToManual() {
        manualOnly = true
        stopAll()
    }

    @Synchronized fun endSession() {
        stopAll()
        session = null
        manualOnly = false
        stopRequested = false
    }

    private fun stopAll() {
        val started = running.toList()
        running.clear()
        triggers.filter { it.id in started }.forEach { it.stop() }
    }

    private fun isReady(policy: ArmingPolicy, context: SessionContext, nowMs: Long): Boolean = when (policy) {
        ArmingPolicy.Immediately -> true
        ArmingPolicy.AfterScheduleEnd -> context.occurrence?.let { nowMs >= it.endMs } ?: false
        is ArmingPolicy.Custom -> policy.isReady(context, nowMs)
    }
}
