package io.legado.app.ui.common

import io.legado.app.R
import io.legado.app.exception.NoStackTraceException
import splitties.init.appCtx
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * 全应用唯一的「异常 → 用户可见文案」映射层。
 *
 * 设计原则（见《软件使用体验优化》架构文档 §4.2）：
 * - 分类逻辑只保留这一套，View 与 Compose 双端复用，禁止在 UI 层再写 `when (e is IOException)`；
 * - 任何路径都不向用户返回 `Throwable.localizedMessage`，日志路径（AppLog）仍保留原始异常；
 * - [classify] 是纯函数，任意线程可调；[messageFor]/[message] 内部只读字符串资源，线程安全。
 *
 * 发现页架构文档中的 `DiscoveryErrorType` 5 个枚举全量映射到 [UiErrorType] 子集，
 * 不再单独落地 `DiscoveryErrorMapper.kt`（迁移映射见《软件使用体验优化》§4.2）。
 */
enum class UiErrorType {
    /** 网络不可用 / 连接失败（IOException、SocketTimeoutException）。 */
    NETWORK,

    /** 主动超时（TimeoutCancellationException 或显式超时封装抛出的异常）。 */
    TIMEOUT,

    /** 数据格式解析失败（JSON/GSON/JsonPath 等）。 */
    PARSE_FORMAT,

    /** 书源规则失效（`ERROR:` 前缀条目 / 书源解析失败）。 */
    SOURCE_INVALID,

    /** 文件读取 IO 失败（FileNotFoundException 等）。 */
    FILE_IO,

    /** 文件类型不支持（导入/打开无法识别的格式）。 */
    FILE_UNSUPPORTED,

    /** 空结果（由状态机判断，非异常）。 */
    NO_DATA,

    /** 未识别异常。 */
    UNKNOWN
}

/**
 * 用户可见消息结果模型。
 *
 * @property text 已脱敏的用户可见文案。
 * @property canRetry 该错误是否适合提供「重试」入口。
 * @property errorType 分类结果，供调用方按需取更具体的上下文文案 key。
 */
data class UserFacingMessage(
    val text: String,
    val canRetry: Boolean,
    val errorType: UiErrorType
)

object UiErrorMapper {

    /**
     * 把任意 [Throwable] 归类为 [UiErrorType]。
     *
     * 分类顺序有优先级：超时先于网络（SocketTimeoutException 同时是 IOException），
     * 子类判定必须先于父类（FileNotFoundException 是 IOException 子类，反过来 FILE_IO
     * 永远不可达），解析/书源/文件再按消息特征细分，最后兜底 UNKNOWN。
     */
    fun classify(t: Throwable): UiErrorType {
        val message = t.message.orEmpty()
        return when {
            t is TimeoutCancellationException -> UiErrorType.TIMEOUT
            isTimeoutMessage(t) -> UiErrorType.TIMEOUT
            // FileNotFoundException 是 IOException 子类，必须排在 IOException 之前，
            // 否则文件读取失败会被误报成「网络不可用」
            t is FileNotFoundException -> UiErrorType.FILE_IO
            t is IOException -> UiErrorType.NETWORK
            t is NoStackTraceException && message.startsWith("不是书源") -> UiErrorType.SOURCE_INVALID
            t is NoStackTraceException && message.startsWith("不是订阅源") -> UiErrorType.SOURCE_INVALID
            message.contains("ERROR:") -> UiErrorType.SOURCE_INVALID
            isParseFailure(t) -> UiErrorType.PARSE_FORMAT
            isFileUnsupported(t) -> UiErrorType.FILE_UNSUPPORTED
            else -> UiErrorType.UNKNOWN
        }
    }

    /** 返回该错误类型的通用默认文案。 */
    fun message(type: UiErrorType): UserFacingMessage = UserFacingMessage(
        text = when (type) {
            UiErrorType.NETWORK -> appCtx.getString(R.string.error_network)
            UiErrorType.TIMEOUT -> appCtx.getString(R.string.error_timeout)
            UiErrorType.SOURCE_INVALID -> appCtx.getString(R.string.error_source_invalid)
            UiErrorType.PARSE_FORMAT -> appCtx.getString(R.string.error_parse_format)
            UiErrorType.FILE_IO -> appCtx.getString(R.string.error_file_io)
            UiErrorType.FILE_UNSUPPORTED -> appCtx.getString(R.string.error_file_unsupported)
            UiErrorType.NO_DATA -> appCtx.getString(R.string.error_no_data)
            UiErrorType.UNKNOWN -> appCtx.getString(R.string.unknown_error)
        },
        canRetry = retryable(type),
        errorType = type
    )

    /** 分类并返回通用用户可见文案，任何路径都不含 [Throwable.localizedMessage]。 */
    fun messageFor(t: Throwable): UserFacingMessage = message(classify(t))

    /**
     * 该类型是否适合提供「重试」入口。
     * NO_DATA 由状态机决定其它动作；FILE_UNSUPPORTED / SOURCE_INVALID 是确定性失败，
     * 重试只会得到同样结果，不给重试入口。
     */
    fun retryable(type: UiErrorType): Boolean = when (type) {
        UiErrorType.NO_DATA,
        UiErrorType.FILE_UNSUPPORTED,
        UiErrorType.SOURCE_INVALID -> false
        else -> true
    }

    /** 主动超时封装：`TimeoutCancellationException` 或常见超时类名/文案。 */
    private fun isTimeoutMessage(t: Throwable): Boolean {
        val name = t.javaClass.name
        val message = t.message.orEmpty()
        return name.contains("Timeout") ||
            name.contains("timedOut") ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true)
    }

    /** 解析失败判定：JsonParse/JsonPath/GSON 反序列化或格式校验失败。 */
    private fun isParseFailure(t: Throwable): Boolean {
        val name = t.javaClass.name
        val message = t.message.orEmpty()
        return name.contains("JsonParse") ||
            name.contains("JsonPath") ||
            name.contains("JsonSyntax") ||
            name.contains("MalformedJson") ||
            name.contains("JsonIOException") ||
            message.contains("Expected BEGIN_") ||
            message.contains("parse", ignoreCase = true) && message.contains("json", ignoreCase = true)
    }

    /** 文件类型不支持判定：导入/打开无法识别的格式。 */
    private fun isFileUnsupported(t: Throwable): Boolean {
        val message = t.message.orEmpty()
        return message.contains("unsupported", ignoreCase = true) ||
            message.contains("not supported", ignoreCase = true) ||
            message.contains("不是高亮规则文件") ||
            message.contains("格式不对")
    }
}
