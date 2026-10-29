package ru.ruscrafting.justteams

import org.bukkit.entity.Display
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamNameplateLayoutTest {
    @Test
    fun `one plane keeps rank clan nickname and health glyph bands separate`() {
        val options = teamNameplateOptions()
        // Pinned 1.21.11 TextDisplayRenderer advances by font.lineHeight + 1 = 10 px.
        // Live Survival rank_count height 10 / ascent 9 starts at baseline 7 - 9 = -2 px.
        // Bounds include a one-pixel text shadow; the shield is one pixel taller than text.
        for (hasClan in listOf(false, true)) {
            val glyphs = buildList {
                add(-2F to 9F) // Rank badge, including its shadow.
                if (hasClan) add(0F to 8F)
                add(0F to 8F) // Real nickname.
                add(0F to 9F) // Health and shield.
            }.mapIndexed { line, (top, bottom) -> (line * LINE_STRIDE + top) to (line * LINE_STRIDE + bottom) }
            for (mode in CameraMode.entries) {
                for (yaw in listOf(0F, 73F, 180F)) {
                    for (pitch in listOf(-75F, -30F, 0F, 30F, 75F)) {
                        val camera = cameraRotation(mode, yaw, pitch)
                        for ((upper, lower) in glyphs.zipWithNext()) {
                            val upperBottom = screenUp(upper.second, glyphs.size, camera)
                            val lowerTop = screenUp(lower.first, glyphs.size, camera)
                            assertTrue(upperBottom - lowerTop >= FONT_PIXEL * options.scale - 0.0001F,
                                "hasClan=$hasClan $mode yaw=$yaw pitch=$pitch keeps at least one font pixel between rows")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `passenger plate clears standing crouching and swimming heads at every camera angle`() {
        val options = teamNameplateOptions()
        assertEquals(0.3F, options.verticalOffset)
        for (height in listOf(1.8F, 1.5F, 0.6F)) {
            val head = Vector3f(12F, 64F + height, -4F)
            for (mode in CameraMode.entries) {
                for (pitch in listOf(-75F, -45F, 0F, 45F, 75F)) {
                    val camera = cameraRotation(mode, 73F, pitch)
                    val bottom = camera.transform(Vector3f(0F, options.verticalOffset, 0F)).add(head)
                    val projected = Quaternionf(camera).conjugate().transform(bottom.sub(head))
                    assertEquals(0.3F, projected.y, 0.0001F, "$mode height=$height pitch=$pitch")
                    assertEquals(0F, projected.x, 0.0001F)
                }
            }
        }
        assertTrue(options.shadowed)
        assertEquals(Display.Brightness(15, 15), options.brightness,
            "The single surface must retain its light level in a dark cave")
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

    private fun screenUp(fontY: Float, lineCount: Int, camera: Quaternionf): Float {
        val options = teamNameplateOptions()
        val localY = options.verticalOffset + (lineCount * LINE_STRIDE - 1 - fontY) * FONT_PIXEL * options.scale
        // Every row belongs to this one billboard transform and head-relative world anchor.
        // This is a source geometry guard, not observation of the native client or its motion.
        val anchor = Vector3f(12F, 65.8F, -4F)
        val point = camera.transform(Vector3f(0F, localY, 0F)).add(anchor)
        return Quaternionf(camera).conjugate().transform(point.sub(anchor)).y
    }

    private companion object {
        const val FONT_PIXEL = 0.025F
        const val LINE_STRIDE = 10
    }
}
