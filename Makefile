ANDROID_TV_DEVICE ?= 192.168.1.4:5555
ANDROID_EMULATOR_DEVICE ?= emulator-5554
ADB ?= adb
GRADLE ?= ./gradlew

APK := app/build/outputs/apk/debug/app-debug.apk
PACKAGE := fr.bonamy.movies
COMPONENT := $(PACKAGE)/.MainActivity

.DEFAULT_GOAL := build
.PHONY: help build check install deploy run install-emulator deploy-emulator run-emulator devices clean

help:
	@echo "make build             Build the debug APK (default)"
	@echo "make check             Run tests, lint and build"
	@echo "make deploy            Build and install on TV; do not launch"
	@echo "make run               Restart the installed app on TV"
	@echo "make install-emulator  Build and install on emulator; do not launch"
	@echo "make deploy-emulator   Build, install and restart on emulator"
	@echo "make run-emulator      Restart the installed app on emulator"
	@echo "make devices           List ADB devices"
	@echo "make clean             Clean build outputs"
	@echo "Override ANDROID_TV_DEVICE=IP:PORT or ANDROID_EMULATOR_DEVICE=SERIAL as needed."

build:
	$(GRADLE) :app:assembleDebug

check:
	$(GRADLE) check :app:lintDebug :app:assembleDebug

install: build
	$(ADB) connect "$(ANDROID_TV_DEVICE)"
	$(ADB) -s "$(ANDROID_TV_DEVICE)" install -r "$(APK)"

deploy: install

run:
	$(ADB) connect "$(ANDROID_TV_DEVICE)"
	$(ADB) -s "$(ANDROID_TV_DEVICE)" shell am force-stop "$(PACKAGE)"
	$(ADB) -s "$(ANDROID_TV_DEVICE)" shell am start -W -n "$(COMPONENT)"

install-emulator: build
	$(ADB) -s "$(ANDROID_EMULATOR_DEVICE)" install -r "$(APK)"

deploy-emulator: install-emulator
	$(MAKE) run-emulator

run-emulator:
	$(ADB) -s "$(ANDROID_EMULATOR_DEVICE)" shell am force-stop "$(PACKAGE)"
	$(ADB) -s "$(ANDROID_EMULATOR_DEVICE)" shell am start -W -n "$(COMPONENT)"

devices:
	$(ADB) devices -l

clean:
	$(GRADLE) clean
