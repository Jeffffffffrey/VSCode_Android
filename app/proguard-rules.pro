# WebView 的 JS 桥必须保留，否则剪贴板等功能失效
-keepclassmembers class com.vsc.android.webview.AndroidBridge {
    public *;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# Compose 运行时
-dontwarn androidx.compose.**
