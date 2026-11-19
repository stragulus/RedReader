// JNI bridge for redreader-translate: Bergamot translation + CLD2 language detection.
//
// Strings cross the JNI boundary as UTF-8 byte arrays rather than jstring, because
// GetStringUTFChars/NewStringUTF use "modified UTF-8", which mangles characters outside the
// BMP (e.g. emoji, which are very common in Reddit comments).

#include <jni.h>

#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "common/logging.h"
#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"
#include "translator/translation_model.h"

#include "compact_lang_det.h"
#include "encodings.h"

using marian::bergamot::BlockingService;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

namespace {

// BlockingService is not thread-safe, and marian's loggers are process-global (creating two
// services at the same time breaks logger registration), so there is a single service shared by
// all engines, guarded by a mutex. The service also holds the translation cache, which is keyed
// by model, so sharing it between engines is fine.
std::mutex gServiceMutex;
std::unique_ptr<BlockingService> gService;

BlockingService& service() {
	if(!gService) {
		BlockingService::Config config;
		config.cacheSize = 2048;
		config.logger.level = "off";
		gService = std::make_unique<BlockingService>(config);
	}
	return *gService;
}

struct Engine {
	// One model for a direct translation, or two for pivoting (e.g. es->en, en->de).
	std::vector<std::shared_ptr<TranslationModel>> models;
};

void throwJava(JNIEnv* env, const char* clazz, const std::string& message) {
	if(env->ExceptionCheck()) {
		return;
	}
	jclass cls = env->FindClass(clazz);
	if(cls != nullptr) {
		env->ThrowNew(cls, message.c_str());
	}
}

std::string toStdString(JNIEnv* env, jbyteArray bytes) {
	const jsize len = env->GetArrayLength(bytes);
	std::string result(static_cast<size_t>(len), '\0');
	if(len > 0) {
		env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(&result[0]));
	}
	return result;
}

jbyteArray toByteArray(JNIEnv* env, const std::string& str) {
	jbyteArray result = env->NewByteArray(static_cast<jsize>(str.size()));
	if(result == nullptr) {
		return nullptr;
	}
	env->SetByteArrayRegion(result, 0, static_cast<jsize>(str.size()),
	                        reinterpret_cast<const jbyte*>(str.data()));
	return result;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
	// Make marian's ABORT() throw a C++ exception (which we turn into a Java exception) instead of
	// calling std::abort() and killing the app, e.g. on a corrupt model file or malformed HTML.
	marian::setThrowExceptionOnAbort(true);
	return JNI_VERSION_1_6;
}

// ----------------------------------------------------------------------------------------------
// Translation
// ----------------------------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_org_quantumbadger_redreader_translate_TranslationNative_nativeCreate(
		JNIEnv* env, jclass, jobjectArray configs) {

	try {
		auto engine = std::make_unique<Engine>();
		const jsize count = env->GetArrayLength(configs);

		for(jsize i = 0; i < count; i++) {
			auto cfgBytes = static_cast<jbyteArray>(env->GetObjectArrayElement(configs, i));
			const std::string cfg = toStdString(env, cfgBytes);
			env->DeleteLocalRef(cfgBytes);

			auto options = marian::bergamot::parseOptionsFromString(cfg, /*validate=*/false);
			engine->models.push_back(std::make_shared<TranslationModel>(options));
		}

		if(engine->models.empty() || engine->models.size() > 2) {
			throw std::invalid_argument("Expected one or two models");
		}

		// Marian loads the model weights lazily on first use. Translate a word now, so that a
		// broken model fails here (as an IOException) rather than on the first real request,
		// and so that the first real request isn't slowed down by graph construction.
		{
			std::lock_guard<std::mutex> lock(gServiceMutex);
			for(const auto& model : engine->models) {
				std::vector<std::string> warmup{"Hello"};
				service().translateMultiple(model, std::move(warmup), {ResponseOptions{}});
			}
		}

		return reinterpret_cast<jlong>(engine.release());

	} catch(const std::exception& e) {
		throwJava(env, "java/io/IOException", std::string("Failed to load translation model: ") + e.what());
		return 0;
	} catch(...) {
		throwJava(env, "java/io/IOException", "Failed to load translation model");
		return 0;
	}
}

extern "C" JNIEXPORT void JNICALL
Java_org_quantumbadger_redreader_translate_TranslationNative_nativeDestroy(
		JNIEnv*, jclass, jlong handle) {

	auto* engine = reinterpret_cast<Engine*>(handle);
	if(engine == nullptr) {
		return;
	}
	// The service mutex ensures no translation using these models is in flight.
	std::lock_guard<std::mutex> lock(gServiceMutex);
	delete engine;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_quantumbadger_redreader_translate_TranslationNative_nativeTranslate(
		JNIEnv* env, jclass, jlong handle, jobjectArray inputs, jboolean html) {

	auto* engine = reinterpret_cast<Engine*>(handle);
	if(engine == nullptr) {
		throwJava(env, "java/lang/IllegalStateException", "TranslationEngine is closed");
		return nullptr;
	}

	std::vector<Response> responses;
	try {
		// Inside the try block: no C++ exception (e.g. std::bad_alloc) may cross the JNI
		// boundary
		const jsize count = env->GetArrayLength(inputs);
		std::vector<std::string> sources;
		sources.reserve(static_cast<size_t>(count));
		for(jsize i = 0; i < count; i++) {
			auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(inputs, i));
			sources.push_back(toStdString(env, bytes));
			env->DeleteLocalRef(bytes);
		}

		ResponseOptions opts;
		opts.HTML = html == JNI_TRUE;
		opts.qualityScores = false;
		opts.alignment = false;
		opts.sentenceMappings = false;
		const std::vector<ResponseOptions> responseOptions(sources.size(), opts);

		std::lock_guard<std::mutex> lock(gServiceMutex);
		if(engine->models.size() == 1) {
			responses = service().translateMultiple(engine->models[0], std::move(sources), responseOptions);
		} else {
			responses = service().pivotMultiple(
					engine->models[0], engine->models[1], std::move(sources), responseOptions);
		}
	} catch(const std::exception& e) {
		throwJava(env, "org/quantumbadger/redreader/translate/TranslationException", e.what());
		return nullptr;
	} catch(...) {
		throwJava(env, "org/quantumbadger/redreader/translate/TranslationException", "Unknown native error");
		return nullptr;
	}

	jclass byteArrayClass = env->FindClass("[B");
	jobjectArray result = env->NewObjectArray(static_cast<jsize>(responses.size()), byteArrayClass, nullptr);
	if(result == nullptr) {
		return nullptr;
	}
	for(size_t i = 0; i < responses.size(); i++) {
		jbyteArray bytes = toByteArray(env, responses[i].target.text);
		if(bytes == nullptr) {
			return nullptr;
		}
		env->SetObjectArrayElement(result, static_cast<jsize>(i), bytes);
		env->DeleteLocalRef(bytes);
	}
	return result;
}

// ----------------------------------------------------------------------------------------------
// Language detection
// ----------------------------------------------------------------------------------------------

// Returns the CLD2 language code of the top language, and fills `out` with:
//   out[0] = percent of text in the top language (0..100)
//   out[1] = 1 if CLD2 considers the result reliable
//   out[2] = number of text bytes that were actually scored (after stripping tags, etc)
//   out[3] = normalized score of the top language * 1000
extern "C" JNIEXPORT jstring JNICALL
Java_org_quantumbadger_redreader_translate_LanguageDetector_nativeDetect(
		JNIEnv* env, jclass, jbyteArray utf8, jboolean isPlainText, jintArray out) {

	std::string text;
	try {
		text = toStdString(env, utf8);
	} catch(...) {
		throwJava(env, "java/lang/OutOfMemoryError", "Language detection: out of memory");
		return nullptr;
	}

	CLD2::CLDHints hints = {nullptr, nullptr, CLD2::UNKNOWN_ENCODING, CLD2::UNKNOWN_LANGUAGE};
	CLD2::Language language3[3];
	int percent3[3];
	double normalizedScore3[3];
	int textBytes = 0;
	bool isReliable = false;
	int validPrefixBytes = 0;

	const CLD2::Language lang = CLD2::ExtDetectLanguageSummaryCheckUTF8(
			text.data(),
			static_cast<int>(text.size()),
			isPlainText == JNI_TRUE,
			&hints,
			0,
			language3,
			percent3,
			normalizedScore3,
			nullptr,
			&textBytes,
			&isReliable,
			&validPrefixBytes);

	// Note: `lang` is the summary language, which can differ from language3[0] (e.g. it is
	// UNKNOWN_LANGUAGE if the text is too short or unreliable). We use language3[0] and report
	// reliability separately, so callers can apply their own policy.
	(void) lang;

	const jint values[4] = {
			percent3[0],
			isReliable ? 1 : 0,
			textBytes,
			static_cast<jint>(normalizedScore3[0] * 1000.0)};
	env->SetIntArrayRegion(out, 0, 4, values);

	return env->NewStringUTF(CLD2::LanguageCode(language3[0]));
}
