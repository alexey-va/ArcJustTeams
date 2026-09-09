package ru.ruscrafting.justteams

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import ru.arc.paper.menu.PaperDialogBody

/** Uses ARC's resource-pack table renderer when ARC is installed, with a readable fallback. */
internal object DialogTables {
    fun body(rows: List<Pair<Component, Component>>, width: Int = 468): PaperDialogBody {
        val rendered = runCatching {
            val owner = Class.forName("ru.arc.gui.DialogTables")
            val loader = owner.classLoader
            val pairType = Class.forName("kotlin.Pair", true, loader)
            val frameType = Class.forName("${owner.name}\$Frame", true, loader)
            val columnsType = Class.forName("${owner.name}\$Columns", true, loader)
            val method = owner.getMethod("render", List::class.java, pairType, frameType, Int::class.javaPrimitiveType, columnsType)
            val pairs = rows.map { (label, value) -> pairType.getConstructor(Any::class.java, Any::class.java).newInstance(label, value) }
            val frame = frameType.enumConstants.first { (it as Enum<*>).name == "EPIC" }
            val columns = columnsType.enumConstants.first { (it as Enum<*>).name == "VALUE_WIDE" }
            val result = method.invoke(owner.getField("INSTANCE").get(null), pairs, null, frame, width, columns)
            result.javaClass.getMethod("getComponent").invoke(result) as Component
        }.getOrNull()
        return PaperDialogBody(rendered ?: Component.join(JoinConfiguration.newlines(), rows.map { (label, value) ->
            label.append(Component.text(": ")).append(value)
        }), width)
    }
}
