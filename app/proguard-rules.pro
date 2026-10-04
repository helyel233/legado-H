# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# 混合时不使用大小写混合，混合后的类名为小写
-dontusemixedcaseclassnames

# 这句话能够使我们的项目混淆后产生映射文件
# 包含有类名->混淆后类名的映射关系
-verbose

# 保留Annotation不混淆
-keepattributes *Annotation*,InnerClasses

# 避免混淆泛型
-keepattributes Signature

# 指定混淆是采用的算法，后面的参数是一个过滤器
# 这个过滤器是谷歌推荐的算法，一般不做更改
-optimizations !code/simplification/cast,!field/*,!class/merging/*

-flattenpackagehierarchy

#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################
# 屏蔽错误Unresolved class name
#noinspection ShrinkerUnresolvedReference

# 移除Log类打印各个等级日志的代码，打正式包的时候可以做为禁log使用，这里可以作为禁止log打印的功能使用
# 记得proguard-android.txt中一定不要加-dontoptimize才起作用
# 另外的一种实现方案是通过BuildConfig.DEBUG的变量来控制
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# 保持js引擎调用的java类
-keep class * extends io.legado.app.help.JsExtensions{*;}
# 数据类
-keep class **.data.entities.**{*;}
# hutool-core hutool-crypto
-keep class
!cn.hutool.core.util.RuntimeUtil,
!cn.hutool.core.util.ClassLoaderUtil,
!cn.hutool.core.util.ReflectUtil,
!cn.hutool.core.util.SerializeUtil,
!cn.hutool.core.util.ClassUtil,
cn.hutool.core.codec.**,
cn.hutool.core.util.**{*;}
-keep class cn.hutool.crypto.**{*;}
-dontwarn cn.hutool.**
# 缓存 Cookie
-keep class **.help.http.CookieStore{*;}
-keep class **.help.CacheManager{*;}
# StrResponse
-keep class **.help.http.StrResponse{*;}

# markwon
-dontwarn org.commonmark.ext.gfm.**

-keep class okhttp3.*{*;}
-keep class okio.*{*;}
-keep class com.jayway.jsonpath.*{*;}

# LiveEventBus
-keepclassmembers class androidx.lifecycle.LiveData {
    *** mObservers;
    *** mActiveCount;
}
-keepclassmembers class androidx.arch.core.internal.SafeIterableMap {
    *** size();
    *** putIfAbsent(...);
}

## ChangeBookSourceDialog initNavigationView
-keepclassmembers class androidx.appcompat.widget.Toolbar {
    *** mNavButtonView;
}

# MenuExtensions applyOpenTint
-keepnames class androidx.appcompat.view.menu.SubMenuBuilder
-keep class androidx.appcompat.view.menu.MenuBuilder {
    *** setOptionalIconsVisible(...);
    *** getNonActionItems();
}

# FileDocExtensions.kt treeDocumentFileConstructor
-keep class androidx.documentfile.provider.TreeDocumentFile {
    <init>(...);
}

# JsoupXpath
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.AxisSelector{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.NodeTest{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.Function{*;}

## JSOUP
-keep class org.jsoup.**{*;}
-dontwarn org.jspecify.annotations.NullMarked

## ExoPlayer 反射设置ua 保证该私有变量不被混淆
-keepclassmembers class androidx.media3.datasource.cache.CacheDataSource$Factory {
    *** upstreamDataSourceFactory;
}
## ExoPlayer 如果还不能播放就取消注释这个
# -keep class com.google.android.exoplayer2.** {*;}

## 对外提供api
-keep class io.legado.app.api.ReturnData{*;}

# Cronet
-keepclassmembers class org.chromium.net.X509Util {
    *** sDefaultTrustManager;
    *** sTestTrustManager;
}

# Throwable
-keepnames class * extends java.lang.Throwable
-keepclassmembernames,allowobfuscation class * extends java.lang.Throwable{*;}

# Sora Editor
-keep class org.eclipse.tm4e.** { *; }
-keep class org.joni.** { *; }

# GSYVideoPlayer
-keep class com.shuyu.gsyvideoplayer.** { *; }
-dontwarn com.shuyu.gsyvideoplayer.**
#-keep class com.shuyu.gsyvideoplayer.video.** { *; }
#-dontwarn com.shuyu.gsyvideoplayer.video.**
#-keep class com.shuyu.gsyvideoplayer.video.base.** { *; }
#-dontwarn com.shuyu.gsyvideoplayer.video.base.**
#-keep class com.shuyu.gsyvideoplayer.utils.** { *; }
#-dontwarn com.shuyu.gsyvideoplayer.utils.**
#-keep class com.shuyu.gsyvideoplayer.player.** {*;}
#-dontwarn com.shuyu.gsyvideoplayer.player.**
#-keep class tv.danmaku.ijk.** { *; }
#-dontwarn tv.danmaku.ijk.**
#-keep class androidx.media3.** {*;}
#-keep interface androidx.media3.**
#-keep class com.shuyu.alipay.** {*;}
#-keep interface com.shuyu.alipay.**
-keep public class * extends android.view.View{
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, java.lang.Boolean);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

#############################################
# R8 混淆兼容规则（release 启用 minify/shrinkResources 后必须遵守）
#############################################

# 尊重 @Keep 标注：类与其全部成员保持原名（JS 可达类如 JsExtensions/JsURL/TextFile.JsExtensions 等）
-keep @androidx.annotation.Keep class * { *; }

# WebView addJavascriptInterface 注入对象：注解方法由系统反射调用，方法名不可混淆
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# JS 加解密接口默认实现（java.md5Encode / java.createSymmetricCrypto / java.createSign 等）
-keep class io.legado.app.help.JsEncodeUtils { *; }
# 替换规则 JS 作用域对象（替换结果 JS 中可直接调用其方法）
-keep class io.legado.app.help.RegexJsExtensions { *; }
# JS 可达的加解密实现类（createAsymmetricCrypto / createSign 返回类型）
-keep class io.legado.app.help.crypto.** { *; }
# java.queryTTF / queryBase64TTF 返回类型
-keep class io.legado.app.model.analyzeRule.QueryTTF { *; }

# 持久化兼容：保留 app 内全部字段名
# （Gson 键名即字段名，涉及用户配置、备份 zip、WebDAV 同步、书源导出数据的兼容性）
-keepclassmembers class io.legado.app.** { <fields>; }

# 类名保留：
# 1) RhinoClassShutter 按类名字符串做 JS 安全黑名单，类名改变会导致黑名单失效
# 2) 书源 JS 可通过 Packages 按类名访问（含崩溃栈可读性）
-keepnames class io.legado.app.**
-keepnames class androidx.room.**
-keepnames class androidx.sqlite.db.**
-keepnames class com.script.**
-keepnames class org.mozilla.**
-keepnames class cn.hutool.**
-keepnames class splitties.init.AppCtxKt

# okio 子包同样被 Rhino 黑名单引用（okio 直属类已有 -keep class okio.* 规则）
-keepnames class okio.**

# Glide 5 通过 Class.forName 反射查找 KSP 生成的模块实现类
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
