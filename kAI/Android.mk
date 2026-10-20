LOCAL_PATH := $(call my-dir)

# Beta 5: kAI answers from the cloud (LocalEngine); the on-device llama.cpp engine and its
# models are gone. Only the speech model stays on the phone (offline voice), whisper.cpp built
# with the NDK into one JNI library (jni/kai_whisper.cpp).
include $(CLEAR_VARS)
LOCAL_MODULE := libkai_whisper
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE_SUFFIX := .so
LOCAL_MULTILIB := 64
LOCAL_SRC_FILES_arm64 := jni/prebuilt/arm64-v8a/libkai_whisper.so
LOCAL_SRC_FILES_x86_64 := jni/prebuilt/x86_64/libkai_whisper.so
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

# Beta 5: kAI's own voice, Piper neural voices run by sherpa-onnx v1.13.8 (Apache-2.0,
# prebuilt for Android by its authors; Java API in src/com/k2fsa/sherpa/onnx)
include $(CLEAR_VARS)
LOCAL_MODULE := libsherpa-onnx-jni
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE_SUFFIX := .so
LOCAL_MULTILIB := 64
LOCAL_SRC_FILES_arm64 := jni/prebuilt/arm64-v8a/libsherpa-onnx-jni.so
LOCAL_STRIP_MODULE := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := libonnxruntime
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE_SUFFIX := .so
LOCAL_MULTILIB := 64
LOCAL_SRC_FILES_arm64 := jni/prebuilt/arm64-v8a/libonnxruntime.so
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
LOCAL_JNI_SHARED_LIBRARIES := libkai_whisper libsherpa-onnx-jni libonnxruntime
LOCAL_MULTILIB := 64
include $(BUILD_PACKAGE)
# tests/voicetest: a bench for the voice, built by hand (see its README), never in the ROM
