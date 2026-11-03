package dev.ciel.desktop.ui

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Seamless animation time for the crystal (Compose and the View-based overlays).
 *
 * Why: the previous animation multiplied one clock by a state-dependent speed. Changing state
 * (idle → thinking) changed the multiplier and every ring jumped to a new angle, and the clock
 * wrapped every few minutes, snapping rotations back. Here each motion is a *phase* that is
 * integrated frame by frame (phase += dt × speed). Speeds ease towards their targets, so a state
 * change only alters how fast things move, never where they are. Phases are measured in whole
 * cycles and only ever used through periodic functions, so they loop without a visible seam.
 */
class MotionClock {
    /** Seconds of animation time (scaled by the animation level). */
    var time = 0.0; private set
    /** Ring rotation, in revolutions. */
    var ring = 0.0; private set
    /** Breathing / speaking pulse, in cycles. */
    var pulse = 0.0; private set
    /** Slow core spin, in revolutions (one every 90 s). */
    var drift = 0.0; private set

    private var ringSpeed = 0.0
    private var pulseSpeed = 0.0
    private var primed = false

    /**
     * Advances by [dtSeconds]. [targetRing] is in revolutions per second, [targetPulse] in cycles
     * per second. [ease] is the time constant (seconds) for speed changes.
     */
    fun advance(dtSeconds: Double, targetRing: Double, targetPulse: Double, timeScale: Double = 1.0, ease: Double = 0.45) {
        val dt = dtSeconds.coerceIn(0.0, 0.1) * timeScale // a long pause (app in background) never causes a jump
        if (!primed) { ringSpeed = targetRing; pulseSpeed = targetPulse; primed = true }
        val k = 1.0 - exp(-dt / ease)
        ringSpeed += (targetRing - ringSpeed) * k
        pulseSpeed += (targetPulse - pulseSpeed) * k
        time += dt
        ring = (ring + dt * ringSpeed) % 1_000.0      // whole revolutions → wrap is invisible
        pulse = (pulse + dt * pulseSpeed) % 1_000.0   // whole cycles
        drift = (drift + dt / 90.0) % 1_000.0
    }

    companion object {
        const val TAU = 2.0 * PI

        /** sin of a phase measured in cycles. */
        fun wave(cycles: Double): Float = sin(cycles * TAU).toFloat()

        /** Angle in degrees for a phase measured in revolutions, times an integer multiple. */
        fun degrees(revolutions: Double, multiple: Int = 1): Float = ((revolutions * multiple % 1.0) * 360.0).toFloat()

        /** 0 → 1 → 0 envelope over one cycle; used to fade looping particles in and out. */
        fun envelope(fraction: Double): Float = sin(fraction.coerceIn(0.0, 1.0) * PI).toFloat()

        /** Fractional part, always in [0, 1). */
        fun frac(x: Double): Double = x - kotlin.math.floor(x)
    }
}
