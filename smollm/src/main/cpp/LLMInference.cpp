#include "LLMInference.h"
#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <utility>

#define TAG "[SmolLMAndroid-Cpp]"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

void
LLMInference::loadModel(const char *model_path, float minP, float temperature, bool storeChats, long contextSize,
                        const char *chatTemplate, int nThreads, bool useMmap, bool useMlock, int topK, float topP,
                        float repeatPenalty, int repeatLastN, int seed) {
    LOGi("loading model with"
         "\n\tmodel_path = %s"
         "\n\tminP = %f"
         "\n\ttemperature = %f"
         "\n\ttopK = %d"
         "\n\ttopP = %f"
         "\n\trepeatPenalty = %f"
         "\n\trepeatLastN = %d"
         "\n\tseed = %d"
         "\n\tstoreChats = %d"
         "\n\tcontextSize = %li"
         "\n\tchatTemplate = %s"
         "\n\tnThreads = %d"
         "\n\tuseMmap = %d"
         "\n\tuseMlock = %d",
         model_path, minP, temperature, topK, topP, repeatPenalty, repeatLastN, seed, storeChats, contextSize,
         chatTemplate, nThreads, useMmap, useMlock);

    // load dynamic backends
    ggml_backend_load_all();

    // create an instance of llama_model
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = useMmap;
    model_params.use_mlock = useMlock;
    _model = llama_model_load_from_file(model_path, model_params);
    if (!_model) {
        LOGe("failed to load model from %s", model_path);
        throw std::runtime_error("loadModel() failed");
    }

    // create an instance of llama_context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_batch = contextSize;
    ctx_params.n_threads = nThreads;
    ctx_params.no_perf = true; // disable performance metrics
    _ctx = llama_init_from_model(_model, ctx_params);
    if (!_ctx) {
        LOGe("llama_new_context_with_model() returned null)");
        throw std::runtime_error("llama_new_context_with_model() returned null");
    }

    // create an instance of llama_sampler
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true; // disable performance metrics
    _sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(
        _sampler,
        llama_sampler_init_penalties(repeatLastN, repeatPenalty, 0.0f, 0.0f)
    );
    llama_sampler_chain_add(_sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(_sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(_sampler, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(
        _sampler,
        llama_sampler_init_dist(static_cast<uint32_t>(seed))
    );

    _formattedMessages = std::vector<char>(llama_n_ctx(_ctx));
    _messages.clear();
    _kvTokens.clear();
    _lastPromptTokens.clear();
    _cacheValid = false;
    _lastResponseEmpty = true;
    _response.clear();
    _cacheResponseTokens.clear();
    _batch = llama_batch{};

    if (chatTemplate == nullptr) {
        _chatTemplate = llama_model_chat_template(_model, nullptr);
    } else {
        _chatTemplate = strdup(chatTemplate);
    }
    this->_storeChats = storeChats;
}

void
LLMInference::addChatMessage(const char *message, const char *role) {
    _messages.push_back({strdup(role), strdup(message)});
}

void
LLMInference::clearChat() {
    for (llama_chat_message &message : _messages) {
        free(const_cast<char *>(message.role));
        free(const_cast<char *>(message.content));
    }
    _messages.clear();
    _resetContext();
    llama_sampler_reset(_sampler);
}

bool
LLMInference::trimToMessageCount(size_t messageCount) {
    if (!_storeChats) {
        return false;
    }
    if (messageCount > _messages.size()) {
        return false;
    }
    if (messageCount == _messages.size()) {
        return true;
    }
    if (messageCount == 0) {
        clearChat();
        return true;
    }
    if (!_cacheValid) {
        return false;
    }
    const char *lastRole = _messages[messageCount - 1].role;
    size_t      boundary = 0;
    if (strcmp(lastRole, "assistant") == 0) {
        // The cache ends with the sampled assistant response. Use the boundary
        // recorded when that turn started.
        auto boundaryIt = _messageBoundaries.find(messageCount);
        if (boundaryIt == _messageBoundaries.end()) {
            return false;
        }
        boundary = boundaryIt->second;
    } else if (strcmp(lastRole, "user") == 0) {
        // Re-render the kept conversation without the assistant header and
        // verify that it is an exact prefix of the cached tokens. This handles
        // rolling back an assistant reply while keeping the user prefix.
        try {
            std::vector<common_chat_msg> keptMessages;
            keptMessages.reserve(messageCount);
            for (size_t i = 0; i < messageCount; ++i) {
                common_chat_msg msg;
                msg.role    = _messages[i].role;
                msg.content = _messages[i].content;
                keptMessages.push_back(std::move(msg));
            }
            RenderedPrompt keptRendered = _renderPrompt(keptMessages, false);
            std::vector<llama_token> keptTokens = common_tokenize(
                llama_model_get_vocab(_model), keptRendered.text, /*add_special=*/true,
                /*parse_special=*/true);
            if (keptTokens.empty() || keptTokens.size() > _kvTokens.size() ||
                !std::equal(keptTokens.begin(), keptTokens.end(), _kvTokens.begin())) {
                return false;
            }
            boundary = keptTokens.size();
        } catch (...) {
            return false;
        }
    } else {
        return false;
    }
    if (boundary == 0 || boundary > _kvTokens.size()) {
        return false;
    }
    if (!llama_memory_seq_rm(llama_get_memory(_ctx), 0, (llama_pos) boundary, -1)) {
        LOGi("trimToMessageCount: partial KV removal failed");
        return false;
    }
    for (size_t i = messageCount; i < _messages.size(); ++i) {
        free(const_cast<char *>(_messages[i].role));
        free(const_cast<char *>(_messages[i].content));
    }
    _messages.resize(messageCount);
    _kvTokens.resize(boundary);
    // Force the token-prefix path on the next turn. The cache may contain
    // sampled assistant tokens, so the rendered-prompt fast path is not valid.
    _lastPromptTokens.clear();
    _lastResponseEmpty = false;
    _cacheValid         = true;
    _nCtxUsed           = (int) boundary;
    _response.clear();
    _cacheResponseTokens.clear();
    llama_sampler_reset(_sampler);
    _messageBoundaries.erase(_messageBoundaries.upper_bound(messageCount), _messageBoundaries.end());
    LOGi("trimToMessageCount: messages=%zu kv=%zu", messageCount, boundary);
    return true;
}

LLMInference::RenderedPrompt
LLMInference::_renderPrompt(bool addGenerationPrompt) {
    std::vector<common_chat_msg> messages;
    messages.reserve(_messages.size());
    for (const llama_chat_message& message : _messages) {
        common_chat_msg msg;
        msg.role    = message.role;
        msg.content = message.content;
        messages.push_back(std::move(msg));
    }
    return _renderPrompt(messages, addGenerationPrompt);
}

LLMInference::RenderedPrompt
LLMInference::_renderPrompt(
    const std::vector<common_chat_msg>& messages, bool addGenerationPrompt) {
    auto templates = common_chat_templates_init(_model, _chatTemplate ? _chatTemplate : "");

    common_chat_templates_inputs inputs;
    inputs.messages              = messages;
    inputs.add_generation_prompt = addGenerationPrompt;
    inputs.use_jinja             = true;
    inputs.chat_template_kwargs["tools"] = "[]";

    RenderedPrompt rendered;
    try {
        rendered.text = common_chat_templates_apply(templates.get(), inputs).prompt;
    } catch (const std::exception &e) {
        LOGe("Jinja template failed: %s — retrying with legacy renderer", e.what());
        inputs.use_jinja = false;
        inputs.chat_template_kwargs.clear();
        rendered.text    = common_chat_templates_apply(templates.get(), inputs).prompt;
        rendered.usedJinja = false;
    }
    return rendered;
}

void
LLMInference::_resetContext() {
    llama_memory_clear(llama_get_memory(_ctx), true);
    _kvTokens.clear();
    _lastPromptTokens.clear();
    _cacheValid = false;
    _lastResponseEmpty = true;
    _response.clear();
    _cacheResponseTokens.clear();
    _nCtxUsed = 0;
    _messageBoundaries.clear();
}

float
LLMInference::getResponseGenerationTime() const {
    return (float) _responseNumTokens / (_responseGenerationTime / 1e6);
}

int
LLMInference::getContextSizeUsed() const {
    return _nCtxUsed;
}

bool
LLMInference::startCompletion(const char *query) {
    llama_sampler_reset(_sampler);
    _responseGenerationTime = 0;
    _responseNumTokens = 0;
    _response.clear();
    _cacheResponseTokens.clear();

    if (_storeChats && _cacheValid) {
        _messageBoundaries[_messages.size()] = _kvTokens.size();
    }

    addChatMessage(query, "user");

    RenderedPrompt rendered = _renderPrompt(true);

    // Tokenize the whole prompt on every turn. Reusing an exact token prefix
    // (instead of comparing rendered text) is robust against templates that
    // trim or otherwise normalize whitespace in the assistant message.
    std::vector<llama_token> promptTokens = common_tokenize(
        llama_model_get_vocab(_model),
        rendered.text,
        /*add_special=*/true,
        /*parse_special=*/true
    );

    const size_t kvSize = _kvTokens.size();

    // Fast path: the previous turn ended cleanly and the conversation prefix
    // (everything up to the assistant header) is unchanged. Keep the tokens
    // the model actually generated in the KV cache and append only the
    // template's assistant terminator plus the new user turn. This avoids
    // re-prefilling the previous response when the template renders the
    // assistant text differently (e.g. trims whitespace) from the sampled
    // tokens.
    if (_storeChats && _cacheValid && !_lastResponseEmpty &&
        !_lastPromptTokens.empty() &&
        _lastPromptTokens.size() <= promptTokens.size() &&
        std::equal(_lastPromptTokens.begin(), _lastPromptTokens.end(),
                   promptTokens.begin()) &&
        _messages.size() >= 2 &&
        strcmp(_messages[_messages.size() - 1].role, "user") == 0 &&
        strcmp(_messages[_messages.size() - 2].role, "assistant") == 0) {
        static const std::string kSentinel = "__SMOLCHAT_ASSISTANT_SENTINEL__";

        std::vector<common_chat_msg> baseMessages;
        baseMessages.reserve(_messages.size() - 1);
        for (size_t i = 0; i + 1 < _messages.size(); i++) {
            common_chat_msg msg;
            msg.role    = _messages[i].role;
            msg.content = _messages[i].content;
            baseMessages.push_back(std::move(msg));
        }
        baseMessages.back().content = kSentinel;

        RenderedPrompt prev = _renderPrompt(baseMessages, false);

        std::vector<common_chat_msg> nextMessages = baseMessages;
        common_chat_msg userMessage;
        userMessage.role    = _messages.back().role;
        userMessage.content = _messages.back().content;
        nextMessages.push_back(std::move(userMessage));

        RenderedPrompt next = _renderPrompt(nextMessages, true);

        const size_t sentinelPos = prev.text.rfind(kSentinel);
        const bool jsonGlue = sentinelPos != std::string::npos &&
            [&] {
                const std::string tail = prev.text.substr(sentinelPos + kSentinel.size());
                return tail.find('"') != std::string::npos ||
                       tail.find('}') != std::string::npos ||
                       tail.find(']') != std::string::npos;
            }();
        const bool sentinelOk =
            sentinelPos != std::string::npos &&
            prev.text.find(kSentinel) == sentinelPos &&
            !jsonGlue &&
            prev.usedJinja == rendered.usedJinja &&
            next.usedJinja == rendered.usedJinja &&
            next.text.size() >= prev.text.size() &&
            next.text.compare(0, prev.text.size(), prev.text) == 0;

        if (sentinelOk) {
            const std::string assistantEnd = prev.text.substr(sentinelPos + kSentinel.size());
            const std::string userTurn     = next.text.substr(prev.text.size());
            const std::string deltaText    = assistantEnd + userTurn;
            std::vector<llama_token> deltaTokens = common_tokenize(
                llama_model_get_vocab(_model),
                deltaText,
                /*add_special=*/false,
                /*parse_special=*/true
            );
            if (!deltaTokens.empty()) {
                _kvTokens.insert(_kvTokens.end(), deltaTokens.begin(), deltaTokens.end());
                _promptTokens = deltaTokens;
                _lastPromptTokens = promptTokens;
                _cacheValid = false;

                _batch = llama_batch{};
                _batch.token = _promptTokens.data();
                _batch.n_tokens = _promptTokens.size();
                if (_storeChats) {
                    _messageBoundaries[_messages.size()] = _kvTokens.size();
                }

                LOGi("startCompletion: suffix reuse, prompt=%zu kv=%zu decode=%zu, end=%zu user=%zu",
                     promptTokens.size(), kvSize, _promptTokens.size(),
                     assistantEnd.size(), userTurn.size());
                return rendered.usedJinja;
            }
        }
        LOGi("startCompletion: suffix reuse unavailable, using token prefix");
    }

    size_t reuseLen = 0;
    if (_storeChats && _cacheValid) {
        const size_t n = std::min(kvSize, promptTokens.size());
        while (reuseLen < n && _kvTokens[reuseLen] == promptTokens[reuseLen]) {
            ++reuseLen;
        }
        // Drop the cached tokens that no longer match the new prompt. If the
        // memory module cannot remove a partial sequence, start over.
        if (reuseLen > 0 && reuseLen < kvSize) {
            if (!llama_memory_seq_rm(
                    llama_get_memory(_ctx), 0, (llama_pos) reuseLen, -1)) {
                LOGi("startCompletion: partial KV removal failed, resetting");
                reuseLen = 0;
            }
        }
    }
    if (reuseLen == 0) {
        _resetContext();
    } else {
        // The cache was rebuilt from the rendered prompt, so older boundaries
        // no longer map to the new token positions.
        _messageBoundaries.clear();
    }
    // The cache is only reusable again once this turn completes cleanly.
    _cacheValid = false;

    // The KV cache already holds promptTokens[0, reuseLen).
    _kvTokens = promptTokens;
    _lastPromptTokens = promptTokens;
    _promptTokens.assign(promptTokens.begin() + reuseLen, promptTokens.end());

    // A new user message always adds tokens, but guard against an empty batch
    // in case a template ignores it.
    if (_promptTokens.empty() && !promptTokens.empty()) {
        _resetContext();
        _kvTokens = promptTokens;
        _lastPromptTokens = promptTokens;
        _promptTokens = promptTokens;
    }

    // create a llama_batch containing a single sequence
    // see llama_batch_init for more details
    _batch = llama_batch{};
    _batch.token = _promptTokens.data();
    _batch.n_tokens = _promptTokens.size();
    if (_storeChats) {
        // Re-record the boundary before this user turn in the rebuilt cache so
        // repeated edits keep the fast path. Verify that rendering the base
        // conversation is an exact token prefix of the full prompt.
        if (_messages.size() > 1) {
            std::vector<common_chat_msg> baseMessages;
            baseMessages.reserve(_messages.size() - 1);
            for (size_t i = 0; i + 1 < _messages.size(); ++i) {
                common_chat_msg msg;
                msg.role    = _messages[i].role;
                msg.content = _messages[i].content;
                baseMessages.push_back(std::move(msg));
            }
            RenderedPrompt baseRendered = _renderPrompt(baseMessages, false);
            std::vector<llama_token> baseTokens = common_tokenize(
                llama_model_get_vocab(_model), baseRendered.text, /*add_special=*/true,
                /*parse_special=*/true);
            if (!baseTokens.empty() && baseTokens.size() < promptTokens.size() &&
                std::equal(baseTokens.begin(), baseTokens.end(), promptTokens.begin())) {
                _messageBoundaries[_messages.size() - 1] = baseTokens.size();
            }
        }
        _messageBoundaries[_messages.size()] = _kvTokens.size();
    }

    LOGi("startCompletion: prompt=%zu kv=%zu reuse=%zu decode=%zu",
         promptTokens.size(), kvSize, reuseLen, _promptTokens.size());

    return rendered.usedJinja;
}

// taken from:
// https://github.com/ggerganov/llama.cpp/blob/master/examples/llama.android/llama/src/main/cpp/llama-android.cpp#L38
bool
LLMInference::_isValidUtf8(const char *response) {
    if (!response) {
        return true;
    }
    const unsigned char *bytes = (const unsigned char *) response;
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

std::string
LLMInference::completionLoop() {
    // check if the length of the inputs to the model
    // have exceeded the context size of the model
    uint32_t contextSize = llama_n_ctx(_ctx);
    _nCtxUsed = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0) + 1;
    if (_nCtxUsed + _batch.n_tokens > contextSize) {
        throw std::runtime_error("context size reached");
    }

    const int n_batch_tokens = _batch.n_tokens;
    auto start = ggml_time_us();
    // run the model
    if (llama_decode(_ctx, _batch) < 0) {
        throw std::runtime_error("llama_decode() failed");
    }
    const auto decode_end = ggml_time_us();
    if (n_batch_tokens > 1) {
        LOGi("prompt decode: %d tokens in %.1f ms", n_batch_tokens,
             (decode_end - start) / 1000.0);
    }

    // sample a token and check if it is an EOG (end of generation token)
    // convert the integer token to its corresponding word-piece
    _currToken = llama_sampler_sample(_sampler, _ctx, -1);
    if (llama_vocab_is_eog(llama_model_get_vocab(_model), _currToken)) {
        addChatMessage(_response.c_str(), "assistant");
        // The KV cache now holds the prompt and every generated token, so the
        // next turn can reuse it as long as its prompt shares that prefix.
        _lastResponseEmpty = _response.empty();
        if (_storeChats) {
            _cacheValid = true;
            _messageBoundaries[_messages.size()] = _kvTokens.size();
        }
        _response.clear();
        return "[EOG]";
    }
    std::string piece = common_token_to_piece(_ctx, _currToken, true);
    auto end = ggml_time_us();
    _responseGenerationTime += (end - start);
    _responseNumTokens += 1;
    _cacheResponseTokens += piece;

    // re-init the batch with the newly predicted token
    // key, value pairs of all previous tokens have been cached
    // in the KV cache
    _batch.token = &_currToken;
    _batch.n_tokens = 1;
    // This token is decoded at the start of the next completionLoop() call.
    _kvTokens.push_back(_currToken);

    if (_isValidUtf8(_cacheResponseTokens.c_str())) {
        _response += _cacheResponseTokens;
        std::string valid_utf8_piece = _cacheResponseTokens;
        _cacheResponseTokens.clear();
        return valid_utf8_piece;
    }

    return "";
}

void
LLMInference::stopCompletion() {
    if (_storeChats && !_response.empty()) {
        addChatMessage(_response.c_str(), "assistant");
    }
    _response.clear();
}

LLMInference::~LLMInference() {
    // free memory held by the message text in messages
    // (as we had used strdup() to create a malloc'ed copy)
    for (llama_chat_message &message: _messages) {
        free(const_cast<char *>(message.role));
        free(const_cast<char *>(message.content));
    }
    llama_free(_ctx);
    llama_model_free(_model);
    llama_sampler_free(_sampler);
}

std::string
LLMInference::benchModel(int pp, int tg, int pl, int nr) {
    g_batch     = llama_batch_init(pp, 0, pl);
    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(this->_ctx);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 1, i, { 0 }, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        _resetContext();

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(this->_ctx, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        _resetContext();
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, { j }, true);
            }

            if (llama_decode(this->_ctx, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        _resetContext();

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

    llama_batch_free(g_batch);

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
    llama_model_desc(this->_model, model_desc, sizeof(model_desc));

    const auto model_size     = double(llama_model_size(this->_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(this->_model)) / 1e9;

    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto*       reg  = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    std::ostringstream str;
    for (size_t i = 0; i < backends.size(); i++) {
        str << backends[i];
        if (i < backends.size() - 1) {
            str << ",";
        }
    }
    const auto backend = str.str();

    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | pp "
           << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | tg "
           << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return result.str();
}
