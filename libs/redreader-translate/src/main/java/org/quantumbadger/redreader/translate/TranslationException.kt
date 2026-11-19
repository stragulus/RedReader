package org.quantumbadger.redreader.translate

/**
 * Thrown when the native translation engine fails, e.g. on malformed HTML input.
 *
 * Note: this class is instantiated from native code; its (String) constructor must be kept.
 */
class TranslationException(message: String) : Exception(message)
