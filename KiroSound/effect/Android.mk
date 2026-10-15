LOCAL_PATH := $(call my-dir)

# KiroOS Equalizer: the platform equalizer effect (10 bands), loaded by audioserver from
# /system/vendor/etc/audio_effects.conf (device/samsung/universal7420-common/kiroos/configs)
include $(CLEAR_VARS)
LOCAL_MODULE := libkiroeq
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := soundfx
LOCAL_SRC_FILES := KiroEqualizer.c
LOCAL_SHARED_LIBRARIES := libcutils liblog
LOCAL_C_INCLUDES := $(call include-path-for, audio-effects)
LOCAL_CFLAGS += -O2 -fvisibility=hidden -Wall -Werror -std=gnu99
include $(BUILD_SHARED_LIBRARY)
