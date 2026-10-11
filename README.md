# AndroidTorch

一个面向 Android 13 及以上设备的原生 Kotlin 手电筒应用，提供开关和纵向亮度滑条。

## 构建

- Android SDK Platform 35
- JDK 17（Android Studio 自带的 JDK 也可以）
- Android Gradle Plugin 8.9.3 所需的 Gradle 8.11.1

在 Android Studio 中打开项目并同步 Gradle。首次点按“开启手电筒”时，应用会请求相机权限。亮度档位取决于设备闪光灯硬件；仅支持单一亮度的设备仍可使用开关。

## APK

Debug APK 的 Gradle 构建输出位于 `app/build/outputs/apk/debug/app-debug.apk`。仓库内随源码提供的 APK 副本位于 `artifacts/AndroidTorch-debug.apk`；重新构建后，如需更新仓库中的 APK，请用最新构建产物替换此副本。