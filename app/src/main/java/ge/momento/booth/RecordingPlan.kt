package ge.momento.booth

/** Requested durations refer to FINAL playback time, not original camera time. */
data class RecordingPlan(val finalSeconds: Int, val slowStartSeconds: Int, val slowSeconds: Int, val speed: Double) {
    init {
        require(finalSeconds in 1..120)
        require(slowStartSeconds >= 0 && slowSeconds >= 1 && slowStartSeconds + slowSeconds <= finalSeconds)
        require(speed in listOf(0.5, 0.25, 0.125))
    }
    val realCaptureSeconds: Double get() = finalSeconds - slowSeconds + slowSeconds * speed
    val sourceSlowStartSeconds: Double get() = slowStartSeconds.toDouble()
    val sourceSlowEndSeconds: Double get() = slowStartSeconds + slowSeconds * speed
    val normalAfterSeconds: Int get() = finalSeconds - slowStartSeconds - slowSeconds
}
