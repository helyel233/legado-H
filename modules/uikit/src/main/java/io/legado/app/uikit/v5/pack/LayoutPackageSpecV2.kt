package io.legado.app.uikit.v5.pack

import com.google.gson.Gson
import io.legado.app.uikit.v5.token.DensitySpec
import io.legado.app.uikit.v5.token.GlassSpec
import io.legado.app.uikit.v5.token.IconSpec
import io.legado.app.uikit.v5.token.IconStyle
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.MotionLevel
import io.legado.app.uikit.v5.token.MotionSpec
import io.legado.app.uikit.v5.token.NavPosition
import io.legado.app.uikit.v5.token.NavVisibility
import io.legado.app.uikit.v5.token.ShapeFamily
import io.legado.app.uikit.v5.token.TitleWeight
import io.legado.app.uikit.v5.token.TypeSpec
import io.legado.app.uikit.v5.token.VisualSpec

/**
 * P3-1 界面包 v2（docs/ui-v5-skeleton-first.md 4.2）。
 *
 * 只装结构参数：导航 / 形状族 / 玻璃五件套 / 密度 / 动效 / 图标 / 排版 / 栏材质。
 * **铁律：没有任何按页面分的字段。**
 *
 * 容错解析：未知字段忽略、缺失字段回默认、半成品包能加载；
 * 仅"格式过新"与"id/name 缺失"被拒。
 *
 * 与 v1（`layout.LayoutPackageSpec`，旧壳仍在用）的关系：v1 随旧壳在 P4-5 退役，
 * 新体系只认 v2。v1 的 LayoutEngine 状态由 app 桥接层同步，不在本类职责内。
 */
data class LayoutPackageSpecV2(
    val formatVersion: Int = FORMAT_VERSION,
    val id: String = "",
    val name: String = "",
    val author: String = "",
    /** 克隆来源（另存模型），官方包为 null */
    val basedOn: String? = null,
    // ---- 导航 ----
    val navPosition: String = NavPosition.FLOAT.id,
    val navVisibility: String = NavVisibility.ALWAYS.id,
    // ---- 形状 ----
    val shapeFamily: String = "soft",
    val shapeScale: Float = 1f,
    // ---- 玻璃五件套 ----
    val glassEnabled: Boolean = false,
    val glassAlpha: Float = GlassSpec.DEFAULT_ALPHA,
    val glassBlur: Float = GlassSpec.DEFAULT_BLUR,
    val glassStroke: Boolean = true,
    val glassNoise: Float = GlassSpec.DEFAULT_NOISE,
    val glassVibrancy: Float = GlassSpec.DEFAULT_VIBRANCY,
    // ---- 密度 ----
    val densityFactor: Float = 1f,
    // ---- 动效 ----
    val motionLevel: String = MotionLevel.STANDARD.id,
    val motionReduce: Boolean = false,
    // ---- 图标 ----
    val iconStyle: String = IconStyle.OUTLINED.id,
    val iconStroke: Float = IconSpec.DEFAULT_STROKE,
    val iconSelectedMorph: Boolean = true,
    // ---- 排版 ----
    val titleWeight: String = TitleWeight.BOLD.id,
    val cjkSpacing: Boolean = true,
    // ---- 栏材质 ----
    val topBarLevel: String = MaterialLevel.RAISED.id,
    val bottomBarLevel: String = MaterialLevel.FLOATING.id,
    val topBarCollapse: Boolean = true,
) {

    fun isValid(): Boolean =
        formatVersion == FORMAT_VERSION &&
            id.isNotBlank() && name.isNotBlank() &&
            ShapeFamily.byId(shapeFamily).id == shapeFamily &&
            NavPosition.byId(navPosition).id == navPosition &&
            MaterialLevel.byId(topBarLevel).id.equals(topBarLevel, true) &&
            MaterialLevel.byId(bottomBarLevel).id.equals(bottomBarLevel, true)

    /** 解析为宿主消费的视觉规格（全部档位/区间在 token 层完成 clamp）。 */
    fun toVisualSpec(): VisualSpec = VisualSpec(
        navPosition = NavPosition.byId(navPosition),
        navVisibility = NavVisibility.byId(navVisibility),
        shapeFamily = ShapeFamily.byId(shapeFamily),
        shapeScale = shapeScale,
        glass = GlassSpec(
            enabled = glassEnabled,
            alpha = glassAlpha,
            blurDp = glassBlur,
            stroke = glassStroke,
            noise = glassNoise,
            vibrancy = glassVibrancy,
        ),
        topBarLevel = MaterialLevel.byId(topBarLevel),
        bottomBarLevel = MaterialLevel.byId(bottomBarLevel),
        topBarCollapse = topBarCollapse,
        density = DensitySpec(densityFactor),
        motion = MotionSpec(MotionLevel.byId(motionLevel), motionReduce),
        icon = IconSpec(IconStyle.byId(iconStyle), iconStroke, iconSelectedMorph),
        type = TypeSpec(TitleWeight.byId(titleWeight), cjkSpacing),
    )

    /** 包内编辑：改任意字段返回新包（编辑官方包时先克隆，见 store 层）。 */
    fun update(block: (LayoutPackageSpecV2) -> LayoutPackageSpecV2): LayoutPackageSpecV2 =
        block(this)

    companion object {
        const val FORMAT_VERSION = 2

        private val gson = Gson()

        /** 从当前视觉规格捕获一份（"基于当前外观另存为包"）。 */
        fun fromVisualSpec(id: String, name: String, author: String, spec: VisualSpec, basedOn: String? = null): LayoutPackageSpecV2 =
            LayoutPackageSpecV2(
                id = id, name = name, author = author, basedOn = basedOn,
                navPosition = spec.navPosition.id,
                navVisibility = spec.navVisibility.id,
                shapeFamily = spec.shapeFamily.id,
                shapeScale = spec.shapeScale,
                glassEnabled = spec.glass.enabled,
                glassAlpha = spec.glass.alpha,
                glassBlur = spec.glass.blurDp,
                glassStroke = spec.glass.stroke,
                glassNoise = spec.glass.noise,
                glassVibrancy = spec.glass.vibrancy,
                topBarLevel = spec.topBarLevel.id,
                bottomBarLevel = spec.bottomBarLevel.id,
                topBarCollapse = spec.topBarCollapse,
                densityFactor = spec.density.factor,
                motionLevel = spec.motion.level.id,
                motionReduce = spec.motion.reduce,
                iconStyle = spec.icon.style.id,
                iconStroke = spec.icon.strokeDp,
                iconSelectedMorph = spec.icon.selectedMorph,
                titleWeight = spec.type.titleWeight.id,
                cjkSpacing = spec.type.cjkSpacing,
            )

        /** 容错解析；仅过新格式与空 id/name 被拒。 */
        fun parse(json: String): LayoutParseResultV2 = try {
            val spec = gson.fromJson(json, LayoutPackageSpecV2::class.java)
            when {
                spec == null -> LayoutParseResultV2.Error("layout.json 为空或不是 JSON 对象")
                spec.formatVersion > FORMAT_VERSION ->
                    LayoutParseResultV2.Error("界面包格式过新（v${spec.formatVersion}），请升级 App 后再导入")
                spec.id.isBlank() || spec.name.isBlank() ->
                    LayoutParseResultV2.Error("界面包缺少 id 或 name")
                else -> LayoutParseResultV2.Ok(spec)
            }
        } catch (e: Exception) {
            LayoutParseResultV2.Error("layout.json 解析失败：${e.message}")
        }
    }
}

sealed class LayoutParseResultV2 {
    data class Ok(val spec: LayoutPackageSpecV2) : LayoutParseResultV2()
    data class Error(val message: String) : LayoutParseResultV2()
}
