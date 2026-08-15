#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <cstring>
#include <dirent.h>
#include <errno.h>
#include <iomanip>
#include <cmath>
#include <sched.h>
#include <string>
#include <unistd.h>
#include <sampling.h>
#include <speculative.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "ggml.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
// Decode (GEMV, memory-bandwidth-bound) wants FEW threads: 7 threads measured
// 4.07 t/s vs 14.2 t/s at 4 threads on the 1.2B — bus contention + sync
// overhead dominate. Prefill (GEMM, compute-bound) wants MANY: 7 threads
// measured 50.8 vs 45.7 t/s at 4 threads. Split them.
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_BATCH_MAX     = 8;
constexpr int   N_THREADS_HEADROOM      = 1;

constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

// KV cache quantization: the 1.2B decode at ~14 tok/s reads ~9.2 GB/s (46% of
// LPDDR4X nominal) — compute/bandwidth-mixed, not pure-bandwidth-bound like the
// 230M. Quantizing the attention K/V cache to Q8_0 halves KV traffic and frees
// bandwidth for the weight reads that dominate decode.
static ggml_type g_cache_type_k = GGML_TYPE_Q8_0;
static ggml_type g_cache_type_v = GGML_TYPE_Q8_0;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;

// Speculative decoding state (draft model): the 230M drafts K tokens, the 1.2B
// target verifies them in ONE batched forward pass (lossless, DSpark-style).
static llama_model                      * g_model_dft = nullptr;
static llama_context                    * g_context_dft = nullptr;
static common_speculative               * g_spec = nullptr;
static bool                              g_spec_active = false;
static int                               g_spec_n_max = 5;   // draft length
static std::vector<llama_token>          g_spec_queue;        // accepted tokens pending emission
static llama_token                       g_spec_id_last = 0;  // last emitted token
static llama_tokens                      g_spec_prompt;       // full target-context token list
static int                               g_spec_n_past = 0;
static llama_tokens                      g_system_tokens;     // system prompt tokens (draft mirror)
// Spec stats (for telemetry / benchmarks)
static long                              g_spec_n_accept = 0;
static long                              g_spec_n_drafted = 0;
static long                              g_spec_rounds = 0;

// ARM optimization state: when true, inference threads are pinned to the
// big (Cortex-A78) cores via sched_setaffinity (OpenMP-compatible).
static bool                              g_pin_big_cores = false;

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setBigCorePinningNative(JNIEnv * /*env*/, jobject /*unused*/, jboolean enabled) {
    g_pin_big_cores = (enabled == JNI_TRUE);
    LOGi("Big-core pinning %s", g_pin_big_cores ? "ENABLED" : "disabled");
}

/**
 * Loads a draft model for speculative decoding (230M + 1.2B target).
 * Safe: any failure leaves the engine in the normal single-model path.
 * Returns 0 on success, non-zero on failure (caller may ignore).
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setDraftModelNative(JNIEnv *env, jobject /*unused*/, jstring jmodel_path) {
    if (g_spec_active) {
        return 0;
    }
    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGi("%s: Loading draft model: %s", __func__, model_path);

    llama_model_params mparams = llama_model_default_params();
    auto *model_dft = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model_dft) {
        LOGe("%s: failed to load draft model", __func__);
        return 1;
    }

    // Draft context: same n_ctx as the target (4096) — the draft mirrors the
    // FULL conversation, so a smaller context would fail on long sessions.
    // Same n_rs_seq so partial KV trims work on the hybrid arch.
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = DEFAULT_CONTEXT_SIZE;
    cparams.n_batch = BATCH_SIZE;
    cparams.n_ubatch = BATCH_SIZE;
    cparams.n_threads = 2;
    cparams.n_threads_batch = 2;
    cparams.type_k = g_cache_type_k;
    cparams.type_v = g_cache_type_v;
    cparams.n_rs_seq = std::min(256u, cparams.n_ctx - 2);
    auto *ctx_dft = llama_init_from_model(model_dft, cparams);
    if (!ctx_dft) {
        LOGe("%s: failed to init draft context", __func__);
        llama_model_free(model_dft);
        return 2;
    }

    common_params_speculative sp_params;
    sp_params.types = { COMMON_SPECULATIVE_TYPE_DRAFT_SIMPLE };
    sp_params.draft.n_max = g_spec_n_max;
    sp_params.draft.ctx_dft = ctx_dft;
    sp_params.draft.ctx_tgt = g_context;

    try {
        g_spec = common_speculative_init(sp_params, 1);
    } catch (const std::exception & e) {
        LOGe("%s: spec init failed: %s", __func__, e.what());
        llama_free(ctx_dft);
        llama_model_free(model_dft);
        return 3;
    }
    if (!g_spec) {
        LOGe("%s: spec init returned null", __func__);
        llama_free(ctx_dft);
        llama_model_free(model_dft);
        return 4;
    }

    g_model_dft = model_dft;
    g_context_dft = ctx_dft;
    g_spec_active = true;
    LOGi("%s: speculative decoding ACTIVE (draft n_max=%d)", __func__, g_spec_n_max);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

/** Forward declaration: pins process threads to the big (A78) cores. */
static void pin_threads_to_big_cores();

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup: decode threads (GEMV, bus-bound) stay low;
    // batch/prefill threads (GEMM, compute-bound) go high.
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    const int n_threads_batch = std::max(N_THREADS_MIN, std::min(N_THREADS_BATCH_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads (decode), %d threads (prefill)", __func__, n_threads, n_threads_batch);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads_batch;
    // Q8_0 KV cache: halves attention KV traffic on a decode that is only
    // ~46% bandwidth-saturated — frees bandwidth for the dominant weight reads.
    ctx_params.type_k = g_cache_type_k;
    ctx_params.type_v = g_cache_type_v;
    // LFM2.5 is a Gated DeltaNet hybrid (linear attention): partial KV-cache
    // erasure (prompt-prefix reuse) requires per-token rollback snapshots in
    // the recurrent state. Default n_rs_seq=0 makes llama_memory_seq_rm fail
    // for ANY partial trim, silently killing prefix caching.
    // MUST be < n_ubatch: the hybrid split keeps the trailing (1+n_rs_seq)
    // tokens in one ubatch (split_equal n_keep_tail), so 256 (257 <= 512)
    // is the max that fits. Recurrent buffer scales as (1+n_rs_seq): ~40 MiB.
    // Clamp to n_ctx-2: small bench contexts clamp n_ubatch to n_ctx, and
    // split_equal asserts n_ubatch > n_keep_tail (so n_rs_seq+1 < n_ctx).
    ctx_params.n_rs_seq = std::min(256u, (uint32_t) std::max(0, n_ctx - 2));
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
        return context;
    }

    // ARM optimization: pin inference threads to the big (A78) cores when enabled.
    // Exynos 1330: cores 6-7 are Cortex-A78 @2.4GHz, cores 0-5 are A55 @2.0GHz.
    // This build uses GGML_OPENMP=ON, so the ggml threadpool API is NOT usable
    // (threadpool attach is the non-OpenMP execution path). Instead we set CPU
    // affinity directly on the process threads: OpenMP worker threads inherit
    // the creator's affinity mask, so pinning the calling thread (and all
    // existing process threads) to the big cores pins the compute.
    if (g_pin_big_cores) {
        pin_threads_to_big_cores();
    }

    return context;
}

/** Pin every thread of this process to the big (A78) cores via sched_setaffinity. */
static void pin_threads_to_big_cores() {
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(6, &set);  // A78 core 6
    CPU_SET(7, &set);  // A78 core 7

    // Pin the calling thread first (OpenMP workers inherit this mask on spawn).
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        LOGe("%s: sched_setaffinity(self) failed: %s", __func__, strerror(errno));
        return;
    }
    LOGi("%s: Pinned process threads to big cores (6,7)", __func__);

    // Also pin any already-existing threads (e.g. OpenMP workers from an
    // earlier unpinned run) so a config toggle mid-process still applies.
    DIR *dir = opendir("/proc/self/task");
    if (!dir) {
        LOGw("%s: cannot enumerate threads: %s", __func__, strerror(errno));
        return;
    }
    struct dirent *ent;
    while ((ent = readdir(dir)) != nullptr) {
        if (ent->d_name[0] == '.') continue;
        pid_t tid = (pid_t) atol(ent->d_name);
        if (tid <= 0 || tid == gettid()) continue;
        sched_setaffinity(tid, sizeof(set), &set);
    }
    closedir(dir);
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    // Liquid's recommended settings for LFM2.5-1.2B-Instruct:
    // temperature=0.1, top_k=50, repetition_penalty=1.05 (docs.liquid.ai).
    // Defaults we deliberately keep: top_p (0.95) and min_p (0.05) stay at
    // llama.cpp defaults, which matched the tool-call tuning passes.
    sparams.temp = temp;
    sparams.top_k = 50;
    sparams.penalty_repeat = 1.05f;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

// Prompt-prefix cache: the last full prompt's tokens, so the next turn can
// reuse the KV cache for the shared prefix and decode ONLY the new suffix.
// (llama-server's cache_prompt trick, native: multi-turn TTFT drops from
// full-history prefill to just-the-new-message prefill.)
static std::vector<llama_token> g_cached_prompt_tokens;
static bool g_prompt_cache_valid = false;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;
    g_cached_prompt_tokens.clear();
    g_prompt_cache_valid = false;

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    // KV positions moved — the cached prefix no longer matches.
    g_cached_prompt_tokens.clear();
    g_prompt_cache_valid = false;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context
        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            shift_context();
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }
    // Remember for speculative decoding: the draft context mirrors the target
    // KV layout [system @ 0..S][conversation @ S..], so we must replay the
    // system tokens on the draft too.
    g_system_tokens = system_tokens;

    // Handle context overflow
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);

    // The Kotlin harness (Lfm2Format) renders the ENTIRE conversation —
    // including the <|startoftext|> sentinel — and passes it as one prompt.
    // Detect that and decode it RAW: re-wrapping a fully-formatted prompt
    // inside <|im_start|>user …<|im_end|> produces a closed
    // "assistant\n<|im_end|>" turn, which strictly template-trained models
    // (LFM2.5-2.6B) answer with an immediate EOG → zero tokens.
    const bool already_formatted = strncmp(user_prompt, "<|startoftext|>", 15) == 0;
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template && !already_formatted) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Ensure user prompt doesn't exceed the context size by truncating if necessary.
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }

    // Prefix caching: the full formatted prompt starts with the same
    // [system + tools + history] every turn, so the KV cache already holds
    // that prefix from the previous turn. Decode ONLY the suffix.
    // (KV positions: prompt token i lives at system_prompt_position + i.)
    int skip = 0;
    if (g_prompt_cache_valid) {
        const size_t max_common = std::min(g_cached_prompt_tokens.size(), user_tokens.size());
        while (skip < (int) max_common && g_cached_prompt_tokens[skip] == user_tokens[skip]) {
            skip++;
        }
        if (skip > 0) {
            LOGi("%s: Reusing %d cached prompt tokens (skip prefill)", __func__, skip);
        }
    }

    // The batch allocator requires the new batch to start exactly at
    // seq_pos_max+1. If the reused prefix ends before the previous decode
    // did (partial match, e.g. the assistant reply re-tokenizes differently
    // than it was generated), trim the divergent KV tail so the suffix is
    // contiguous again. When nothing matches, this resets to right after
    // the system prompt — the system KV survives, everything else re-decodes.
    if (skip < (int) g_cached_prompt_tokens.size()) {
        const llama_pos trim_from = system_prompt_position + skip;
        const bool trimmed = llama_memory_seq_rm(llama_get_memory(g_context), 0, trim_from, -1);
        if (trimmed) {
            LOGi("%s: trimmed KV from %d", __func__, trim_from);
        } else {
            // Rollback window exceeded (recurrent snapshots are bounded by
            // n_rs_seq) or cache not erasable: fall back to a full re-decode
            // of the entire prompt from position 0. Correct, just no reuse.
            LOGw("%s: trim failed, full re-decode", __func__);
            llama_memory_clear(llama_get_memory(g_context), false);
            skip = 0;
            system_prompt_position = 0;
        }
        g_prompt_cache_valid = false;
    }

    // Decode the suffix (if any) at its absolute position.
    const llama_pos suffix_start = system_prompt_position + skip;
    if (skip < user_prompt_size) {
        llama_tokens suffix(user_tokens.begin() + skip, user_tokens.end());
        if (decode_tokens_in_batches(g_context, g_batch, suffix, suffix_start, true)) {
            LOGe("%s: llama_decode() failed!", __func__);
            return 2;
        }
    }

    // Update position
    current_position = system_prompt_position + user_prompt_size;
    // NOTE: do NOT add user_prompt_size again — current_position already
    // includes it. Doubling it inflated the effective cap by the whole prompt
    // length (e.g. 512 requested ≈ 2500 actual), which is why the 2.6B
    // "ran for a minute" past its token budget.
    stop_generation_position = current_position + n_predict;

    // Remember this prompt for the next turn's prefix match.
    g_cached_prompt_tokens = user_tokens;
    g_prompt_cache_valid = true;

    // Speculative decoding setup: mirror the prompt on the draft context and
    // tell the speculator where generation starts. The draft KV must match the
    // target layout [system @ 0..S][conversation @ S..], so clear + replay the
    // FULL prompt on the draft (230M prefill is ~4x faster than the 1.2B).
    if (g_spec_active) {
        llama_memory_clear(llama_get_memory(g_context_dft), false);
        // Mirror system + conversation on the draft context.
        // NOTE: keep the LAST prompt token separate (id_last, "in flight") —
        // it is decoded as the first token of the first speculative batch.
        const size_t n_mirror = user_tokens.size() - 1;
        llama_batch batch_dft = llama_batch_init(g_system_tokens.size() + n_mirror + 8, 0, 1);
        for (size_t i = 0; i < g_system_tokens.size(); ++i) {
            common_batch_add(batch_dft, g_system_tokens[i], (llama_pos) i, {0}, false);
        }
        for (size_t i = 0; i < n_mirror; ++i) {
            common_batch_add(batch_dft, user_tokens[i], (llama_pos) (g_system_tokens.size() + i), {0}, false);
        }
        const int ret_dft = llama_decode(g_context_dft, batch_dft);
        llama_batch_free(batch_dft);
        if (ret_dft != 0) {
            LOGw("%s: draft prompt mirror failed (%d), disabling speculation", __func__, ret_dft);
            g_spec_active = false;
        } else {
            g_spec_prompt.clear();
            g_spec_prompt.insert(g_spec_prompt.end(), g_system_tokens.begin(), g_system_tokens.end());
            g_spec_prompt.insert(g_spec_prompt.end(), user_tokens.begin(), user_tokens.begin() + n_mirror);
            // The target prompt is also decoded WITHOUT its last token; that
            // token becomes id_last, decoded as the first spec batch token.
            llama_memory_clear(llama_get_memory(g_context), false);
            llama_batch batch_tgt = llama_batch_init(g_system_tokens.size() + n_mirror + 8, 0, 1);
            for (size_t i = 0; i < g_system_tokens.size(); ++i) {
                common_batch_add(batch_tgt, g_system_tokens[i], (llama_pos) i, {0}, false);
            }
            for (size_t i = 0; i < n_mirror; ++i) {
                common_batch_add(batch_tgt, user_tokens[i], (llama_pos) (g_system_tokens.size() + i), {0}, false);
            }
            const int ret_tgt = llama_decode(g_context, batch_tgt);
            llama_batch_free(batch_tgt);
            if (ret_tgt != 0) {
                LOGw("%s: target prompt mirror failed (%d), disabling speculation", __func__, ret_tgt);
                g_spec_active = false;
            } else {
                g_spec_n_past = (int) g_spec_prompt.size();
                current_position = g_spec_n_past;
                g_spec_id_last = user_tokens.back();
                g_spec_queue.clear();
                // prefix cache tracks exactly the KV contents (id_last excluded)
                g_cached_prompt_tokens = g_spec_prompt;
                common_speculative_begin(g_spec, 0, g_spec_prompt);
                LOGi("%s: spec ready (n_past=%d)", __func__, g_spec_n_past);
            }
        }
    }
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Infinite text generation via context shifting
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
        if (g_spec_active) {
            // The draft KV must mirror the shift; simplest correct fallback is
            // to drop speculation for the remainder of this generation.
            g_spec_active = false;
            g_spec_queue.clear();
        }
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }

    // ---- emit one queued token if available (speculative round already ran) ----
    if (!g_spec_queue.empty()) {
        const llama_token id = g_spec_queue.front();
        g_spec_queue.erase(g_spec_queue.begin());

        // NOTE: current_position already accounts for this token; do not
        // advance. The prefix cache was already updated by the round that
        // produced this token (bonus excluded - it is still in flight).
        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id)) {
            LOGd("id: %d,\tIS EOG!\\nSTOP.", id);
            chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
            return nullptr;
        }
        auto new_token_chars = common_token_to_piece(g_context, id);
        cached_token_chars += new_token_chars;
        jstring result = nullptr;
        if (is_valid_utf8(cached_token_chars.c_str())) {
            result = env->NewStringUTF(cached_token_chars.c_str());
            assistant_ss << cached_token_chars;
            cached_token_chars.clear();
        } else {
            result = env->NewStringUTF("");
        }
        return result;
    }

    // ---- normal single-token path (no speculation active) ----
    if (!g_spec_active) {
        const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
        common_sampler_accept(g_sampler, new_token_id, true);

        common_batch_clear(g_batch);
        common_batch_add(g_batch, new_token_id, current_position, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            LOGe("%s: llama_decode() failed for generated token", __func__);
            return nullptr;
        }
        g_cached_prompt_tokens.push_back(new_token_id);
        current_position++;

        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
            LOGd("id: %d,\tIS EOG!\\nSTOP.", new_token_id);
            chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
            return nullptr;
        }
        auto new_token_chars = common_token_to_piece(g_context, new_token_id);
        cached_token_chars += new_token_chars;
        jstring result = nullptr;
        if (is_valid_utf8(cached_token_chars.c_str())) {
            result = env->NewStringUTF(cached_token_chars.c_str());
            LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());
            assistant_ss << cached_token_chars;
            cached_token_chars.clear();
        } else {
            LOGv("id: %d,\tappend to cache", new_token_id);
            result = env->NewStringUTF("");
        }
        return result;
    }

    // ---- speculative round: draft K tokens, verify in ONE target pass ----
    const int n_draft_max = std::min(g_spec_n_max, (int) (stop_generation_position - current_position - 2));
    if (n_draft_max <= 0) {
        return nullptr;
    }

    llama_tokens draft;
    common_speculative_draft_params dparams = {
        /* .drafting = */ true,
        /* .n_max    = */ n_draft_max,
        /* .n_past   = */ current_position,
        /* .id_last  = */ g_spec_id_last,
        /* .prompt   = */ &g_spec_prompt,
        /* .result   = */ &draft,
    };
    common_speculative_get_draft_params(g_spec, 0) = dparams;
    common_speculative_draft(g_spec);

    // Roll the draft context back to the pre-draft position: draft() already
    // advanced the draft KV, and process() below must re-decode the target
    // batch starting at seq_pos_max+1 (contiguity requirement).
    llama_memory_seq_rm(llama_get_memory(g_context_dft), 0, current_position, -1);

    // Target verifies [id_last, draft0..draftN-1] in one batched forward pass.
    // id_last lives at current_position (in flight, not in KV); drafts follow.
    common_batch_clear(g_batch);
    common_batch_add(g_batch, g_spec_id_last, current_position, {0}, true);
    for (size_t i = 0; i < draft.size(); ++i) {
        common_batch_add(g_batch, draft[i], current_position + 1 + i, {0}, true);
    }
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for speculative batch", __func__);
        g_spec_active = false;
        return nullptr;
    }

    // Feed the batch to the draft impl (keeps draft KV in sync with target).
    if (!common_speculative_process(g_spec, g_batch)) {
        LOGe("%s: speculative process failed, disabling", __func__);
        g_spec_active = false;
        return nullptr;
    }

    // Rejection-sample: accept the longest draft prefix the target agrees with.
    auto ids = common_sampler_sample_and_accept_n(g_sampler, g_context, draft);
    if (ids.empty()) {
        g_spec_active = false;
        return nullptr;
    }
    common_speculative_accept(g_spec, 0, (uint16_t) (ids.size() - 1));

    // Commit accepted tokens: n_past advances by (ids.size() - 1) draft tokens,
    // and the last id is the bonus target token (stays "in flight" as id_last).
    g_spec_prompt.push_back(g_spec_id_last);           // old id_last, now decoded in KV
    for (size_t i = 0; i + 1 < ids.size(); ++i) {
        g_spec_prompt.push_back(ids[i]);               // accepted drafts
    }
    current_position += (int) ids.size();
    g_spec_id_last = ids.back();                       // bonus token, in flight

    // Trim the unaccepted draft tail from both contexts.
    llama_memory_seq_rm(llama_get_memory(g_context), 0, current_position, -1);
    if (g_context_dft) {
        llama_memory_seq_rm(llama_get_memory(g_context_dft), 0, current_position, -1);
    }

    // Queue everything except the first token (which we return now).
    for (size_t i = 1; i < ids.size(); ++i) {
        g_spec_queue.push_back(ids[i]);
    }

    const llama_token id0 = ids[0];
    // Prefix cache mirrors the KV: the bonus token (ids.back()) is in flight
    // (trimmed from the KV), so it is NOT cached. Accepted drafts are.
    for (size_t i = 0; i + 1 < ids.size(); ++i) {
        g_cached_prompt_tokens.push_back(ids[i]);
    }
    // Spec stats
    g_spec_n_accept += (long) ids.size() - 1;
    g_spec_n_drafted += (long) draft.size();
    g_spec_rounds++;
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id0)) {
        LOGd("id: %d,\tIS EOG!\\nSTOP.", id0);
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }
    auto new_token_chars = common_token_to_piece(g_context, id0);
    cached_token_chars += new_token_chars;
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGi("spec: accepted %d/%d draft tokens", (int) ids.size() - 1, (int) draft.size());
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        result = env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_getSpecStatsNative(JNIEnv *env, jobject /*unused*/) {
    std::ostringstream ss;
    ss << (g_spec_active ? "true" : "false") << "|" << g_spec_rounds << "|" << g_spec_n_accept << "|" << g_spec_n_drafted;
    return env->NewStringUTF(ss.str().c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free speculative-decoding resources (draft model + context).
    if (g_spec) {
        common_speculative_free(g_spec);
        g_spec = nullptr;
    }
    if (g_context_dft) {
        llama_free(g_context_dft);
        g_context_dft = nullptr;
    }
    if (g_model_dft) {
        llama_model_free(g_model_dft);
        g_model_dft = nullptr;
    }
    g_spec_active = false;
    g_spec_queue.clear();

    // Free up resources
    common_sampler_free(g_sampler);
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    llama_free(g_context);
    llama_model_free(g_model);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
