#
# KiroOS v1.0 app suite (packages/apps/KiroOS) and the LineageOS / AOSP stock apps it
# replaces. Inherited by device/samsung/universal7420-common/kiroos/kiroos.mk.
#

# Suite
PRODUCT_PACKAGES += \
    KiroSetupWizard \
    KiroHome \
    KiroPhone \
    KiroMessages \
    KiroGallery \
    PulseMusic \
    SurgeBrowser \
    KiroFiles \
    KiroCalendar \
    KiroCalculator \
    KiroContacts \
    KiroClock \
    KiroRecorder \
    KiroCamera \
    kAI \
    KeurStore \
    KiroSound \
    KiroCanvas

# Stock apps replaced by the suite (PRODUCT_PACKAGES_REMOVE is applied in build/core/main.mk
# after all inherited product makefiles, so nothing can bring them back)
PRODUCT_PACKAGES_REMOVE += \
    LineageSetupWizard \
    Provision \
    Trebuchet \
    Launcher2 \
    Launcher3 \
    Dialer \
    messaging \
    Messaging \
    Mms \
    Gallery2 \
    Eleven \
    Jelly \
    Browser \
    Browser2 \
    CMFileManager \
    ExactCalculator \
    Calculator \
    DeskClock \
    Contacts \
    Calendar \
    Recorder \
    SoundRecorder \
    Snap \
    Camera2

# Providers the replaced apps relied on stay: contacts, calendar, telephony, media,
# downloads, blocked numbers, call log backup.
