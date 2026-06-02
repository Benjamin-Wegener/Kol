#include <jni.h>

#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "llama-android", __VA_ARGS__)

struct NativeLlamaModel {
    llama_model * model;
    int n_gpu_layers;
    int seed;
};

static std::string generate_text(
        NativeLlamaModel * handle,
        const std::string & prompt,
        float temp,
        int top_k,
        float top_p,
        int max_tokens) {
    __android_log_print(ANDROID_LOG_ERROR, "llama-android", "generate_text start prompt_len=%zu temp=%.3f top_k=%d top_p=%.3f max_tokens=%d",
                        prompt.size(), temp, top_k, top_p, max_tokens);
    const llama_vocab * vocab = llama_model_get_vocab(handle->model);

    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) {
        return {};
    }

    std::vector<llama_token> prompt_tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "llama-android", "tokenization failed");
        return {};
    }
    __android_log_print(ANDROID_LOG_ERROR, "llama-android", "prompt_tokens=%d", n_prompt);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_prompt + max_tokens + 64;
    ctx_params.n_batch = n_prompt;
    ctx_params.no_perf = true;

    llama_context * ctx = llama_init_from_model(handle->model, ctx_params);
    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "llama-android", "llama_init_from_model failed");
        return {};
    }

    llama_sampler * smpl = llama_sampler_init_greedy();
    __android_log_print(ANDROID_LOG_ERROR, "llama-android", "using greedy sampler");

    std::string result;
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    if (llama_model_has_encoder(handle->model)) {
        if (llama_encode(ctx, batch) != 0) {
            llama_sampler_free(smpl);
            llama_free(ctx);
            return {};
        }

        llama_token decoder_start_token_id = llama_model_decoder_start_token(handle->model);
        if (decoder_start_token_id == LLAMA_TOKEN_NULL) {
            decoder_start_token_id = llama_vocab_bos(vocab);
        }
        batch = llama_batch_get_one(&decoder_start_token_id, 1);
    }

    for (int n_pos = 0; n_pos + batch.n_tokens < n_prompt + max_tokens; ) {
        if (llama_decode(ctx, batch) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "llama-android", "llama_decode failed at n_pos=%d batch=%d", n_pos, batch.n_tokens);
            break;
        }

        n_pos += batch.n_tokens;
        llama_token token = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            __android_log_print(ANDROID_LOG_ERROR, "llama-android", "eog token reached at n_pos=%d", n_pos);
            break;
        }

        char buf[256];
        const int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "llama-android", "token_to_piece failed for token=%d", (int) token);
            break;
        }
        result.append(buf, n);
        __android_log_print(ANDROID_LOG_ERROR, "llama-android", "token=%d piece_len=%d result_len=%zu", (int) token, n, result.size());
        batch = llama_batch_get_one(&token, 1);
    }

    llama_sampler_free(smpl);
    llama_free(ctx);
    __android_log_print(ANDROID_LOG_ERROR, "llama-android", "generate_text done result_len=%zu", result.size());
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_voiceassistant_llm_LlamaInference_llamaLoadModel(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring model_path,
        jint n_gpu_layers,
        jint seed) {
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        return 0;
    }

    ggml_backend_load_all();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    llama_model * model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (model == nullptr) {
        LOGE("failed to load model");
        return 0;
    }

    auto * handle = new NativeLlamaModel{model, n_gpu_layers, seed};
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_voiceassistant_llm_LlamaInference_llamaGenerateText(
        JNIEnv * env,
        jobject /*thiz*/,
        jlong model_ptr,
        jstring prompt,
        jfloat temp,
        jint top_k,
        jfloat top_p,
        jfloat /*presence_penalty*/,
        jint max_tokens) {
    auto * handle = reinterpret_cast<NativeLlamaModel *>(model_ptr);
    if (handle == nullptr || handle->model == nullptr) {
        return env->NewStringUTF("");
    }

    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) {
        return env->NewStringUTF("");
    }

    std::string result = generate_text(
            handle,
            prompt_chars,
            temp,
            top_k,
            top_p,
            max_tokens);

    env->ReleaseStringUTFChars(prompt, prompt_chars);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiceassistant_llm_LlamaInference_llamaFreeModel(
        JNIEnv * /*env*/,
        jobject /*thiz*/,
        jlong model_ptr) {
    auto * handle = reinterpret_cast<NativeLlamaModel *>(model_ptr);
    if (handle == nullptr) {
        return;
    }

    if (handle->model != nullptr) {
        llama_model_free(handle->model);
    }
    delete handle;
}
