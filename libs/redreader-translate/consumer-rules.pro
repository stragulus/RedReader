# JNI: the native methods (and the classes declaring them) are looked up by name from the .so, so
# they must be neither removed, renamed, nor merged into other classes.
-keepclasseswithmembers,includedescriptorclasses class org.quantumbadger.redreader.translate.** {
	native <methods>;
}
# Instantiated from native code via ThrowNew(cls, message)
-keep class org.quantumbadger.redreader.translate.TranslationException {
	<init>(java.lang.String);
}
