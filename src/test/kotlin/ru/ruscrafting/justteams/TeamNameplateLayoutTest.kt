package ru.ruscrafting.justteams

import org.bukkit.entity.Display
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamNameplateLayoutTest {
    @Test
    fun `extra rows clear native font and rank badge bounds in every camera orientation`() {
        val clan = clanNameplateOptions()
        val health = healthNameplateOptions()
        // Mojang 1.21.11 NameTagFeatureRenderer.Storage and TextDisplayRenderer background bounds.
        // Survival rank_count is height 10 / ascent 9: baseline 7 puts its top at -2 px.
        // Union with the native background therefore reaches 2 px above the name anchor.
        val nativeBottom = -9 * FONT_PIXEL
        val nativeTop = 2 * FONT_PIXEL
        val clanBottom = clan.verticalOffset
        val healthTop = health.verticalOffset + 10 * FONT_PIXEL * health.scale
        for (mode in CameraMode.entries) {
            for (yaw in listOf(0F, 73F, 180F)) {
                for (pitch in listOf(-75F, -30F, 0F, 30F, 75F)) {
                    val camera = cameraRotation(mode, yaw, pitch)
                    val context = "$mode yaw=$yaw pitch=$pitch"
                    assertTrue(screenUp(clanBottom, camera) - screenUp(nativeTop, camera) >= 0.049F, context)
                    assertTrue(screenUp(nativeBottom, camera) - screenUp(healthTop, camera) >= FONT_PIXEL, context)
                }
            }
        }
    }

    @Test
    fun `lower row leaves head clearance for ordinary viewing angles and both rows stay lit`() {
        val health = healthNameplateOptions()
        for (pitch in listOf(-30.0, 0.0, 30.0)) {
            val projectedHeadClearance = 0.5 * cos(Math.toRadians(pitch)) + health.verticalOffset
            assertTrue(projectedHeadClearance > 0.015, "pitch=$pitch lower background clears head origin")
        }
        for (options in listOf(clanNameplateOptions(), health)) {
            assertTrue(options.shadowed)
            assertEquals(Display.Brightness(15, 15), options.brightness,
                "Nameplate text must retain its own light level in a dark cave")
        }
    }

    private enum class CameraMode { FIRST_PERSON, REAR_THIRD_PERSON, FRONT_THIRD_PERSON }

    private fun cameraRotation(mode: CameraMode, yaw: Float, pitch: Float): Quaternionf {
        val front = mode == CameraMode.FRONT_THIRD_PERSON
        return Quaternionf().rotationYXZ(
            Math.toRadians(-(yaw + if (front) 180F else 0F).toDouble()).toFloat(),
            Math.toRadians((if (front) -pitch else pitch).toDouble()).toFloat(),
            0F,
        )
    }

    private fun screenUp(row: Float, camera: Quaternionf): Float {
        // All rows now start at the same WORLD anchor; their displacement rotates only after it.
        val anchor = Vector3f(12F, 65.8F + 0.5F, -4F)
        val point = camera.transform(Vector3f(0F, row, 0F)).add(anchor)
        return Quaternionf(camera).conjugate().transform(point.sub(anchor)).y
    }

    private companion object {
        const val FONT_PIXEL = 0.025F
    }
}
