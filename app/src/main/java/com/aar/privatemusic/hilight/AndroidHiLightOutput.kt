package com.aar.privatemusic.hilight

import android.content.Context
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Binder
import android.os.IBinder
import androidx.annotation.RequiresApi

/**
 * Android 17 public animation API, resolved only on supported devices. The project still
 * compiles with SDK 36: the installed SDK repository does not yet provide platform 37.
 * Reflection is confined to this adapter; missing APIs fail closed before enabling the UI.
 */
@RequiresApi(37)
internal class AndroidHiLightOutput private constructor(private val port: Port) : HiLightOutput {
    private val sequenceClass = Class.forName("android.hardware.lights.ColorSequence")
    private val sequenceBuilderClass = Class.forName("android.hardware.lights.ColorSequence\$Builder")
    private val effectClass = Class.forName("android.hardware.lights.MultiLightEffect")
    private val effectBuilderClass = Class.forName("android.hardware.lights.MultiLightEffect\$Builder")
    private val lights = port.lights.filter {
        it.type == 10 && it.hasRgbControl() &&
            Light::class.java.getMethod("hasAnimationControl").invoke(it) == true
    }
    val supported: Boolean = lights.size == 8
    private var opened = false
    private var colorOffset = 0
    private val colors = intArrayOf(
        0xffc00000.toInt(), 0xffc06000.toInt(), 0xffc0c000.toInt(),
        0xff00c000.toInt(), 0xff00c0c0.toInt(), 0xff0060c0.toInt(),
        0xff6000c0.toInt(), 0xffc000c0.toInt(),
    )

    override fun pulse() {
        check(supported)
        if (!opened) {
            opened = true
            port.open()
        }
        val effect = effectBuilderClass.getConstructor().newInstance()
        for ((index, light) in lights.sortedBy { it.id }.withIndex()) {
            val period = (Light::class.java.getMethod("getMinUpdatePeriodMillis")
                .invoke(light) as Number).toLong().coerceIn(1, 250)
            fun frames(ms: Long) = maxOf(period, ms / period * period)
            val sequence = sequenceBuilderClass.getConstructor().newInstance()
            sequenceBuilderClass.getMethod("setInterpolationMode", Int::class.javaPrimitiveType)
                .invoke(sequence, sequenceClass.getField("INTERPOLATION_MODE_LINEAR").getInt(null))
            val point = sequenceBuilderClass.getMethod("addControlPoint", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            point.invoke(sequence, 0L, 0xff000000.toInt())
            // Rotate saturated colours across the array, with a 75% channel peak (was 6%).
            // Each finite pulse still ends in black; the PCM onset detector triggers each pulse.
            point.invoke(sequence, frames(33), colors[(index + colorOffset) % colors.size])
            point.invoke(sequence, frames(231), 0xff000000.toInt())
            effectBuilderClass.getMethod("addLightSequence", Light::class.java, sequenceClass)
                .invoke(effect, light, sequenceBuilderClass.getMethod("build").invoke(sequence))
        }
        effectBuilderClass.getMethod("setIterations", Int::class.javaPrimitiveType).invoke(effect, 1)
        effectBuilderClass.getMethod("setPreemptive", Boolean::class.javaPrimitiveType).invoke(effect, true)
        port.effect(effectBuilderClass.getMethod("build").invoke(effect), effectClass)
        colorOffset = (colorOffset + 1) % colors.size
    }

    override fun stop() {
        if (!opened) return
        try {
            // Cancel the animation explicitly before releasing ownership; some firmware retains
            // the last colour on close. Both Android black representations are sent, then closed.
            port.black(lights, 0xff000000.toInt())
            Thread.sleep(66)
            port.black(lights, 0)
            Thread.sleep(66)
        } finally {
            port.close()
            opened = false
        }
    }

    private interface Port {
        val lights: List<Light>
        fun open()
        fun effect(value: Any, type: Class<*>)
        fun black(lights: List<Light>, color: Int)
        fun close()
    }

    companion object {
        fun direct(context: Context): AndroidHiLightOutput {
            val manager = requireNotNull(context.getSystemService(LightsManager::class.java))
            return AndroidHiLightOutput(object : Port {
                override val lights = manager.lights
                private var session: LightsManager.LightsSession? = null
                override fun open() { session = manager.openSession() }
                override fun effect(value: Any, type: Class<*>) {
                    val builder = LightsRequest.Builder()
                    LightsRequest.Builder::class.java.getMethod("setEffect", type).invoke(builder, value)
                    requireNotNull(session).requestLights(builder.build())
                }
                override fun black(lights: List<Light>, color: Int) {
                    val builder = LightsRequest.Builder()
                    lights.forEach { builder.addLight(it, LightState.Builder().setColor(color).build()) }
                    session?.requestLights(builder.build())
                }
                override fun close() { session?.close(); session = null }
            })
        }

        /** Shizuku's plain shell process has no application Context for LightsManager. */
        fun privileged(): AndroidHiLightOutput {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "lights") as IBinder
            val api = Class.forName("android.hardware.lights.ILightsManager")
            val service = Class.forName("android.hardware.lights.ILightsManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            return AndroidHiLightOutput(object : Port {
                private val token = Binder()
                @Suppress("UNCHECKED_CAST")
                override val lights = api.getMethod("getLights").invoke(service) as List<Light>
                override fun open() {
                    // Low priority leaves system calls / Gemini in control when they need it.
                    api.getMethod("openSession", IBinder::class.java, Int::class.javaPrimitiveType)
                        .invoke(service, token, -10)
                }
                override fun effect(value: Any, type: Class<*>) {
                    api.getMethod("setLightEffect", IBinder::class.java, type).invoke(service, token, value)
                }
                override fun black(lights: List<Light>, color: Int) {
                    api.getMethod("setLightStates", IBinder::class.java, IntArray::class.java, Array<LightState>::class.java)
                        .invoke(service, token, lights.map { it.id }.toIntArray(),
                            lights.map { LightState.Builder().setColor(color).build() }.toTypedArray())
                }
                override fun close() { api.getMethod("closeSession", IBinder::class.java).invoke(service, token) }
            })
        }
    }
}
