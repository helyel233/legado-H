#!/usr/bin/env python3
"""Batch i18n helper for legado-H.

Workflow:
  1) scan  : collect Chinese string literals from given Kotlin files into a JSON batch.
             Each literal is annotated with its source line and a risk flag (comparison,
             default value, map key...) so that a human decides what is user-visible.
  2) apply : for every entry with a non-empty "en" value, generate a semantic resource
             key, write values/ + values-zh/ XML, and replace the literal in Kotlin with
             appCtx.getString(R.string.key[, args...]). String interpolations are converted
             to positional %N$s placeholders.

Safety rules:
  - Only literals inside the explicitly listed files are touched.
  - Risky occurrences are skipped unless the batch entry sets "force": true.
  - Literals without a translation are left untouched (no pseudo-i18n).
  - Re-running apply is idempotent: already-replaced literals no longer exist.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.sax.saxutils as sax
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = "app/src/main/java"
VALUES = "app/src/main/res/values"
VALUES_ZH = "app/src/main/res/values-zh"

STR_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
HAN = re.compile(r"[\u4e00-\u9fff]")
INTERP_RE = re.compile(r"\$\{([^{}]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")

# A literal used as a value (comparison / persisted default / protocol key) must not
# become a localized string, otherwise behaviour changes with the locale.
RISK_PATTERNS = [
    r"(==|!=)\s*$", r"\bwhen\s*\(", r"\.equals\s*\(",
    r"\b(listOf|setOf|mapOf|arrayOf|mutableListOf|mutableMapOf|sequenceOf)\s*\(\s*$",
    r"\b(const\s+)?val\s+\w+\s*(:\s*String\s*)?=\s*$",
    r"\b(put|putString|get|getString|contains|indexOf|startsWith|endsWith|matches)\s*\(\s*$",
    r"\b(require|check|error|throw|IllegalArgumentException|IllegalStateException|Exception)\b",
    r"\bAppLog|\bLog\.|\bDebug\.", r"\bputString\s*\(",
    # Data semantics: annotation args (Room defaultValue), entity property defaults,
    # and Chinese keyword sets used for text matching must keep their literal value.
    r"@\w+\([^)]*=\s*$",
    r"\bvar\s+\w+\s*(?::\s*[\w?<>., ]+\s*)?=\s*$",
    r"\bin\s+(?:setOf|listOf|arrayOf|mutableSetOf|mutableListOf)\s*\([^)]*$",
]
RISK_RE = re.compile("|".join(RISK_PATTERNS))

# Files whose Chinese literals are data, not UI text. Never touch them in any
# command (scan / apply / reuse). Translating these changes behaviour with the
# locale instead of changing what the user sees.
EXCLUDE_BASENAMES = {
    "ChineseUtils.kt",                  # simplified/traditional conversion word list
    "ShibbolethCodec.kt",               # share-code alphabet (Chinese chars are protocol values)
    "JsEncodeUtils.kt",                 # JS helper documentation
    "Debug.kt",                         # debug state strings ("校验成功" is compared, not shown)
    "SyntheticReviewEntry.kt",          # synthetic data payload markers
    "SpeechModels.kt",                  # TTS speaker keyword matching table
    "ReadAloudPreprocessRuleConfig.kt", # dialogue-attribution keyword rules
    "AppConfig.kt",                     # onomatopoeia / inner-thought keyword config data
    "BookCharacter.kt",                 # gender keywords + genderLabel/roleLabel data values
    "ContentHelp.kt",                   # Chinese-specific regexes and separators
    "TimeUtils.kt",                     # Chinese numeral / calendar parsing data
    "StringUtils.kt",                   # CJK character classification data
}

# Files dominated by LLM protocol text (tool schemas, system prompts, planner
# stages) or diagnostic fragments. They are not excluded from explicit batches
# (some do contain real UI status strings), but `classify` counts their literals
# separately so the "user-visible candidates" number is meaningful.
LLM_TEXT_BASENAMES = {
    "AiBookCharacterTool.kt", "AiBookshelfTool.kt", "AiBookSourceTool.kt",
    "AiReadingNetworkTool.kt", "AiLibraryTool.kt", "AiWorldBookTool.kt",
    "AiAgentPlanner.kt", "AiAgentValidator.kt", "AiImagePromptRewriter.kt",
    "AiReadAloudBgmService.kt", "AiToolRegistry.kt", "AiChatService.kt",
    "AiReadAloudRoleService.kt", "AiImageGalleryManager.kt",
}
DIAGNOSTIC_BASENAMES = {
    "ReviewSnapshotManager.kt", "ReviewSnapshotCapture.kt",
    "ReviewSnapshotResourceStore.kt", "ShareNoteImageRenderer.kt",
    "EpubReaderTemplate.kt", "EpubReaderTemplatePackages.kt",
}
# NOTE: EpubFile.kt / PdfFile.kt / HighlightPackageParser.kt are deliberately
# NOT here: they mix diagnostic logs with user-visible generated chapter titles
# and import error messages, which are handled by an explicit batch instead.
LOG_RE = re.compile(
    r"AppLog|LogUtils|\bLog\.|LogTag|\bDebug\.|\bthrow\b|\brequire\(|\bcheck\("
    r"|\berror\(|Exception\(|stackTrace|println")
# A line building a static collection with more than one literal is a data table
# (e.g. searchKeys = listOf("漫画", "漫画浏览", ...)); replacing only some of its
# members makes the index language-dependent and inconsistent.
STATIC_TABLE_RE = re.compile(
    r"\b(?:listOf|setOf|mapOf|arrayOf|mutableListOf|mutableSetOf|mutableMapOf|sequenceOf)\s*\(")


# ---------------------------------------------------------------------------
# R57 收尾：对最后 192 处「真实可见候选」逐条读完上下文后的判定结果。
#
# 本表只记录被判为 **B 类（确实不该翻）** 的条目，命中键是
# (basename, 字面量)，因此：
#   * 绝不把某个文件整体塞进 EXCLUDE_BASENAMES —— 同一文件里若还有真 UI
#     文案（如 EpubFile / DiagnosticExporter 混有日志、CSS 选择器、比较值与
#     诊断段落标题；ContentProcessor / BookChapter / HighlightImport 则混有
#     toast 与导入提示），仍按各自真实性质分别处理；
#   * 每条都写明了豁免类别与理由，统计口径可重复、可审计；
#   * 类别只取既有七个豁免桶之一，保证八类加总恒等于总量。
# A 类（真用户可见）已由 b10.json / strings_i18n_final.xml 双语资源化，
# 对应 Kotlin 字面量已移除，故不再出现在本表中。
# ---------------------------------------------------------------------------
FINAL_EXEMPT_RULES: tuple[tuple[str, str, str, tuple[str, ...]], ...] = (
    # AiReadAloudBgmTool.kt — AI 工具 JSON schema 的 description 字段
    ('LLM 协议文本', 'AiReadAloudBgmTool.kt', 'AI 工具 JSON schema 的 description 字段', (
        '读取朗读智能音频资源库，返回可用配乐和音效的 trackId、分组、标签、类型和时长，不返回本地文件路径。',
    )),
    # AiSettingsTool.kt — AI 工具 JSON schema 的 description 字段，发给大模型的协议文本
    ('LLM 协议文本', 'AiSettingsTool.kt', 'AI 工具 JSON schema 的 description 字段，发给大模型的协议文本', (
        '修改单个白名单设置项。',
        '批量修改白名单设置项。',
        '按 key 类型传值',
        '读取应用可公开配置项。支持按 key 或分类读取。',
    )),
    # AiTavilyTool.kt — AI 工具 JSON schema description 与 errorJson 协议返回，翻了会让模型行为随语言漂移
    ('LLM 协议文本', 'AiTavilyTool.kt', 'AI 工具 JSON schema description 与 errorJson 协议返回，翻了会让模型行为随语言漂移', (
        'query 不能为空',
        '使用 Tavily 联网搜索实时网页信息，返回答案摘要、来源链接和内容片段。适合新闻、实时事件、最新产品信息和网页检索。',
        '搜索主题，默认使用设置中的值。',
        '搜索深度，默认使用设置中的值。',
        '是否返回 Tavily 的简短总结答案。默认 true。',
        '最多返回几条结果，默认使用设置中的值。',
        '要搜索的问题或关键词。',
    )),
    # AppPattern.kt — 章节号识别正则（中文正则不改语义）
    ('技术字符串', 'AppPattern.kt', '章节号识别正则（中文正则不改语义）', (
        '(第)(.+?)(章)',
    )),
    # BookSourceActivity.kt — 校验终态消息正则；另见下方 OVERRIDES 的搜索查询值
    ('技术字符串', 'BookSourceActivity.kt', '校验终态消息正则；另见下方 OVERRIDES 的搜索查询值', (
        '成功|失败',
    )),
    # BookSourceScreen.kt — 校验终态消息正则
    ('技术字符串', 'BookSourceScreen.kt', '校验终态消息正则', (
        '成功|失败',
    )),
    # FileUtils.kt — SimpleDateFormat 日期格式模式默认值，中文是格式字面量
    ('技术字符串', 'FileUtils.kt', 'SimpleDateFormat 日期格式模式默认值，中文是格式字面量', (
        'yyyy年MM月dd日HH:mm',
    )),
    # LibraryCloudModels.kt — 章节号识别正则与中文数字字符集（中文正则/关键词集合不改语义）
    ('技术字符串', 'LibraryCloudModels.kt', '章节号识别正则与中文数字字符集（中文正则/关键词集合不改语义）', (
        '(?:第)?([0-9]{1,7})(?:章|回|节|卷|集|部|篇|话)',
        '(chapter|chap|volume|vol|正文|章节|第|章|回|节|卷|集|部|篇)',
        '[零〇一二两三四五六七八九十百千万]+',
    )),
    # LocalBook.kt — 本地书名/作者解析正则（中文正则不改语义）
    ('技术字符串', 'LocalBook.kt', '本地书名/作者解析正则（中文正则不改语义）', (
        '(.*?)《([^《》]+)》.*?作者：(.*)',
        '(^)(.+) 作者：(.+)$',
    )),
    # ReadAloudRolePreprocessor.kt — 心想/对白归属中文关键词集合的默认值
    ('技术字符串', 'ReadAloudRolePreprocessor.kt', '心想/对白归属中文关键词集合的默认值', (
        '心道|暗道|想道|心想',
    )),
    # ReplaceRuleDao.kt — Room @Query SQL（含中文分组比较值 未分组），改了会破坏查询语义
    ('技术字符串', 'ReplaceRuleDao.kt', 'Room @Query SQL（含中文分组比较值 未分组），改了会破坏查询语义', (
        "select * from replace_rules where `group` is null or trim(`group`) = '' or trim(`group`) like '%未分组%'",
    )),
    # ReviewSnapshotMerger.kt — 回复开关正则与 check 异常消息；逐条细分见 OVERRIDES
    ('技术字符串', 'ReviewSnapshotMerger.kt', '回复开关正则与 check 异常消息；逐条细分见 OVERRIDES', (
        '展开.{0,8}回复|收起.{0,6}回复|更多回复|查看回复|全部回复',
    )),
    # RssSourceActivity.kt — 书源校验终态消息正则（比较值，宽泛匹配会把中间消息误判为已完成）
    ('技术字符串', 'RssSourceActivity.kt', '书源校验终态消息正则（比较值，宽泛匹配会把中间消息误判为已完成）', (
        '校验成功|校验失败',
    )),
    # RssSourceDao.kt — Room @Query SQL（含中文分组比较值 未分组）
    ('技术字符串', 'RssSourceDao.kt', 'Room @Query SQL（含中文分组比较值 未分组）', (
        "select * from rssSources where sourceGroup is null or sourceGroup = '' or sourceGroup like '%未分组%'",
    )),
    # RssSourceScreen.kt — 同上，终态消息正则
    ('技术字符串', 'RssSourceScreen.kt', '同上，终态消息正则', (
        '校验成功|校验失败',
    )),
    # TextChapter.kt — 朗读文本清理用的正则字符类
    ('技术字符串', 'TextChapter.kt', '朗读文本清理用的正则字符类', (
        '[袮꧁]',
    )),
    # ZhLayout.kt — 中文字符宽度测量样本字，改了会改变排版度量
    ('技术字符串', 'ZhLayout.kt', '中文字符宽度测量样本字，改了会改变排版度量', (
        '我',
    )),
    # BookCharacterProfileMeta.kt — 与 ageLineRegex(年纪|年龄) 绑定的结构化属性前缀；本地化会让 ageFromAttributes 解析失效
    ('数据语义', 'BookCharacterProfileMeta.kt', '与 ageLineRegex(年纪|年龄) 绑定的结构化属性前缀；本地化会让 ageFromAttributes 解析失效', (
        '年纪：$cleanAge',
        '年纪：$cleanAge\\n$body',
    )),
    # BookExtensions.kt — 书籍导出文件名模板（含 作者：），本地化会改变生成的文件名
    ('数据语义', 'BookExtensions.kt', '书籍导出文件名模板（含 作者：），本地化会改变生成的文件名', (
        '$name 作者：${getRealAuthor()}.$suffix',
    )),
    # BookSource.kt — 书源分组名比较值（失效 / 校验超时），改了会让筛选失效
    ('数据语义', 'BookSource.kt', '书源分组名比较值（失效 / 校验超时），改了会让筛选失效', (
        '失效',
    )),
    # BookSourceActivity.kt — 校验终态消息正则；另见下方 OVERRIDES 的搜索查询值
    ('数据语义', 'BookSourceActivity.kt', '校验终态消息正则；另见下方 OVERRIDES 的搜索查询值', (
        '失效',
    )),
    # BookSourceDebugWebSocket.kt — WebSocket NormalClosure 的 close reason 协议值，禁止改语义
    ('数据语义', 'BookSourceDebugWebSocket.kt', 'WebSocket NormalClosure 的 close reason 协议值，禁止改语义', (
        '调试结束',
    )),
    # BubblePackageManager.kt — 写入持久化 config.name 的默认包名，本地化会让存储数据随语言变化
    ('数据语义', 'BubblePackageManager.kt', '写入持久化 config.name 的默认包名，本地化会让存储数据随语言变化', (
        '段评气泡',
    )),
    # ContentSelectConfig.kt — Gson 持久化的 SearchEngine.name；object 顶层 val，本地化会让存储值随语言漂移且有类加载期解析资源风险
    ('数据语义', 'ContentSelectConfig.kt', 'Gson 持久化的 SearchEngine.name；object 顶层 val，本地化会让存储值随语言漂移且有类加载期解析资源风险', (
        '百度百科',
    )),
    # EpubFile.kt — EPUB 调试导出 README 说明、AppLog 日志、CSS 选择器与 EPUB 元数据比较值；逐条细分见 OVERRIDES
    ('数据语义', 'EpubFile.kt', 'EPUB 调试导出 README 说明、AppLog 日志、CSS 选择器与 EPUB 元数据比较值；逐条细分见 OVERRIDES', (
        '[title*=书籍信息], [title*=版权信息], [title*=简介]',
        '完本',
        '简介：',
        '首发',
    )),
    # ReadAloudBgmManageActivity.kt — 自动生成的导出 zip 文件名（含时间戳），本地化会改变文件名
    ('数据语义', 'ReadAloudBgmManageActivity.kt', '自动生成的导出 zip 文件名（含时间戳），本地化会改变文件名', (
        '${label}包_${System.currentTimeMillis()}.zip',
    )),
    # ReadRecordFragment.kt — joinToString 的拼接分隔符常量，非独立文案
    ('数据语义', 'ReadRecordFragment.kt', 'joinToString 的拼接分隔符常量，非独立文案', (
        ' 路 ',
    )),
    # RssSourceDebugWebSocket.kt — WebSocket NormalClosure 的 close reason 协议值，禁止改语义
    ('数据语义', 'RssSourceDebugWebSocket.kt', 'WebSocket NormalClosure 的 close reason 协议值，禁止改语义', (
        '调试结束',
    )),
    # AnalyzeRule.kt — JS 规则引擎 log() 输出
    ('日志/异常', 'AnalyzeRule.kt', 'JS 规则引擎 log() 输出', (
        '已跳过重复加载详情页，请优化代码',
        '重新获取book',
    )),
    # AnalyzeUrl.kt — JS 规则引擎 log() 输出
    ('日志/异常', 'AnalyzeUrl.kt', 'JS 规则引擎 log() 输出', (
        '链接参数 JSON 格式不规范，请改为规范格式',
    )),
    # AutoTask.kt — 自动化任务执行日志构建标签（buildLog / buildErrorLog 的 耗时/动作/返回/错误/堆栈）
    ('日志/异常', 'AutoTask.kt', '自动化任务执行日志构建标签（buildLog / buildErrorLog 的 耗时/动作/返回/错误/堆栈）', (
        '动作:',
        '堆栈:\\n',
        '耗时: ',
        '返回: ',
        '错误: ',
    )),
    # BookChapterList.kt — Debug.log 书源目录解析调试日志；◇/⇒ 是日志符号而非自动生成章节名，字符串是日志参数
    ('日志/异常', 'BookChapterList.kt', 'Debug.log 书源目录解析调试日志；◇/⇒ 是日志符号而非自动生成章节名，字符串是日志参数', (
        '⇒一级目录${index}未获取到url,使用标题替代',
        '⇒目录${index}未获取到url,使用baseUrl替代',
        '◇并发解析目录,总页数:${totalPages}',
        '◇目录有$failed 页获取失败，已跳过，剩余${chapterList.size}章',
        '◇目录第${nextUrlList.size}页获取失败，沿用已解析的${chapterList.size}章',
        '◇目录页获取失败(已重试${attempt - 1}次):$url ${e.localizedMessage}',
        '◇目录页解析失败:$urlStr ${e.localizedMessage}',
    )),
    # BookHelp.kt — AppLog.put 多行日志续行
    ('日志/异常', 'BookHelp.kt', 'AppLog.put 多行日志续行', (
        '已删除 EPUB 错误正文缓存: book=${book.name}, ',
    )),
    # BookInfo.kt — DebugLog.e 的日志消息
    ('日志/异常', 'BookInfo.kt', 'DebugLog.e 的日志消息', (
        '获取分类出错',
        '获取字数出错',
        '获取封面出错',
        '获取最新章节出错',
        '获取简介出错',
    )),
    # BottomWebViewDialog.kt — 评论快照注入的 AppLog.putDebug 多行日志续行与 checkNotNull 异常消息；中文片段是 Kotlin 模板插值残片，不是独立文案
    ('日志/异常', 'BottomWebViewDialog.kt', '评论快照注入的 AppLog.putDebug 多行日志续行与 checkNotNull 异常消息；中文片段是 Kotlin 模板插值残片，不是独立文案', (
        '[评论快照] 章评/书评补充注入：${result ?: ',
        '} 书=${context.bookName} 章=${context.chapterTitle}',
        '书=${context.bookName} 章=${context.chapterTitle}',
        '评论快照资源不存在: $url',
        '评论快照资源引用缺少书籍上下文: $url',
    )),
    # CacheManifestHelper.kt — checkNotNull 的异常消息
    ('日志/异常', 'CacheManifestHelper.kt', 'checkNotNull 的异常消息', (
        '无法读取正文缓存目录: ${dir.absolutePath}',
    )),
    # CronetLoader.kt — DebugLog.d 日志消息
    ('日志/异常', 'CronetLoader.kt', 'DebugLog.d 日志消息', (
        'So 库已存在',
    )),
    # DirectLinkUpload.kt — throw NoStackTraceException 的异常消息；中文片段是 Kotlin 模板插值残片
    ('日志/异常', 'DirectLinkUpload.kt', 'throw NoStackTraceException 的异常消息；中文片段是 Kotlin 模板插值残片', (
        '直链上传规则无效：${it.localizedMessage ?: ',
    )),
    # EpubFile.kt — EPUB 调试导出 README 说明、AppLog 日志、CSS 选择器与 EPUB 元数据比较值；逐条细分见 OVERRIDES
    ('日志/异常', 'EpubFile.kt', 'EPUB 调试导出 README 说明、AppLog 日志、CSS 选择器与 EPUB 元数据比较值；逐条细分见 OVERRIDES', (
        'EPUB spine 补页完成: spine=${spineContents.size}, ',
    )),
    # JsExtensions.kt — JS 桥 log() 输出（含 queryBase64TTF 弃用提示）
    ('日志/异常', 'JsExtensions.kt', 'JS 桥 log() 输出（含 queryBase64TTF 弃用提示）', (
        'getZipContent 未发现内容',
        'queryBase64TTF(String)方法已过时,并将在未来删除；请无脑使用queryTTF(Any)替代，新方法支持传入 url、本地文件、base64、ByteArray 自动判断&自动缓存，特殊情况需禁用缓存请传入第二可选参数false:Boolean',
        '首次下载 $urlStr >> $path',
    )),
    # MyFragment.kt — 仅 BuildConfig.DEBUG 生效的开发者断言消息，提示同步 SettingsSearchIndex；设置层级映射结构不动
    ('日志/异常', 'MyFragment.kt', '仅 BuildConfig.DEBUG 生效的开发者断言消息，提示同步 SettingsSearchIndex；设置层级映射结构不动', (
        '搜索子项挂载到不存在的一级行: $orphanOwners —— ',
        '改 buildSections 的行 key 时要同步 SettingsSearchIndex 的 ownerKey',
    )),
    # ReadBookViewModel.kt — AppLog.put 多行日志续行（跳过删除正式书架书）
    ('日志/异常', 'ReadBookViewModel.kt', 'AppLog.put 多行日志续行（跳过删除正式书架书）', (
        '跳过删除正式书架书: ${book.name}, bookUrl=${book.bookUrl}, ',
    )),
    # ReadPresetPreferences.kt — require 断言的异常消息
    ('日志/异常', 'ReadPresetPreferences.kt', 'require 断言的异常消息', (
        '阅读设置取值错误: $key',
        '阅读设置数值错误: $key',
    )),
    # ReviewCaptureHost.kt — AppLog.put 日志消息
    ('日志/异常', 'ReviewCaptureHost.kt', 'AppLog.put 日志消息', (
        '评论快照任务处理失败 ${task.key}\\n${it.localizedMessage}',
    )),
    # ReviewOutboxStore.kt — AppLog.putDebug 日志续行；中文片段是 Kotlin 模板插值残片
    ('日志/异常', 'ReviewOutboxStore.kt', 'AppLog.putDebug 日志续行；中文片段是 Kotlin 模板插值残片', (
        '} 待发=$pending',
        '来源=${if (item.buttonSrc != null) ',
    )),
    # ReviewParagraphTemplate.kt — require 的异常消息
    ('日志/异常', 'ReviewParagraphTemplate.kt', 'require 的异常消息', (
        '缓存评论地址缺少段落身份，无法生成离线段评入口',
    )),
    # ReviewSnapshotImages.kt — checkNotNull 的异常消息
    ('日志/异常', 'ReviewSnapshotImages.kt', 'checkNotNull 的异常消息', (
        '评论图片缺少书籍上下文',
    )),
    # ReviewSnapshotInventory.kt — checkNotNull / check / require 的异常消息
    ('日志/异常', 'ReviewSnapshotInventory.kt', 'checkNotNull / check / require 的异常消息', (
        '无法创建评论目录索引: ${indexDir.absolutePath}',
        '无法读取评论缓存目录: ${dir.absolutePath}',
        '评论快照身份错误: ${file.absolutePath}',
        '评论目录索引为空: ${index.baseFile.absolutePath}',
    )),
    # ReviewSnapshotMerger.kt — 回复开关正则与 check 异常消息；逐条细分见 OVERRIDES
    ('日志/异常', 'ReviewSnapshotMerger.kt', '回复开关正则与 check 异常消息；逐条细分见 OVERRIDES', (
        '新数据快照包含重复评论 ID',
    )),
    # ReviewSnapshotStore.kt — check 异常消息与导出结果摘要（诊断输出）
    ('日志/异常', 'ReviewSnapshotStore.kt', 'check 异常消息与导出结果摘要（诊断输出）', (
        '无法创建评论快照导出目录: ${targetDir.absolutePath}',
        '无法创建评论缓存目录: ${targetDir.absolutePath}',
        '评论缓存文件在复制期间变化或复制不完整',
        '评论缓存文件复制不完整: ${source.name}',
        '评论缓存有 $failed 个文件未能复制，其余文件已原样导出',
    )),
    # ReviewWorkerAdapter.kt — failTask 的失败原因（诊断消息）
    ('日志/异常', 'ReviewWorkerAdapter.kt', 'failTask 的失败原因（诊断消息）', (
        '评论缓存宿主异常结束',
    )),
    # RuleAnalyzer.kt — throw Error 的异常消息（规则括号未平衡）
    ('日志/异常', 'RuleAnalyzer.kt', 'throw Error 的异常消息（规则括号未平衡）', (
        '后未平衡',
    )),
    # TextChapterLayout.kt — AppLog.putDebug 多行日志续行（EPUB Native Layout skip）
    ('日志/异常', 'TextChapterLayout.kt', 'AppLog.putDebug 多行日志续行（EPUB Native Layout skip）', (
        'EPUB Native Layout skip: getNativeLayout 返回 null, ',
        'EPUB Native Layout skip: layout 页数为 0, ',
    )),
    # WebViewActivity.kt — AppLog.putDebug 日志续行残片与 checkNotNull 异常消息
    ('日志/异常', 'WebViewActivity.kt', 'AppLog.putDebug 日志续行残片与 checkNotNull 异常消息', (
        '} 书=${context.bookName}',
        '评论快照资源不存在: $resourceUrl',
        '评论快照资源引用缺少书籍上下文: $resourceUrl',
    )),
    # WebViewPool.kt — logScopedDestroy 的日志动作标签
    ('日志/异常', 'WebViewPool.kt', 'logScopedDestroy 的日志动作标签', (
        '执行销毁',
        '计划销毁',
        '跳过使用中的实例',
    )),
    # CoverHtmlActivity.kt — 封面 HTML 预览渲染的示例书名/作者假数据，属分享预览假数据
    ('诊断/示例数据', 'CoverHtmlActivity.kt', '封面 HTML 预览渲染的示例书名/作者假数据，属分享预览假数据', (
        '天蚕土豆',
        '斗破苍穹',
    )),
    # DebugEventCenter.kt — 调试日志纯文本导出模板的段落标题与字段标签
    ('诊断/示例数据', 'DebugEventCenter.kt', '调试日志纯文本导出模板的段落标题与字段标签', (
        '=== 调试日志导出 ===\\n',
        '\\n详情:\\n$it\\n',
        '书源: $it\\n',
        '总条数: ${_events.size}\\n\\n',
        '方法: $it\\n',
        '消息: ${event.message}\\n',
        '状态码: $it\\n',
        '耗时: ${it}ms\\n',
    )),
    # DebugLogDialog.kt — 调试事件详情文本标签，与 DebugEventCenter 的纯文本导出模板同源
    ('诊断/示例数据', 'DebugLogDialog.kt', '调试事件详情文本标签，与 DebugEventCenter 的纯文本导出模板同源', (
        '\\n书源: $it',
    )),
    # DiagnosticExporter.kt — 崩溃诊断报告/zip summary 的段落标题，属导出诊断文本
    ('诊断/示例数据', 'DiagnosticExporter.kt', '崩溃诊断报告/zip summary 的段落标题，属导出诊断文本', (
        '===== 环境信息 =====\\n',
        '\\n===== logcat 末尾（最近 $LOGCAT_TAIL_LINES 行，已截断到 ${MAX_LOGCAT_TEXT} 字符）=====\\n',
        '\\n===== 上次崩溃 =====\\n',
        '\\n===== 已记录崩溃文件（最近 ${crashes.size} 个）=====\\n',
        '\\n===== 最近一次崩溃全文 =====\\n',
        '\\n（没有已记录的崩溃文件）\\n',
    )),
    # EpubFile.kt — EPUB 调试导出 README 说明、AppLog 日志、CSS 选择器与 EPUB 元数据比较值；逐条细分见 OVERRIDES
    ('诊断/示例数据', 'EpubFile.kt', 'EPUB 调试导出 README 说明、AppLog 日志、CSS 选择器与 EPUB 元数据比较值；逐条细分见 OVERRIDES', (
        'raw_*.xhtml/html 是 EPUB 解压后的原文；rendered.html 是应用解析 CSS 和资源路径后送入阅读器的内容。\\n',
    )),
    # ReviewDataCapture.kt — 评论快照 HTML 渲染模板的插值残片（赞/回复 标签），属导出诊断文本
    ('诊断/示例数据', 'ReviewDataCapture.kt', '评论快照 HTML 渲染模板的插值残片（赞/回复 标签），属导出诊断文本', (
        ' 回复 ${it.text(',
        ')} · 赞 ${record.getAsJsonObject(',
    )),
)

FINAL_EXEMPT_MAP: dict[tuple[str, str], str] = {
    (basename, value): category
    for category, basename, _reason, values in FINAL_EXEMPT_RULES
    for value in values
}


def is_static_table(line: str) -> bool:
    return bool(STATIC_TABLE_RE.search(line)) and len(list(STR_RE.finditer(line))) >= 2

# Regexes, JS/HTML/CSS snippets, templates and serialized payloads are never UI text.
TECH_MARKERS = (
    r"\\p\{", r"\\s", r"\\d", r"\[.*\|.*\]", r"<\w+", r"</\w+",
    r"\bfunction\b", r"\breturn\b", r"\{\{", r"\}\}", r"<!DOCTYPE",
    r"https?://", r"\bjavaScript\b", r"\.js\b", r"data:", r"^@",
)


def is_map_key(suffix: str) -> bool:
    """A Chinese literal followed by `to` is a map/protocol key, not display text."""
    return bool(re.match(r"\s*to\b", suffix))


def is_technical(value: str) -> bool:
    if len(value) > 200:
        return True
    if any(re.search(p, value, re.I) for p in TECH_MARKERS):
        return True
    # Chinese punctuation is fine, but code-like bracket soup is not
    if value.count("{") > 2 or value.count(";") > 3:
        return True
    return False


def kotlin_unescape(s: str) -> str:
    # Keep \n and \t as literal escapes: Android resources interpret them the same way.
    return s.replace('\\"', '"')


def android_escape(s: str) -> str:
    """Escape a literal for Android string resources."""
    out = sax.escape(s, {'"': "&quot;", "'": "\\'"})
    # Escape bare % so aapt2 does not read it as a format specifier.
    out = re.sub(r"%(?!(?:\d+\$)?[sdf])", "%%", out)
    return out


def slug(text: str, max_words: int = 5) -> str:
    text = re.sub(r"%\d+\$[sdf]", " ", text).replace("%", " ")
    words = re.findall(r"[A-Za-z0-9]+", text.lower())
    stop = {"the", "a", "an", "of", "to", "for", "in", "on", "with", "and", "is", "are"}
    words = [w for w in words if w not in stop] or words
    words = words[:max_words]
    return "_".join(words) or "text"


def scan_files(files: list[str]) -> dict:
    entries: dict[str, dict] = {}
    for rel in files:
        path = os.path.join(ROOT, rel)
        if os.path.basename(rel) in EXCLUDE_BASENAMES:
            continue
        if not os.path.isfile(path):
            print(f"! missing file: {rel}", file=sys.stderr)
            continue
        in_raw = False
        for lineno, line in enumerate(open(path, encoding="utf-8"), 1):
            stripped = line.strip()
            toggles = line.count('"""')
            raw_line = in_raw or toggles > 0
            if toggles % 2 == 1:
                in_raw = not in_raw
            if raw_line or stripped.startswith(("//", "*", "/*")):
                continue
            for m in STR_RE.finditer(line):
                value = m.group(1)
                if not HAN.search(value):
                    continue
                prefix = line[:m.start()]
                risk = bool(RISK_RE.search(prefix.rstrip())) or is_map_key(line[m.end():])
                rec = entries.setdefault(value, {
                    "zh": value, "en": "", "count": 0, "risk": 0, "samples": []
                })
                rec["count"] += 1
                rec["risk"] += 1 if risk else 0
                if len(rec["samples"]) < 3:
                    rec["samples"].append(f"{os.path.basename(rel)}:{lineno}: {stripped[:120]}")
    return {"files": files, "entries": list(entries.values())}


def split_interpolations(value: str):
    """Return (android_template, [kotlin expressions]) or None when too complex."""
    args: list[str] = []
    pos = [0]

    def repl(m: re.Match) -> str:
        expr = m.group(1) if m.group(1) is not None else m.group(2)
        expr = expr.strip()
        # Allow simple identifiers, property chains, calls and arithmetic expressions.
        # Anything with quotes / nested braces / another $ is too risky to rewrite.
        if not expr or any(c in expr for c in '"{}' + "$"):
            raise ValueError(f"complex interpolation: {expr}")
        args.append(expr.strip())
        pos[0] += 1
        return f"%{pos[0]}$s"

    try:
        template = INTERP_RE.sub(repl, value)
    except ValueError:
        return None
    # a bare $ followed by text (rare) survives as-is
    return template, args


def existing_zh_keys(exclude: str = "") -> dict[str, str]:
    """Chinese text -> existing resource key (reuse instead of duplicating)."""
    import xml.etree.ElementTree as ET
    folder = os.path.join(ROOT, VALUES_ZH)
    out: dict[str, str] = {}
    for fn in os.listdir(folder):
        if not fn.endswith(".xml") or fn == exclude:
            continue
        try:
            root = ET.parse(os.path.join(folder, fn)).getroot()
        except ET.ParseError:
            continue
        for e in root.iter("string"):
            text = "".join(e.itertext()).replace("\\'", "'")
            if text and e.get("name"):
                out.setdefault(text, e.get("name"))
    return out


def build_keys(batch: dict):
    prefix = batch["prefix"]
    used: Counter = Counter()
    mapping: dict[str, str] = {}
    reused: set[str] = set()
    zh_keys = existing_zh_keys(batch["resource"])
    all_existing = set(zh_keys.values())
    for e in batch["entries"]:
        if not e.get("en"):
            continue
        if e.get("key"):
            key = e["key"]
        else:
            candidate = zh_keys.get(kotlin_unescape(e["zh"]))
            if candidate and "${" not in e["zh"] and "$" not in e["zh"] and "%" not in e["zh"]:
                key = candidate
            else:
                base = f"{prefix}_{slug(e['en'])}"
                used[base] += 1
                key = base if used[base] == 1 else f"{base}_{used[base]}"
        if key in all_existing:
            reused.add(key)
        mapping[e["zh"]] = key
    return mapping, reused


def write_resources(batch: dict, mapping: dict[str, str], reused: set[str], dry_run: bool = False):
    name = batch["resource"]
    lines_en, lines_zh = [], []
    for e in batch["entries"]:
        if not e.get("en"):
            continue
        if is_technical(e["zh"]) and not e.get("force"):
            continue
        key = mapping[e["zh"]]
        if key in reused:
            continue
        zh_raw = kotlin_unescape(e["zh"])
        en_raw = kotlin_unescape(e["en"])
        tpl = split_interpolations(zh_raw)
        if tpl is None:
            print(f"! skip (complex interpolation): {zh_raw[:60]}", file=sys.stderr)
            continue
        zh_tpl, _ = tpl
        # English translations already carry Android positional placeholders;
        # never run the Kotlin interpolation parser on them (it would eat %1$s).
        en_tpl = en_raw
        lines_en.append(f'    <string name="{key}">{android_escape(en_tpl)}</string>')
        lines_zh.append(f'    <string name="{key}">{android_escape(zh_tpl)}</string>')
    header = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    footer = '</resources>\n'
    if dry_run:
        print(f"  [dry-run] would write {len(lines_en)} strings to {name}")
        return
    for folder, lines in ((VALUES, lines_en), (VALUES_ZH, lines_zh)):
        path = os.path.join(ROOT, folder, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(header + "\n".join(lines) + "\n" + footer)
        print(f"  wrote {folder}/{name}: {len(lines)} strings")


def apply_batch(batch: dict, mapping: dict[str, str], dry_run: bool = False):
    total = Counter()
    skipped: list[str] = []
    entry_by_zh = {e["zh"]: e for e in batch["entries"]}
    for rel in batch["files"]:
        path = os.path.join(ROOT, rel)
        if os.path.basename(path) in EXCLUDE_BASENAMES:
            continue
        src = open(path, encoding="utf-8").read()
        lines = src.split("\n")
        changed = False
        # Settings search aliases are index data, never UI text. Track the whole
        # (possibly multi-line) `searchKeys = listOf(...)` statement, not just its
        # first line, so continuation lines are protected too.
        in_search_keys = False
        sk_depth = 0
        # Kotlin raw strings ("""...""") may span lines. Replacing a literal
        # inside one leaves stray quotes and breaks the syntax, so skip the
        # whole block including its opening/closing lines.
        in_raw_string = False
        for i, line in enumerate(lines):
            stripped = line.strip()
            toggles = line.count('"""')
            raw_line = in_raw_string or toggles > 0
            if toggles % 2 == 1:
                in_raw_string = not in_raw_string
            if raw_line:
                continue
            if "searchKeys" in line:
                in_search_keys = True
                sk_depth = line.count("(") - line.count(")")
            elif in_search_keys:
                sk_depth += line.count("(") - line.count(")")
            skip_line = in_search_keys
            if in_search_keys and sk_depth <= 0:
                in_search_keys = False
            if skip_line or stripped.startswith(("//", "*", "/*")):
                continue
            if batch.get("static_table_guard") and is_static_table(line):
                continue
            out = []
            cursor = 0
            for m in STR_RE.finditer(line):
                value = m.group(1)
                if value not in mapping or not HAN.search(value):
                    continue
                if is_technical(value):
                    skipped.append(f"{os.path.basename(rel)}:{i+1}: (technical) {value[:40]}")
                    continue
                entry = entry_by_zh.get(value)
                prefix = line[:m.start()]
                risky = bool(RISK_RE.search(prefix.rstrip())) or is_map_key(line[m.end():])
                if risky and not (entry and entry.get("force")):
                    skipped.append(f"{os.path.basename(rel)}:{i+1}: {value[:40]}")
                    continue
                tpl = split_interpolations(kotlin_unescape(value))
                if tpl is None:
                    skipped.append(f"{os.path.basename(rel)}:{i+1}: (complex) {value[:40]}")
                    continue
                _, args = tpl
                call = f"appCtx.getString(R.string.{mapping[value]}"
                if args:
                    call += ", " + ", ".join(args)
                call += ")"
                out.append((m.start(), m.end(), call))
            for start, end, call in reversed(out):
                line = line[:start] + call + line[end:]
                changed = True
                total["replaced"] += 1
            lines[i] = line
        if not changed:
            continue
        text = "\n".join(lines)
        imports = []
        if "appCtx.getString" in text and "import splitties.init.appCtx" not in text:
            imports.append("import splitties.init.appCtx")
        if "R.string." in text and "import io.legado.app.R" not in text:
            imports.append("import io.legado.app.R")
        if imports:
            # append right after the last existing import, keep everything else untouched
            nl = text.split("\n")
            last_import = max((j for j, l in enumerate(nl) if l.startswith("import ")), default=0)
            nl[last_import + 1:last_import + 1] = sorted(imports)
            text = "\n".join(nl)
        if not dry_run:
            open(path, "w", encoding="utf-8").write(text)
        total["files"] += 1
    print(f"  replaced {total['replaced']} literals in {total['files']} files")
    if skipped:
        print(f"  skipped {len(skipped)} risky/complex occurrences (left as-is):")
        for s in skipped[:25]:
            print("   ", s)
        if len(skipped) > 25:
            print(f"    ... and {len(skipped)-25} more")
    return skipped


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("scan")
    s.add_argument("--prefix", required=True)
    s.add_argument("--resource", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("files", nargs="+")
    a = sub.add_parser("apply")
    a.add_argument("--batch", required=True)
    a.add_argument("--dry-run", action="store_true")
    a.add_argument("--no-resources", action="store_true")
    f = sub.add_parser("fill")
    f.add_argument("--batch", required=True)
    f.add_argument("--translations", required=True)
    f.add_argument("--force", default="", help="comma separated zh literals to force-replace")
    v = sub.add_parser("validate")
    v.add_argument("--resource", required=True)
    v.add_argument("files", nargs="*")
    c = sub.add_parser("classify", help="report how the remaining Chinese literals break down")
    c.add_argument("--top", type=int, default=25)
    r = sub.add_parser("reuse")
    r.add_argument("--dry-run", action="store_true")
    r.add_argument("--dir", default="")
    r.add_argument("files", nargs="*")
    args = ap.parse_args()
    if args.cmd == "scan":
        data = {"prefix": args.prefix, "resource": args.resource,
                "files": args.files, "entries": scan_files(args.files)["entries"]}
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        n = len(data["entries"])
        print(f"scan: {n} unique Chinese literals ({sum(e['count'] for e in data['entries'])} occurrences)")
        print(f"  risky: {sum(1 for e in data['entries'] if e['risk'])}")
        print(f"  -> {args.out}")
    elif args.cmd == "apply":
        data = json.load(open(args.batch, encoding="utf-8"))
        missing = [e["zh"] for e in data["entries"] if not e.get("en")]
        if missing:
            print(f"! {len(missing)} entries have no English translation; they are left untouched")
        mapping, reused = build_keys(data)
        print(f"  reused {len(reused)} existing resource keys")
        if not args.no_resources:
            write_resources(data, mapping, reused, args.dry_run)
        apply_batch(data, mapping, args.dry_run)
        if not args.dry_run:
            # Pin the generated keys so re-running this batch is idempotent.
            for e in data["entries"]:
                if e["zh"] in mapping:
                    e["key"] = mapping[e["zh"]]
            json.dump(data, open(args.batch, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    elif args.cmd == "fill":
        data = json.load(open(args.batch, encoding="utf-8"))
        tr = json.load(open(args.translations, encoding="utf-8"))
        forces = set(x for x in args.force.split(",") if x)
        found = 0
        for e in data["entries"]:
            if e["zh"] in tr:
                e["en"] = tr[e["zh"]]
                found += 1
            if e["zh"] in forces:
                e["force"] = True
        json.dump(data, open(args.batch, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        unknown = set(tr) - {e["zh"] for e in data["entries"]}
        print(f"filled {found}/{len(data['entries'])} entries; unknown keys: {len(unknown)}")
        for k in list(unknown)[:10]:
            print("  !", k[:60])
    elif args.cmd == "validate":
        validate(args.resource, args.files)
    elif args.cmd == "reuse":
        reuse(args.files, args.dir, args.dry_run)
    elif args.cmd == "classify":
        classify(args.top)


def classify(top: int = 25):
    """Single source of truth for the remaining-Chinese report.

    Categories, in priority order: file-level exemptions, LLM protocol text,
    diagnostic fragments, technical strings, log/exception output, data
    semantics, R57 per-literal exemptions (FINAL_EXEMPT_MAP), and finally
    genuine user-visible candidates. The seven exemption buckets plus the
    candidate bucket always sum to 总量.
    """
    cat: Counter = Counter()
    per_file: Counter = Counter()
    per_dir: Counter = Counter()
    java_root = os.path.join(ROOT, JAVA)
    for dp, _, fns in os.walk(java_root):
        for fn in fns:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dp, fn)
            rel = os.path.relpath(path, java_root)
            in_raw = False
            in_search_keys = False
            sk_depth = 0
            for line in open(path, encoding="utf-8"):
                stripped = line.strip()
                toggles = line.count('"""')
                raw_line = in_raw or toggles > 0
                if toggles % 2 == 1:
                    in_raw = not in_raw
                if "searchKeys" in line:
                    in_search_keys = True
                    sk_depth = line.count("(") - line.count(")")
                elif in_search_keys:
                    sk_depth += line.count("(") - line.count(")")
                skip_line = in_search_keys
                if in_search_keys and sk_depth <= 0:
                    in_search_keys = False
                if stripped.startswith(("//", "*", "/*")):
                    continue
                if raw_line or skip_line:
                    # Kotlin raw strings (SQL / JS / regex) and settings search
                    # index aliases are exemptions, but still count toward the total.
                    for m in STR_RE.finditer(line):
                        if HAN.search(m.group(1)):
                            cat["总量"] += 1
                            cat["原始串/搜索索引"] += 1
                    continue
                for m in STR_RE.finditer(line):
                    value = m.group(1)
                    if not HAN.search(value):
                        continue
                    cat["总量"] += 1
                    prefix = line[:m.start()].rstrip()
                    suffix = line[m.end():]
                    if fn in EXCLUDE_BASENAMES:
                        cat["文件级技术豁免"] += 1
                    elif fn in LLM_TEXT_BASENAMES:
                        cat["LLM 协议文本"] += 1
                    elif fn in DIAGNOSTIC_BASENAMES:
                        cat["诊断/示例数据"] += 1
                    elif is_technical(value) or is_static_table(line):
                        cat["技术字符串"] += 1
                    elif LOG_RE.search(line):
                        cat["日志/异常"] += 1
                    elif RISK_RE.search(prefix) or is_map_key(suffix):
                        cat["数据语义"] += 1
                    elif (fn, value) in FINAL_EXEMPT_MAP:
                        # R57 逐条判定：只命中「文件 + 精确字面量」，归入该条目
                        # 经核实后的真实豁免桶，不整体排除任何文件。
                        cat[FINAL_EXEMPT_MAP[(fn, value)]] += 1
                    else:
                        cat["真实可见候选"] += 1
                        per_file[rel] += 1
                        per_dir["/".join(rel.split("/")[:3])] += 1
    print("剩余中文分类（classify）:")
    for k in ("总量", "文件级技术豁免", "LLM 协议文本", "诊断/示例数据",
              "原始串/搜索索引", "技术字符串", "日志/异常", "数据语义",
              "真实可见候选"):
        print(f"  {k:14s} {cat[k]:5d}")
    print(f"\n真实可见候选 Top{top} 文件:")
    for p, n in per_file.most_common(top):
        print(f"  {n:4d} {p}")
    return cat, per_file


def reuse(files: list[str], directory: str, dry_run: bool):
    """Replace literals whose exact Chinese text already exists in values-zh."""
    if directory:
        base = os.path.join(ROOT, directory)
        files = [os.path.relpath(os.path.join(dp, fn), ROOT)
                 for dp, _, fns in os.walk(base) for fn in fns if fn.endswith(".kt")]
    zh_keys = existing_zh_keys()
    mapping = {zh: key for zh, key in zh_keys.items()}
    data = {
        "files": files,
        "prefix": "reuse",
        "resource": "__reuse__.xml",
        "static_table_guard": True,
        "entries": [{"zh": zh, "en": "reuse", "key": key} for zh, key in mapping.items()],
    }
    print(f"reuse: {len(files)} files, {len(mapping)} existing Chinese strings")
    apply_batch(data, mapping, dry_run)


def validate(resource: str, files: list[str]):
    import xml.etree.ElementTree as ET

    def load(folder):
        path = os.path.join(ROOT, folder, resource)
        tree = ET.parse(path)
        return {e.get("name"): "".join(e.itertext()) for e in tree.getroot().iter("string")}

    en, zh = load(VALUES), load(VALUES_ZH)
    print(f"resources: en={len(en)} zh={len(zh)}")
    only_en, only_zh = set(en) - set(zh), set(zh) - set(en)
    print(f"  keys only in en: {len(only_en)}; only in zh: {len(only_zh)}")
    for k in list(only_en)[:5]:
        print("   !", k)
    ph = re.compile(r"%\d+\$[sdf]")
    bad = [k for k in en.keys() & zh.keys() if ph.findall(en[k]) != ph.findall(zh[k])]
    print(f"  placeholder mismatches: {len(bad)}")
    for k in bad[:10]:
        print("   !", k, ph.findall(en[k]), ph.findall(zh[k]))
    corrupt = [k for k, v in en.items() if re.search(r"%%\d+%", v)]
    print(f"  corrupted placeholders: {len(corrupt)}")
    for k in corrupt[:10]:
        print("   !", k, en[k][:60])
    allkeys = set()
    for folder in (VALUES, VALUES_ZH):
        for fn in os.listdir(os.path.join(ROOT, folder)):
            if not fn.endswith(".xml"):
                continue
            try:
                allkeys |= {e.get("name") for e in ET.parse(os.path.join(ROOT, folder, fn)).getroot().iter("string")}
            except ET.ParseError:
                pass
    refs = set()
    for dp, _, fs in os.walk(os.path.join(ROOT, JAVA)):
        for fn in fs:
            if fn.endswith((".kt", ".java")):
                refs |= set(re.findall(r"R\.string\.(\w+)", open(os.path.join(dp, fn), encoding="utf-8").read()))
    missing = refs - allkeys
    print(f"  R.string refs: {len(refs)}; missing: {len(missing)}")
    for k in list(missing)[:10]:
        print("   !", k)
    for rel in files:
        path = os.path.join(ROOT, rel)
        n = sum(1 for line in open(path, encoding="utf-8")
                if not line.strip().startswith(("//", "*", "/*"))
                for m in STR_RE.finditer(line) if HAN.search(m.group(1)))
        print(f"  {os.path.basename(rel)}: {n} Chinese literals left")


if __name__ == "__main__":
    main()
