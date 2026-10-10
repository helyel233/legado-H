package io.legado.app.ui.config

import android.os.Bundle
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeCenterBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageStore
import io.legado.app.theme.palette.PaletteRole
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

/**
 * A1-5 主题中心（新引擎最小可用版）：
 * 内置 6 主题 / 自定义种子色与策略 / 夜间模式 / 手调管理。
 * 每次变更即时生效并持久化（ThemePackageStore）。
 */
class ThemeCenterActivity : BaseActivity<ActivityThemeCenterBinding>() {

    override val binding by viewBinding(ActivityThemeCenterBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeContent.setContent {
            AppTheme(Applicator.rememberAppColorScheme()) {
                ThemeCenterScreen()
            }
        }
    }
}

private val SEED_PRESETS = listOf(
    "#5B6ABF", "#8D6E63", "#3A4258", "#A08A5B", "#2E5266",
    "#7A9B76", "#9B4B4B", "#6C5B7B", "#556B2F", "#808080",
)

@Composable
private fun ThemeCenterScreen() {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    val rev = Applicator.revision
    val tweaks = remember(rev) { Applicator.tweaksSnapshot() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(horizontal = AppSpacing.s16, vertical = AppSpacing.s12),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s12),
    ) {
        item {
            Text(
                stringResource(R.string.theme_center_title),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onBackground,
            )
            Text(
                stringResource(R.string.theme_center_desc),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.muted,
            )
        }
        item { SectionCard(R.string.theme_center_builtin) {
            BuiltinThemes.all.forEach { spec ->
                val selected = Applicator.activeTheme.id == spec.id
                val palette = remember(spec.id, scheme.isDark, rev) { spec.resolvePalette(scheme.isDark) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Applicator.applyTheme(spec)
                            ThemePackageStore.persistCurrent(context)
                        }
                        .padding(vertical = AppSpacing.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) scheme.primary else Color.Transparent,
                                shape = CircleShape,
                            )
                            .padding(3.dp)
                            .background(Color(palette.getValue(PaletteRole.PRIMARY)), CircleShape),
                    )
                    Spacer(Modifier.width(AppSpacing.s12))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(spec.name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        Text(
                            spec.id + if (spec.isWallpaperSeed) " · wallpaper" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.muted,
                        )
                    }
                    Row {
                        ThemeSwatch(palette.getValue(PaletteRole.SURFACE))
                        ThemeSwatch(palette.getValue(PaletteRole.ACCENT))
                    }
                }
            }
        } }
        item { SectionCard(R.string.theme_center_custom) {
            SeedRow()
            Spacer(Modifier.height(AppSpacing.s8))
            StrategyRow()
        } }
        item { SectionCard(R.string.theme_center_night) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.theme_center_night_label),
                    modifier = Modifier.weight(1f),
                    color = scheme.onSurface,
                )
                Switch(
                    checked = AppConfig.isNightTheme,
                    onCheckedChange = { ThemeConfig.applyDayNight(context, it) },
                )
            }
        } }
        item { SectionCard(R.string.theme_center_tweaks) { TweaksSection(tweaks) } }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
internal fun ThemeCenterSectionCard(titleRes: Int, content: @Composable () -> Unit) {
    SectionCard(titleRes, content)
}

@Composable
private fun SectionCard(titleRes: Int, content: @Composable () -> Unit) {
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
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    var custom by remember { mutableStateOf(ThemePackageStore.load(context)?.customSpec) }
    val currentSeed = custom?.seed
        ?: (if (!Applicator.activeTheme.isWallpaperSeed) Applicator.activeTheme.seed else "#5B6ABF")
    Text(
        stringResource(R.string.theme_center_seed),
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SEED_PRESETS.forEach { hex ->
            val selected = currentSeed.equals(hex, ignoreCase = true)
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
                    .clickable {
                        val spec = (custom ?: customBase()).copy(seed = hex)
                        custom = spec
                        Applicator.applyTheme(spec)
                        ThemePackageStore.persistCurrent(context)
                    },
            )
        }
    }
}

@Composable
private fun StrategyRow() {
    val context = LocalContext.current
    val scheme = Applicator.rememberAppColorScheme()
    var custom by remember { mutableStateOf(ThemePackageStore.load(context)?.customSpec) }
    val strategies = listOf("tonal", "amoled", "muted", "mono")
    Text(
        stringResource(R.string.theme_center_strategy),
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        strategies.forEach { value ->
            val selected = (custom?.darkStrategy ?: Applicator.activeTheme.darkStrategy) == value
            AppButton(
                text = value,
                onClick = {
                    val spec = (custom ?: customBase()).copy(darkStrategy = value)
                    custom = spec
                    Applicator.applyTheme(spec)
                    ThemePackageStore.persistCurrent(context)
                },
                style = if (selected) AppButtonStyle.FILLED else AppButtonStyle.TONAL,
                size = AppButtonSize.COMPACT,
            )
        }
    }
}

/** Copy the current look into a editable custom package. */
internal fun customBase(): ThemePackageSpec =
    Applicator.activeTheme.let {
        it.copy(
            id = ThemePackageStore.CUSTOM_ID,
            name = "我的主题",
            author = "自定义",
            seed = if (it.isWallpaperSeed) "#5B6ABF" else it.seed,
        )
    }
