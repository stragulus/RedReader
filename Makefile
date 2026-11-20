# Local development helpers. Native code (libs/redreader-translate) is only built for
# the ABI of the target device, which is detected via adb. Override with e.g.
#   make install ABI=arm64-v8a
# With several devices attached, pick one with SERIAL=<serial> (see `adb devices`).

GRADLE  := ./gradlew
PACKAGE := org.quantumbadger.redreader

ifdef SERIAL
export ANDROID_SERIAL := $(SERIAL)
endif

# Lazily evaluated, so targets that don't need a device don't call adb.
ABI ?= $(shell adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')

ABI_ARGS = -PtranslateAbis=$(ABI)

E2E_TEST := org.quantumbadger.redreader.translation.BergamotEndToEndTest

# Emulator: the AVD must be an x86_64 image (`make emulator-list` shows them).
ANDROID_SDK    ?= $(or $(ANDROID_HOME),$(ANDROID_SDK_ROOT),$(HOME)/Android/Sdk)
export ANDROID_HOME := $(ANDROID_SDK)
EMULATOR       ?= $(ANDROID_SDK)/emulator/emulator
AVD            ?= Pixel_6_Pro
EMULATOR_FLAGS ?=
EMULATOR_LOG   := build/emulator.log
BOOT_TIMEOUT   ?= 300

# Prints the adb serial of the running emulator for $(AVD), if any.
define find_emulator
for s in $$(adb devices | awk '/^emulator-[0-9]+\tdevice/ {print $$1}'); do \
	if [ "$$(adb -s $$s emu avd name 2>/dev/null | head -n1 | tr -d '\r')" = "$(AVD)" ]; then \
		echo $$s; break; \
	fi; \
done
endef

.PHONY: help submodules check-abi build install run logcat \
	test test-translate test-e2e test-device release precommit clean \
	emulator emulator-list emulator-start emulator-stop

help:
	@echo "Targets:"
	@echo "  submodules      Fetch the native translation submodules"
	@echo "  build           Build the debug APK for the device ABI"
	@echo "  install         Build and install the debug APK on the device"
	@echo "  run             install, then launch the app"
	@echo "  logcat          Follow translation-related logcat output"
	@echo "  emulator        Start the emulator (AVD=$(AVD)) if needed, install and launch"
	@echo "  emulator-start  Start the emulator and wait for it to boot"
	@echo "  emulator-stop   Shut down the emulator"
	@echo "  emulator-list   List the available AVDs"
	@echo "  test            JVM unit tests"
	@echo "  test-translate  Instrumented tests of the translate library (device)"
	@echo "  test-e2e        Bergamot end-to-end test (device + network)"
	@echo "  test-device     All instrumented tests of the app (device)"
	@echo "  release         Release build for all ABIs, as in CI (slow)"
	@echo "  precommit       pmd, unit tests, checkstyle and lint"
	@echo ""
	@echo "Variables: ABI=<abi> (detected: run 'make check-abi'), SERIAL=<device serial>,"
	@echo "           AVD=<avd name>, EMULATOR_FLAGS=<extra emulator flags>"

submodules:
	git submodule update --init --recursive

check-abi:
	@abi="$(ABI)"; \
	case "$$abi" in \
		arm64-v8a|armeabi-v7a|x86_64) echo "Using ABI $$abi" ;; \
		"") echo "No ABI: attach a device/emulator, or pass ABI=<abi>" >&2; exit 1 ;; \
		*) echo "Unsupported ABI '$$abi' (arm64-v8a, armeabi-v7a, x86_64)" >&2; exit 1 ;; \
	esac

build: check-abi
	$(GRADLE) assembleDebug $(ABI_ARGS)

install: check-abi
	$(GRADLE) installDebug $(ABI_ARGS)

run: install
	adb shell monkey -p $(PACKAGE) -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

logcat:
	adb logcat | grep -i -E "translat|bergamot"

emulator-list:
	@$(EMULATOR) -list-avds

emulator-start:
	@serial=$$($(find_emulator)); \
	if [ -n "$$serial" ]; then echo "$(AVD) already running as $$serial"; exit 0; fi; \
	mkdir -p $(dir $(EMULATOR_LOG)); \
	echo "Starting $(AVD) (log: $(EMULATOR_LOG))"; \
	nohup $(EMULATOR) -avd $(AVD) $(EMULATOR_FLAGS) >$(EMULATOR_LOG) 2>&1 & \
	pid=$$!; \
	for i in $$(seq $(BOOT_TIMEOUT)); do \
		if ! kill -0 $$pid 2>/dev/null; then \
			echo "Emulator exited, see $(EMULATOR_LOG)" >&2; exit 1; \
		fi; \
		serial=$$($(find_emulator)); \
		if [ -n "$$serial" ] && \
			[ "$$(adb -s $$serial shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then \
			echo "$(AVD) booted as $$serial"; exit 0; \
		fi; \
		sleep 1; \
	done; \
	echo "Timed out after $(BOOT_TIMEOUT)s waiting for $(AVD) to boot" >&2; exit 1

emulator-stop:
	@serial=$$($(find_emulator)); \
	if [ -z "$$serial" ]; then echo "$(AVD) is not running"; exit 0; fi; \
	adb -s $$serial emu kill

emulator: emulator-start
	@serial=$$($(find_emulator)); \
	$(MAKE) --no-print-directory run SERIAL=$$serial

test:
	$(GRADLE) testDebugUnitTest

test-translate: check-abi
	$(GRADLE) :redreader-translate:connectedDebugAndroidTest $(ABI_ARGS)

test-e2e: check-abi
	$(GRADLE) connectedDebugAndroidTest $(ABI_ARGS) \
		-Pandroid.testInstrumentationRunnerArguments.class=$(E2E_TEST)

test-device: check-abi
	$(GRADLE) connectedDebugAndroidTest $(ABI_ARGS)

release:
	$(GRADLE) assembleRelease

precommit:
	$(GRADLE) pmd test checkstyle lint

clean:
	$(GRADLE) clean
