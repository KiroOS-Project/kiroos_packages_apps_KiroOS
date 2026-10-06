LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := KiroSound
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res packages/apps/KiroOS/KiroUi/res
LOCAL_STATIC_JAVA_LIBRARIES := kiroos-ui
LOCAL_AAPT_FLAGS := --auto-add-overlay --extra-packages com.kiroos.ui
LOCAL_SDK_VERSION := current
# persistent (the effects live as long as its process): a privileged platform app
LOCAL_PRIVILEGED_MODULE := true
LOCAL_CERTIFICATE := platform
LOCAL_OVERRIDES_PACKAGES := AudioFX MusicFX
LOCAL_PROGUARD_ENABLED := disabled
include $(BUILD_PACKAGE)
