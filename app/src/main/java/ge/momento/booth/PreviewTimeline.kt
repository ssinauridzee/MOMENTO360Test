package ge.momento.booth

import kotlin.math.roundToLong

/** Pure math for changing presentation timestamps of an already-encoded preview track. */
object PreviewTimeline {
    fun toOutputTimeUs(sourceTimeUs: Long, plan: RecordingPlan): Long {
        val t = sourceTimeUs.coerceAtLeast(0L).toDouble()
        val start = plan.slowStartSeconds * 1_000_000.0
        val end = plan.sourceSlowEndSeconds * 1_000_000.0
        val stretched = when {
            t <= start -> t
            t <= end -> start + (t - start) / plan.speed
            else -> start + plan.slowSeconds * 1_000_000.0 + (t - end)
        }
        return stretched.roundToLong()
    }
}
