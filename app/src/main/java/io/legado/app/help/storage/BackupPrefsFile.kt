package io.legado.app.help.storage

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.InputStream

/**
 * 直接按 SharedPreferences XML 格式读写文件，绕开 ContextImpl 的
 * SharedPreferences 进程级缓存。
 *
 * 缓存实例只在首次创建时读盘，之后永不重读：本机每天的自动备份会创建并
 * 常驻备份目录 config.xml 对应的缓存实例；恢复时解压已把磁盘上的
 * config.xml 替换为备份包内容，再经缓存读取拿到的仍是本机上次备份的旧值。
 * 写入侧同理，缓存实例会残留上次备份的键（如后来开启「忽略阅读配置」前
 * 已备份的键）。备份/恢复对该文件一律走这里，直接读写字节。
 */
internal object BackupPrefsFile {

    /** 读取 SharedPreferences XML 文件；文件不存在或解析失败返回 null。 */
    fun read(file: File): MutableMap<String, Any?>? {
        if (!file.isFile) {
            return null
        }
        return runCatching {
            file.inputStream().use { input -> parse(input) }
        }.getOrNull()
    }

    /** 写入 SharedPreferences XML 文件（先写临时文件再替换，中断不留半截文件）。 */
    fun write(file: File, values: Map<String, Any?>): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().use { output ->
                val serializer = Xml.newSerializer()
                serializer.setOutput(output, Charsets.UTF_8.name())
                serializer.startDocument(null, true)
                serializer.startTag(null, "map")
                values.forEach { (key, value) ->
                    when (value) {
                        null -> serializer.simpleTag("null", key)
                        is Boolean -> serializer.simpleTag("boolean", key, value.toString())
                        is Int -> serializer.simpleTag("int", key, value.toString())
                        is Long -> serializer.simpleTag("long", key, value.toString())
                        is Float -> serializer.simpleTag("float", key, value.toString())
                        is String -> {
                            serializer.startTag(null, "string")
                            serializer.attribute(null, "name", key)
                            serializer.text(value)
                            serializer.endTag(null, "string")
                        }

                        is Set<*> -> {
                            serializer.startTag(null, "set")
                            serializer.attribute(null, "name", key)
                            value.forEach { item ->
                                serializer.startTag(null, "string")
                                serializer.text(item?.toString().orEmpty())
                                serializer.endTag(null, "string")
                            }
                            serializer.endTag(null, "set")
                        }
                    }
                }
                serializer.endTag(null, "map")
                serializer.endDocument()
                serializer.flush()
            }
            if (!tmp.renameTo(file)) {
                // rename 失败（如跨文件系统）：退回复制
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /** boolean/int/long/float/null 这类以 value 属性承载的自闭合标签。 */
    private fun XmlSerializer.simpleTag(type: String, key: String, value: String? = null) {
        startTag(null, type)
        attribute(null, "name", key)
        if (value != null) {
            attribute(null, "value", value)
        }
        endTag(null, type)
    }

    private fun parse(input: InputStream): MutableMap<String, Any?> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val map = linkedMapOf<String, Any?>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val key = parser.getAttributeValue(null, "name")
                if (key != null) {
                    when (parser.name) {
                        "string" -> map[key] = parser.nextText()
                        "boolean" -> map[key] = parser.attrValue().toBoolean()
                        "int" -> map[key] = parser.attrValue().toIntOrNull() ?: 0
                        "long" -> map[key] = parser.attrValue().toLongOrNull() ?: 0L
                        "float" -> map[key] = parser.attrValue().toFloatOrNull() ?: 0f
                        "null" -> map[key] = null
                        "set" -> {
                            val set = linkedSetOf<String>()
                            var inner = parser.next()
                            while (!(inner == XmlPullParser.END_TAG && parser.name == "set")) {
                                if (inner == XmlPullParser.START_TAG && parser.name == "string") {
                                    set.add(parser.nextText())
                                }
                                inner = parser.next()
                            }
                            map[key] = set
                        }
                    }
                }
            }
            event = parser.next()
        }
        return map
    }

    private fun XmlPullParser.attrValue(): String = getAttributeValue(null, "value").orEmpty()
}
