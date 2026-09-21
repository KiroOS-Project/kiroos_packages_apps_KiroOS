LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := KiroMessages
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res packages/apps/KiroOS/KiroUi/res
LOCAL_STATIC_JAVA_LIBRARIES := kiroos-ui
# MMS PDU composer/parser/persister (com.google.android.mms.pdu)
LOCAL_JAVA_LIBRARIES := telephony-common
LOCAL_AAPT_FLAGS := --auto-add-overlay --extra-packages com.kiroos.ui
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true
LOCAL_OVERRIDES_PACKAGES := messaging Messaging Mms
LOCAL_PROGUARD_ENABLED := disabled
include $(BUILD_PACKAGE)
