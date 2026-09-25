APP_PLATFORM := android-29
APP_ABI := arm64-v8a armeabi-v7a x86_64
APP_OPTIM := release
APP_LDFLAGS := -Wl,-z,max-page-size=16384
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
APP_CFLAGS := -DBB_GLOBAL_CONST= -fno-strict-aliasing -Oz
