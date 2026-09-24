LOCAL_PATH := $(call my-dir)

# kAI's on-device engine: llama.cpp built with the NDK (-O3, arm64-v8a) into one JNI library,
# see jni/kai_llm.cpp and /home/icholas/kai/kiro_kai_jni.sh
include $(CLEAR_VARS)
LOCAL_MODULE := libkai_llm
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE_SUFFIX := .so
LOCAL_MULTILIB := 64
LOCAL_SRC_FILES_arm64 := jni/prebuilt/arm64-v8a/libkai_llm.so
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := libkai_whisper
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE_SUFFIX := .so
LOCAL_MULTILIB := 64
LOCAL_SRC_FILES_arm64 := jni/prebuilt/arm64-v8a/libkai_whisper.so
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := kAI
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res packages/apps/KiroOS/KiroUi/res
LOCAL_STATIC_JAVA_LIBRARIES := kiroos-ui
LOCAL_AAPT_FLAGS := --auto-add-overlay --extra-packages com.kiroos.ui
LOCAL_SDK_VERSION := current
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_JNI_SHARED_LIBRARIES := libkai_llm libkai_whisper
LOCAL_MULTILIB := 64
include $(BUILD_PACKAGE)
