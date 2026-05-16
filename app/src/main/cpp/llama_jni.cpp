#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <functional>

#include "llama.h"
#include "ggml.h"

#define TAG "GemmaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Global state ──────────────────────────────────────────────────────────────

struct LlamaState {
    llama_model*   model   = nullptr;
    llama_context* ctx     = nullptr;
    std::atomic<bool> stop_flag{false};
};

static LlamaState g_state;

// ── Helpers ───────────────────────────────────────────────────────────────────

static std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// ── JNI exports ───────────────────────────────────────────────────────────────

extern "C" {

// Returns nullptr on success, error string on failure.
JNIEXPORT jstring JNICALL
Java_com_example_gemmaapp_LlamaEngine_nativeLoad(
        JNIEnv* env, jobject /*thiz*/,
        jstring modelPath,
        jint    nCtx,
        jint    nGpuLayers)
{
    if (g_state.model) {
        llama_free(g_state.ctx);
        llama_model_free(g_state.model);
        g_state.ctx   = nullptr;
        g_state.model = nullptr;
    }

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    // n_gpu_layers > 0  → offload that many transformer layers to Vulkan GPU.
    // Pass -1 from Kotlin to offload everything.
    mparams.n_gpu_layers = nGpuLayers;

    std::string path = jstring_to_std(env, modelPath);
    LOGI("Loading model: %s  gpu_layers=%d", path.c_str(), nGpuLayers);

    g_state.model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_state.model) {
        std::string err = "Failed to load model: " + path;
        LOGE("%s", err.c_str());
        return env->NewStringUTF(err.c_str());
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx    = static_cast<uint32_t>(nCtx);
    cparams.n_batch  = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;

    g_state.ctx = llama_init_from_model(g_state.model, cparams);
    if (!g_state.ctx) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
        return env->NewStringUTF("Failed to create llama context");
    }

    LOGI("Model loaded OK. ctx_size=%d", nCtx);
    return nullptr; // success
}

JNIEXPORT void JNICALL
Java_com_example_gemmaapp_LlamaEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_state.stop_flag.store(true);
}

// Generates tokens and calls back into Kotlin for each piece.
// tokenCallback: (String) -> Unit
JNIEXPORT jstring JNICALL
Java_com_example_gemmaapp_LlamaEngine_nativeGenerate(
        JNIEnv*  env,
        jobject  /*thiz*/,
        jstring  jPrompt,
        jint     maxTokens,
        jfloat   temperature,
        jfloat   topP,
        jobject  tokenCallback)
{
    if (!g_state.model || !g_state.ctx) {
        return env->NewStringUTF("Model not loaded");
    }

    g_state.stop_flag.store(false);
    llama_context* ctx   = g_state.ctx;
    llama_model*   model = g_state.model;

    std::string prompt = jstring_to_std(env, jPrompt);

    // Tokenise prompt
    const llama_vocab* vocab = llama_model_get_vocab(model);
    const int n_prompt_tokens = -llama_tokenize(
        vocab, prompt.c_str(), (int)prompt.size(),
        nullptr, 0, /*add_special=*/true, /*parse_special=*/true);

    std::vector<llama_token> tokens_prompt(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                       tokens_prompt.data(), n_prompt_tokens,
                       true, true) < 0) {
        return env->NewStringUTF("Tokenisation failed");
    }

    llama_memory_clear(llama_get_memory(ctx), true);

    // Evaluate prompt in one batch
    llama_batch batch = llama_batch_get_one(tokens_prompt.data(), (int)tokens_prompt.size());
    if (llama_decode(ctx, batch) != 0) {
        return env->NewStringUTF("Prompt eval failed");
    }

    // Sampler chain: temp → top-p → greedy
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    // JNI callback method
    jclass   cbClass  = env->GetObjectClass(tokenCallback);
    jmethodID onToken = env->GetMethodID(cbClass, "invoke",
                                         "(Ljava/lang/Object;)Ljava/lang/Object;");

    std::string full_response;
    int n_decoded = 0;

    while (n_decoded < maxTokens && !g_state.stop_flag.load()) {
        llama_token token_id = llama_sampler_sample(smpl, ctx, -1);

        if (llama_vocab_is_eog(vocab, token_id)) break;

        char piece[256];
        int  n = llama_token_to_piece(vocab, token_id, piece, sizeof(piece), 0, false);
        if (n < 0) break;

        std::string token_str(piece, n);
        full_response += token_str;

        // Callback to Kotlin
        jstring jpiece = env->NewStringUTF(token_str.c_str());
        env->CallObjectMethod(tokenCallback, onToken, jpiece);
        env->DeleteLocalRef(jpiece);

        // Feed the new token back
        llama_batch next = llama_batch_get_one(&token_id, 1);
        if (llama_decode(ctx, next) != 0) break;

        ++n_decoded;
    }

    llama_sampler_free(smpl);
    LOGI("Generated %d tokens", n_decoded);
    return nullptr; // success
}

JNIEXPORT void JNICALL
Java_com_example_gemmaapp_LlamaEngine_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_state.ctx)   { llama_free(g_state.ctx);         g_state.ctx   = nullptr; }
    if (g_state.model) { llama_model_free(g_state.model); g_state.model = nullptr; }
    llama_backend_free();
    LOGI("Released model and backend");
}

} // extern "C"
