package org.quantumbadger.redreader.translate

internal object NativeLibrary {

	private const val NAME = "redreader_translate"

	@Volatile
	private var loaded = false

	/**
	 * Loads the native library. Throws [UnsatisfiedLinkError] if it is not available for this
	 * device's ABI.
	 */
	fun ensureLoaded() {
		if (loaded) return
		synchronized(this) {
			if (!loaded) {
				System.loadLibrary(NAME)
				loaded = true
			}
		}
	}
}
