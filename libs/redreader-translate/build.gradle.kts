@file:Suppress("UnstableApiUsage")

// On-device translation (Mozilla Bergamot / Marian) and language detection (CLD2).
//
// The native code is built from source from pinned git submodules under src/main/cpp/third_party
// (run `git submodule update --init --recursive` first). No prebuilt binaries are used.
//
// Pass -PtranslateAbis=x86_64 (comma separated) to restrict the ABIs that are built, which is
// useful during development since a full native build of all ABIs takes a while.

plugins {
	// AGP is already on the build classpath via the root project (com.android.application), so the
	// library plugin is applied without a version.
	id("com.android.library")
}

val defaultAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val selectedAbis = project.findProperty("translateAbis")
	?.toString()
	?.split(',')
	?.map { it.trim() }
	?.filter { it.isNotEmpty() }
	?: defaultAbis

android {
	namespace = "org.quantumbadger.redreader.translate"
	compileSdk = libs.versions.sdk.compile.get().toInt()
	ndkVersion = libs.versions.ndk.get()

	defaultConfig {
		minSdk = libs.versions.sdk.min.get().toInt()
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		consumerProguardFiles("consumer-rules.pro")

		ndk {
			abiFilters.addAll(selectedAbis)
		}

		externalNativeBuild {
			cmake {
				// The native code is always built optimised, also for debug builds: an unoptimised
				// Marian is unusably slow, and the debug .so files would be enormous.
				arguments += listOf(
					"-DCMAKE_BUILD_TYPE=Release",
					"-DANDROID_STL=c++_static",
				)
				cppFlags += listOf("-std=c++17")
				// Only build the JNI library (not bergamot's CLI executables etc.)
				targets += listOf("redreader_translate")
			}
		}
	}

	externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
			version = libs.versions.cmake.get()
		}
	}

	lint {
		abortOnError = true
		warningsAsErrors = true
		lintConfig = rootProject.file("config/lint/lint.xml")
	}

	compileOptions {
		JavaVersion.toVersion(libs.versions.java.get()).let {
			sourceCompatibility = it
			targetCompatibility = it
		}
	}
}

dependencies {
	androidTestImplementation(libs.junit)
	androidTestImplementation(libs.androidx.test.core)
	androidTestImplementation(libs.androidx.test.rules)
	androidTestImplementation(libs.androidx.test.junit)
}
