#
# KiroUi: shared UI library of the KiroOS v1.0 apps (Pure Black AMOLED theme, squircle
# buttons, touch animations). Apps use it with:
#   LOCAL_STATIC_JAVA_LIBRARIES += kiroos-ui
#   LOCAL_RESOURCE_DIR += packages/apps/KiroOS/KiroUi/res
#   LOCAL_AAPT_FLAGS += --auto-add-overlay --extra-packages com.kiroos.ui
#
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := kiroos-ui
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_SDK_VERSION := current
include $(BUILD_STATIC_JAVA_LIBRARY)
