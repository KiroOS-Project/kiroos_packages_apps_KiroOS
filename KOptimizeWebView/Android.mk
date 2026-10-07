#
# KOptimize WebView 1.1: the KiroOS system WebView (com.kiroos.webview), our own build of
# Chromium 119.0.6045.214, the last Chromium for Android 7, with the LineageOS WebView privacy
# patches and S6 tuning (see README.md). It replaces the AOSP WebView (external/chromium-webview,
# 119.0.6045.134).
#
# The APK (both ABIs, ~200 MB) is kept out of git like the kAI models: kai/koptimize/install.sh
# copies it from the Chromium build to vendor/kiroos/webview. Without it the build keeps the
# AOSP WebView (kiroos_apps.mk adds this module only when the APK is there).
#

LOCAL_PATH := vendor/kiroos/webview

ifneq ($(wildcard $(LOCAL_PATH)/KOptimizeWebView.apk),)

include $(CLEAR_VARS)

LOCAL_MODULE := KOptimizeWebView
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_MULTILIB := both
LOCAL_CERTIFICATE := $(DEFAULT_SYSTEM_DEV_CERTIFICATE)
LOCAL_OVERRIDES_PACKAGES := webview
LOCAL_REQUIRED_MODULES := \
        libwebviewchromium_loader \
        libwebviewchromium_plat_support

LOCAL_MODULE_TARGET_ARCH := arm arm64
LOCAL_SRC_FILES := KOptimizeWebView.apk

LOCAL_PREBUILT_JNI_LIBS_arm := @lib/armeabi-v7a/libwebviewchromium.so
LOCAL_PREBUILT_JNI_LIBS_arm64 := @lib/arm64-v8a/libwebviewchromium.so

include $(BUILD_PREBUILT)

endif
