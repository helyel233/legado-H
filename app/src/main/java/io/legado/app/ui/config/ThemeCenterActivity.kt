package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeCenterBinding
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeAssetStore
import io.legado.app.help.config.ThemePackageStore
import io.legado.app.help.config.ThemeConfig
import io.legado.app.theme.model.FontRole
import io.legado.app.theme.pack.ThemeFonts
import io.legado.app.theme.pack.ThemeImages
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.theme.pack.resolvePalette
import io.legado.app.uikit.components.AppButton
import io.legado.app.uikit.components.AppButtonSize
import io.legado.app.uikit.components.AppButtonStyle
import io.legado.app.uikit.theme.Applicator
import io.legado.app.uikit.theme.AppTheme
import io.legado.app.uikit.theme.BuiltinThemes
import io.legado.app.uikit.token.AppSpacing
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * A2-4b 主题包详情：编辑指定用户包（pkgId）；官方包首次编辑自动克隆。
 * 由管理中心携 pkgId 打开；无 pkgId 时编辑当前应用中的包。
 */
class ThemeCenterActivity : BaseActivity<ActivityThemeCenterBinding>() {

    override val binding by viewBinding(ActivityThemeCenterBinding::inflate)

    private var pendingFontRole: FontRole? = null
    private var pendingImageSlot: String? = null

    private val fontPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val role = pendingFontRole
        if (uri != null && role != null) onFontPicked(uri, role)
        pendingFontRole = null
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot = pendingImageSlot
        if (uri != null && slot != null) onImagePicked(uri, slot)
        pendingImageSlot = null
    }

    private val targetId: String? by lazy { intent?.getStringExtra(EXTRA_PKG_ID) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val spec = targetId?.let { id -> ThemePackageStore.resolveSpec(ThemePackageStore.load(this) ?: ThemePackageStore.Runtime(), id) }
        if (spec != null && Applicator.activeTheme.id != spec.id) {
            Applicator.applyTheme(spec)
        }
        binding.composeContent.setContent {
            AppTheme(Applicator.rememberAppColorScheme()) {
                ThemeCenterScreen()
            }
        }
    }

    fun launchFontPicker(role: FontRole) {
        pendingFontRole = role
        fontPicker.launch("*/*")
    }

    fun launchImagePicker(slot: String) {
        pendingImageSlot = slot
        imagePicker.launch("image/*")
    }

    private fun onFontPicked(uri: Uri, role: FontRole) {
        val path = ThemeAssetStore.copyToApp(this, uri, "fonts") ?: return
        updateTarget { spec ->
            val fonts = (spec.fonts ?: ThemeFonts()).let {
                if (role == FontRole.UI) it.copy(ui = path) else it.copy(title = path)
            }
            spec.copy(fonts = fonts)
        }
    }

    private fun onImagePicked(uri: Uri, slot: String) {
        val path = ThemeAssetStore.copyToApp(this, uri, "images") ?: return
        updateTarget { spec -> spec.copy(images = (spec.images ?: ThemeImages()).withSlot(slot, path)) }
    }

    /** 官方包编辑 → 自动克隆为用户包（另存模型）。 */
    private fun editTarget(): ThemePackageSpec {
        val cur = Applicator.activeTheme
        if (BuiltinThemes.byId(cur.id) == null && cur.basedOn != null) return cur
        val clone = cur.copy(
            id = ThemePackageStore.newUserId(),
            name = cur.name + " 副本",
            basedOn = cur.id,
        )
        ThemePackageStore.upsertUserSpec(this, clone)
        Applicator.applyTheme(clone)
        return clone
    }

    fun updateTarget(transform: (ThemePackageSpec) -> ThemePackageSpec) {
        val spec = transform(editTarget())
        Applicator.applyTheme(spec)
        ThemePackageStore.upsertUserSpec(this, spec)
        ThemePackageStore.persistActive(this)
    }

    companion object {
        const val EXTRA_PKG_ID = "pkgId"
    }
}

private val SEED_PRESETS = listOf(
    "#5B6ABF", "#8D6E63", "#3A4258", "#A08A5B", "#2E5266",
    "#7A9B76", "#9B4B4B", "#6C5B7B", "#556B2F", "#808080",
)

@Composable
private fun ThemeCenterScreen() {
    val scheme = Applicator.rememberAppColorScheme()
    val rev = Applicator.revision
    val overrides = remember(rev) { Applicator.activeTheme.override ?: emptyMap() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding().padding(horizontal = AppSpacing.s16, vertical = AppSpacing.s12),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s12),
    ) {
        item {
            Text(
                stringResource(R.string.theme_center_title),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onBackground,
            )
            Text(
                Applicator.activeTheme.name + " · " + if (Applicator.activeTheme.basedOn != null)
                    stringResource(R.string.theme_center_based_prefix, Applicator.activeTheme.basedOn ?: "")
                else stringResource(R.string.theme_center_official),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.muted,
            )
        }
        item { CenterCard(R.string.theme_center_seed) { SeedRow() } }
        item { CenterCard(R.string.theme_center_strategy) { StrategyRow() } }
        item { CenterCard(R.string.theme_center_tweaks) { TweaksSection(overrides) } }
        item { CenterCard(R.string.theme_center_fonts) { FontSection() } }
        item { CenterCard(R.string.theme_center_images) { ImageSection() } }
        item {
            val context = LocalContext.current
            val savedText = stringResource(R.string.theme_center_saved)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    ThemePackageStore.persistActive(context)
                    android.widget.Toast.makeText(context, savedText, android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.theme_center_save)) }
            }
        }
        item { CenterCard(R.string.theme_center_night) {
            val context = LocalContext.current
            val modes = listOf(
                "0" to R.string.theme_night_follow,
                "1" to R.string.theme_night_light,
                "2" to R.string.theme_night_dark,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                modes.forEach { (value, labelRes) ->
                    val selected = AppConfig.themeMode == value
                    Text(
                        stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) scheme.primary else scheme.muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, if (selected) scheme.primary else scheme.outline, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable {
                                appCtx.putPrefString(PreferKey.themeMode, value)
                                ThemeConfig.applyDayNight(context, AppConfig.isNightTheme)
                            },
                    )
                }
            }
        } }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun CenterCard(titleRes: Int, content: @Composable () -> Unit) {
    val scheme = Applicator.rememberAppColorScheme()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.shapes.medium.topStart),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(modifier = Modifier.padding(AppSpacing.s16)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.height(AppSpacing.s8))
            content()
        }
    }
}

@Composable
internal fun ThemeSwatch(colorInt: Int) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(Color(colorInt), CircleShape)
            .border(1.dp, Color.Black.copy(alpha = 0.15f), CircleShape),
    )
}

@Composable
private fun SeedRow() {
    val activity = LocalContext.current as? ThemeCenterActivity
    val scheme = Applicator.rememberAppColorScheme()
    Text(
        stringResource(R.string.theme_center_seed),
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SEED_PRESETS.forEach { hex ->
            val selected = Applicator.activeTheme.seed.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) scheme.primary else scheme.outline,
                        shape = CircleShape,
                    )
                    .padding(3.dp)
                    .background(Color(ThemePackageSpec.parseColor(hex) ?: 0), CircleShape)
                    .clickable { activity?.updateTarget { it.copy(seed = hex) } },
            )
        }
    }
}

@Composable
private fun StrategyRow() {
    val activity = LocalContext.current as? ThemeCenterActivity
    val scheme = Applicator.rememberAppColorScheme()
    val strategies = listOf("tonal", "amoled", "muted", "mono")
    Text(
        stringResource(R.string.theme_center_strategy),
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        strategies.forEach { value ->
            val selected = Applicator.activeTheme.darkStrategy == value
            AppButton(
                text = value,
                onClick = { activity?.updateTarget { it.copy(darkStrategy = value) } },
                style = if (selected) AppButtonStyle.FILLED else AppButtonStyle.TONAL,
                size = AppButtonSize.COMPACT,
            )
        }
    }
}
