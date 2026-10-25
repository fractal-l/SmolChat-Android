#include "LLMInference.h"
#include <algorithm>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <iomanip>
#include <iostream>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>

#define TAG "[SmolLMAndroid-Cpp]"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
constexpr uint32_t kCacheMagic         = 0x534D4C43; // "SMLC"
constexpr uint32_t kCacheVersion       = 2;
constexpr uint32_t kLegacyCacheVersion = 1;
constexpr uint64_t kFnvOffset          = 1469598103934665603ULL;
constexpr uint64_t kFnvPrime           = 1099511628211ULL;

struct CacheMetadata {
    uint32_t                 version       = 0;
    uint64_t                 generation    = 0;
    uint64_t                 modelHash     = 0;
    uint32_t                 contextSize   = 0;
    uint64_t                 messageCount  = 0;
    uint64_t                 tokenCount    = 0;
    uint64_t                 savedHash     = 0;
    uint64_t                 boundaryCount = 0;
    std::map<size_t, size_t> boundaries;
};

uint64_t
hashBytes(uint64_t hash, const void* data, size_t size) {
    const auto* bytes = static_cast<const uint8_t*>(data);
    for (size_t i = 0; i < size; ++i) {
        hash ^= bytes[i];
        hash *= kFnvPrime;
    }
    return hash;
}

uint64_t
hashCache(const std::vector<llama_token>& tokens, const std::map<size_t, size_t>& boundaries) {
    uint64_t hash = kFnvOffset;
    if (!tokens.empty()) {
        hash = hashBytes(hash, tokens.data(), tokens.size() * sizeof(llama_token));
    }
    for (const auto& entry : boundaries) {
        const uint64_t pair[2] = {
            static_cast<uint64_t>(entry.first),
            static_cast<uint64_t>(entry.second),
        };
        hash = hashBytes(hash, pair, sizeof(pair));
    }
    return hash;
}

bool
writeU32(FILE* file, uint32_t value) {
    return fwrite(&value, sizeof(value), 1, file) == 1;
}

bool
writeU64(FILE* file, uint64_t value) {
    return fwrite(&value, sizeof(value), 1, file) == 1;
}

bool
readU32(FILE* file, uint32_t& value) {
    return fread(&value, sizeof(value), 1, file) == 1;
}

bool
readU64(FILE* file, uint64_t& value) {
    return fread(&value, sizeof(value), 1, file) == 1;
}

bool
fileExists(const std::string& path) {
    struct stat info{};
    return stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode);
}

bool
cacheSlotExists(const std::string& statePath) {
    return fileExists(statePath) && fileExists(statePath + ".meta");
}

bool
syncFile(const std::string& path) {
    const int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        return false;
    }
    const bool ok = fsync(fd) == 0;
    close(fd);
    return ok;
}

void
syncParentDirectory(const std::string& path) {
    const size_t      separator = path.find_last_of('/');
    const std::string directory
        = separator == std::string::npos ? "." : (separator == 0 ? "/" : path.substr(0, separator));
    const int fd = open(directory.c_str(), O_RDONLY);
    if (fd < 0) {
        return;
    }
    if (fsync(fd) != 0) {
        LOGe("cache: unable to fsync directory %s", directory.c_str());
    }
    close(fd);
}

bool
readCacheMetadata(const std::string& metaPath, CacheMetadata& metadata) {
    FILE* file = fopen(metaPath.c_str(), "rb");
    if (!file) {
        return false;
    }

    uint32_t magic = 0;
    bool     ok    = readU32(file, magic) && readU32(file, metadata.version);
    if (ok && metadata.version == kCacheVersion) {
        ok = readU64(file, metadata.generation);
    }
    ok = ok && readU64(file, metadata.modelHash) && readU32(file, metadata.contextSize)
         && readU64(file, metadata.messageCount) && readU64(file, metadata.tokenCount)
         && readU64(file, metadata.savedHash) && readU64(file, metadata.boundaryCount);

    if (!ok || magic != kCacheMagic || (metadata.version != kCacheVersion && metadata.version != kLegacyCacheVersion)
        || metadata.boundaryCount > 100000) {
        fclose(file);
        return false;
    }

    bool boundariesOk = true;
    for (uint64_t i = 0; i < metadata.boundaryCount; ++i) {
        uint64_t savedMessageCount = 0;
        uint64_t savedTokenCount   = 0;
        if (!readU64(file, savedMessageCount) || !readU64(file, savedTokenCount)
            || savedMessageCount > metadata.messageCount || savedTokenCount > metadata.tokenCount) {
            boundariesOk = false;
            break;
        }
        metadata.boundaries[static_cast<size_t>(savedMessageCount)] = static_cast<size_t>(savedTokenCount);
    }
    fclose(file);
    return boundariesOk;
}

uint64_t
readCacheGeneration(const std::string& metaPath) {
    CacheMetadata metadata;
    return readCacheMetadata(metaPath, metadata) ? metadata.generation : 0;
}
} // namespace

void
LLMInference::loadModel(const char* model_path, float minP, float temperature, bool storeChats, long contextSize,
                        const char* chatTemplate, int nThreads, bool useMmap, bool useMlock, int topK, float topP,
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
    model_params.use_mmap           = useMmap;
    model_params.use_mlock          = useMlock;
    _model                          = llama_model_load_from_file(model_path, model_params);
    if (!_model) {
        LOGe("failed to load model from %s", model_path);
        throw std::runtime_error("loadModel() failed");
    }

    _modelPath               = model_path ? model_path : "";
    _modelHash               = hashBytes(kFnvOffset, _modelPath.data(), _modelPath.size());
    const uint64_t modelSize = static_cast<uint64_t>(llama_model_size(_model));
    _modelHash               = hashBytes(_modelHash, &modelSize, sizeof(modelSize));

    // create an instance of llama_context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx                = contextSize;
    ctx_params.n_batch              = contextSize;
    ctx_params.n_threads            = nThreads;
    // Prompt processing has its own thread count. Leaving it unset falls back to
    // GGML_DEFAULT_N_THREADS (4), so users who raised the thread setting still
    // prefilled at 4 threads.
    ctx_params.n_threads_batch = nThreads;
    ctx_params.no_perf         = true; // disable performance metrics
    _ctx                       = llama_init_from_model(_model, ctx_params);
    if (!_ctx) {
        LOGe("llama_new_context_with_model() returned null)");
        throw std::runtime_error("llama_new_context_with_model() returned null");
    }
    LOGi("context: n_ctx=%u n_batch=%u n_ubatch=%u threads=%d threads_batch=%d", llama_n_ctx(_ctx), llama_n_batch(_ctx),
         llama_n_ubatch(_ctx), llama_n_threads(_ctx), llama_n_threads_batch(_ctx));

    // create an instance of llama_sampler
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf                    = true; // disable performance metrics
    _sampler                                  = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(_sampler, llama_sampler_init_penalties(repeatLastN, repeatPenalty, 0.0f, 0.0f));
    llama_sampler_chain_add(_sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(_sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(_sampler, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(_sampler, llama_sampler_init_dist(static_cast<uint32_t>(seed)));

    _formattedMessages = std::vector<char>(llama_n_ctx(_ctx));
    _messages.clear();
    _kvTokens.clear();
    _lastPromptTokens.clear();
    _messageBoundaries.clear();
    _cacheValid        = false;
    _lastResponseEmpty = true;
    _response.clear();
    _cacheResponseTokens.clear();
    _nCtxUsed = 0;
    _batch    = llama_batch{};

    if (chatTemplate == nullptr) {
        _chatTemplate = llama_model_chat_template(_model, nullptr);
    } else {
        _chatTemplate = strdup(chatTemplate);
    }
    this->_storeChats = storeChats;
}

void
LLMInference::addChatMessage(const char* message, const char* role) {
    _messages.push_back({ strdup(role), strdup(message) });
}

void
LLMInference::clearChat() {
    for (llama_chat_message& message : _messages) {
        free(const_cast<char*>(message.role));
        free(const_cast<char*>(message.content));
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
    const char* lastRole = _messages[messageCount - 1].role;
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
            RenderedPrompt           keptRendered = _renderPrompt(keptMessages, false);
            std::vector<llama_token> keptTokens
                = common_tokenize(llama_model_get_vocab(_model), keptRendered.text, /*add_special=*/true,
                                  /*parse_special=*/true);
            if (keptTokens.empty() || keptTokens.size() > _kvTokens.size()
                || !std::equal(keptTokens.begin(), keptTokens.end(), _kvTokens.begin())) {
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
    if (!llama_memory_seq_rm(llama_get_memory(_ctx), 0, (llama_pos)boundary, -1)) {
        LOGi("trimToMessageCount: partial KV removal failed");
        return false;
    }
    for (size_t i = messageCount; i < _messages.size(); ++i) {
        free(const_cast<char*>(_messages[i].role));
        free(const_cast<char*>(_messages[i].content));
    }
    _messages.resize(messageCount);
    _kvTokens.resize(boundary);
    // Force the token-prefix path on the next turn. The cache may contain
    // sampled assistant tokens, so the rendered-prompt fast path is not valid.
    _lastPromptTokens.clear();
    _lastResponseEmpty = false;
    _cacheValid        = true;
    _nCtxUsed          = (int)boundary;
    _response.clear();
    _cacheResponseTokens.clear();
    llama_sampler_reset(_sampler);
    _messageBoundaries.erase(_messageBoundaries.upper_bound(messageCount), _messageBoundaries.end());
    LOGi("trimToMessageCount: messages=%zu kv=%zu", messageCount, boundary);
    return true;
}

bool
LLMInference::saveCache(const char* path) {
    if (!path || path[0] == '\0' || !_ctx || !_storeChats || !_cacheValid || _kvTokens.empty()) {
        return false;
    }

    // Never persist a token list that the context does not actually hold. A
    // failed decode or an early stop can leave the bookkeeping ahead of the
    // sequence memory; restoring such a cache would decode later tokens at the
    // wrong positions.
    const llama_pos lastPosition = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0);
    if (lastPosition < 0 || static_cast<size_t>(lastPosition) + 1 != _kvTokens.size()) {
        LOGe("saveCache: token list does not match decoded sequence");
        return false;
    }

    const std::string primaryStatePath(path);
    const std::string primaryMetaPath = primaryStatePath + ".meta";
    const std::string backupStatePath = primaryStatePath + ".bak";
    const std::string backupMetaPath  = backupStatePath + ".meta";

    const bool primaryComplete = cacheSlotExists(primaryStatePath);
    const bool backupComplete  = cacheSlotExists(backupStatePath);

    // Keep the only complete cache while writing the replacement into the
    // other slot. When both slots are complete, replace the older generation.
    std::string targetStatePath;
    std::string otherStatePath;
    if (!primaryComplete && backupComplete) {
        targetStatePath = primaryStatePath;
        otherStatePath  = backupStatePath;
    } else if (primaryComplete && !backupComplete) {
        targetStatePath = backupStatePath;
        otherStatePath  = primaryStatePath;
    } else if (!primaryComplete && !backupComplete) {
        targetStatePath = primaryStatePath;
        otherStatePath  = backupStatePath;
    } else {
        const uint64_t primaryGeneration = readCacheGeneration(primaryMetaPath);
        const uint64_t backupGeneration  = readCacheGeneration(backupMetaPath);
        if (primaryGeneration <= backupGeneration) {
            targetStatePath = primaryStatePath;
            otherStatePath  = backupStatePath;
        } else {
            targetStatePath = backupStatePath;
            otherStatePath  = primaryStatePath;
        }
    }

    const uint64_t generation = std::max(readCacheGeneration(primaryMetaPath), readCacheGeneration(backupMetaPath)) + 1;
    const std::string targetMetaPath = targetStatePath + ".meta";
    const std::string tempStatePath  = targetStatePath + ".tmp";
    const std::string tempMetaPath   = tempStatePath + ".meta";

    std::remove(tempStatePath.c_str());
    std::remove(tempMetaPath.c_str());

    const size_t stateBytes
        = llama_state_seq_save_file(_ctx, tempStatePath.c_str(), 0, _kvTokens.data(), _kvTokens.size());
    if (stateBytes == 0 || !syncFile(tempStatePath)) {
        LOGe("saveCache: failed to write sequence state");
        std::remove(tempStatePath.c_str());
        return false;
    }

    FILE* file = fopen(tempMetaPath.c_str(), "wb");
    if (!file) {
        LOGe("saveCache: failed to open metadata file");
        std::remove(tempStatePath.c_str());
        return false;
    }

    const uint64_t cacheHash = hashCache(_kvTokens, _messageBoundaries);
    bool           ok = writeU32(file, kCacheMagic) && writeU32(file, kCacheVersion) && writeU64(file, generation)
              && writeU64(file, _modelHash) && writeU32(file, llama_n_ctx(_ctx))
              && writeU64(file, static_cast<uint64_t>(_messages.size()))
              && writeU64(file, static_cast<uint64_t>(_kvTokens.size())) && writeU64(file, cacheHash)
              && writeU64(file, static_cast<uint64_t>(_messageBoundaries.size()));
    if (ok) {
        for (const auto& entry : _messageBoundaries) {
            ok = writeU64(file, static_cast<uint64_t>(entry.first))
                 && writeU64(file, static_cast<uint64_t>(entry.second));
            if (!ok)
                break;
        }
    }
    if (fclose(file) != 0) {
        ok = false;
    }
    if (ok && !syncFile(tempMetaPath)) {
        ok = false;
    }

    if (ok) {
        ok = std::rename(tempStatePath.c_str(), targetStatePath.c_str()) == 0;
    }
    if (ok) {
        ok = std::rename(tempMetaPath.c_str(), targetMetaPath.c_str()) == 0;
    }
    if (!ok) {
        LOGe("saveCache: failed to finalize cache files");
        std::remove(tempStatePath.c_str());
        std::remove(tempMetaPath.c_str());
        // The other slot still holds the previous complete cache. Remove the
        // partially installed target instead of deleting the valid slot.
        std::remove(targetStatePath.c_str());
        std::remove(targetMetaPath.c_str());
        return false;
    }

    syncParentDirectory(targetStatePath);

    // Once the new generation is durable, the previous generation is no longer
    // needed. Keeping only one slot at rest halves cache storage usage.
    std::remove(otherStatePath.c_str());
    std::remove((otherStatePath + ".meta").c_str());
    syncParentDirectory(otherStatePath);

    LOGi("saveCache: messages=%zu kv=%zu bytes=%zu generation=%llu", _messages.size(), _kvTokens.size(), stateBytes,
         static_cast<unsigned long long>(generation));
    return true;
}

bool
LLMInference::restoreCache(const char* path) {
    if (!path || path[0] == '\0' || !_ctx || !_storeChats) {
        return false;
    }

    const std::string primaryStatePath(path);
    const std::string backupStatePath = primaryStatePath + ".bak";

    std::vector<std::pair<std::string, CacheMetadata>> candidates;
    CacheMetadata                                      primaryMetadata;
    if (readCacheMetadata(primaryStatePath + ".meta", primaryMetadata) && cacheSlotExists(primaryStatePath)) {
        candidates.emplace_back(primaryStatePath, std::move(primaryMetadata));
    }
    CacheMetadata backupMetadata;
    if (readCacheMetadata(backupStatePath + ".meta", backupMetadata) && cacheSlotExists(backupStatePath)) {
        candidates.emplace_back(backupStatePath, std::move(backupMetadata));
    }

    // If a crash happened after the new generation was committed but before the
    // previous one was cleaned up, restore the newest valid generation first.
    std::sort(candidates.begin(), candidates.end(),
              [](const auto& lhs, const auto& rhs) { return lhs.second.generation > rhs.second.generation; });

    for (auto& candidate : candidates) {
        CacheMetadata& metadata = candidate.second;
        if (metadata.modelHash != _modelHash || metadata.contextSize != llama_n_ctx(_ctx) || metadata.tokenCount == 0
            || metadata.tokenCount > llama_n_ctx(_ctx)) {
            LOGi("restoreCache: incompatible cache (model=%s ctx=%u/%u tokens=%llu)",
                 metadata.modelHash == _modelHash ? "match" : "mismatch", metadata.contextSize, llama_n_ctx(_ctx),
                 static_cast<unsigned long long>(metadata.tokenCount));
            continue;
        }
        // The cache may cover fewer messages than the transcript: a turn can be written to the
        // database before the cache is persisted, and a reload must still reuse the prefix it
        // does contain. A cache that is ahead of the transcript cannot be reconciled.
        if (metadata.messageCount > static_cast<uint64_t>(_messages.size())) {
            LOGi("restoreCache: cache is ahead of the transcript (%llu > %zu), skipping",
                 static_cast<unsigned long long>(metadata.messageCount), _messages.size());
            continue;
        }

        _resetContext();
        std::vector<llama_token> tokens(static_cast<size_t>(metadata.tokenCount));
        size_t                   loadedTokenCount = 0;
        const size_t             stateBytes = llama_state_seq_load_file(_ctx, candidate.first.c_str(), 0, tokens.data(),
                                                                        tokens.size(), &loadedTokenCount);
        if (stateBytes == 0 || loadedTokenCount != metadata.tokenCount
            || hashCache(tokens, metadata.boundaries) != metadata.savedHash) {
            LOGe("restoreCache: cache validation failed for %s", candidate.first.c_str());
            _resetContext();
            continue;
        }

        _kvTokens          = std::move(tokens);
        _messageBoundaries = std::move(metadata.boundaries);
        _lastPromptTokens.clear();
        _promptTokens.clear();
        _response.clear();
        _cacheResponseTokens.clear();
        _cacheValid        = true;
        _lastResponseEmpty = false;
        _batch             = llama_batch{};
        _nCtxUsed          = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0) + 1;
        if (_nCtxUsed <= 0) {
            _nCtxUsed = static_cast<int>(_kvTokens.size());
        }
        llama_sampler_reset(_sampler);

        LOGi("restoreCache: messages=%zu kv=%zu bytes=%zu generation=%llu", _messages.size(), _kvTokens.size(),
             stateBytes, static_cast<unsigned long long>(metadata.generation));
        return true;
    }

    return false;
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
LLMInference::_renderPrompt(const std::vector<common_chat_msg>& messages, bool addGenerationPrompt) {
    auto templates = common_chat_templates_init(_model, _chatTemplate ? _chatTemplate : "");

    common_chat_templates_inputs inputs;
    inputs.messages              = messages;
    inputs.add_generation_prompt = addGenerationPrompt;
    inputs.use_jinja             = true;
    // llama.cpp defaults this to true. For Gemma 4 that leaves the
    // non-thinking prompt without its empty thought channel, so the model
    // emits those control tokens as part of the response. Keep the default
    // for other model families until the app exposes a thinking toggle.
    inputs.enable_thinking =
        _chatTemplate == nullptr || strstr(_chatTemplate, "<|channel>thought") == nullptr;
    inputs.chat_template_kwargs["tools"] = "[]";

    RenderedPrompt rendered;
    try {
        rendered.text = common_chat_templates_apply(templates.get(), inputs).prompt;
    } catch (const std::exception& e) {
        LOGe("Jinja template failed: %s — retrying with legacy renderer", e.what());
        inputs.use_jinja = false;
        inputs.chat_template_kwargs.clear();
        rendered.text      = common_chat_templates_apply(templates.get(), inputs).prompt;
        rendered.usedJinja = false;
    }
    return rendered;
}

void
LLMInference::_resetContext() {
    llama_memory_clear(llama_get_memory(_ctx), true);
    _kvTokens.clear();
    _lastPromptTokens.clear();
    _cacheValid        = false;
    _lastResponseEmpty = true;
    _response.clear();
    _cacheResponseTokens.clear();
    _nCtxUsed = 0;
    _messageBoundaries.clear();
}

float
LLMInference::getResponseGenerationTime() const {
    return (float)_responseNumTokens / (_responseGenerationTime / 1e6);
}

int
LLMInference::getContextSizeUsed() const {
    return _nCtxUsed;
}

bool
LLMInference::startCompletion(const char* query) {
    llama_sampler_reset(_sampler);
    _responseGenerationTime = 0;
    _responseNumTokens      = 0;
    _response.clear();
    _cacheResponseTokens.clear();

    if (_storeChats && _cacheValid) {
        _messageBoundaries[_messages.size()] = _kvTokens.size();
    }

    const bool skipUserTurn = query == nullptr || query[0] == '\0';
    if (!skipUserTurn) {
        addChatMessage(query, "user");
    }

    RenderedPrompt rendered = _renderPrompt(true);

    // Tokenize the whole prompt on every turn. Reusing an exact token prefix
    // (instead of comparing rendered text) is robust against templates that
    // trim or otherwise normalize whitespace in the assistant message.
    std::vector<llama_token> promptTokens = common_tokenize(llama_model_get_vocab(_model), rendered.text,
                                                            /*add_special=*/true,
                                                            /*parse_special=*/true);

    const size_t kvSize = _kvTokens.size();

    // Fast path: the previous turn ended cleanly and the conversation prefix
    // (everything up to the assistant header) is unchanged. Keep the tokens
    // the model actually generated in the KV cache and append only the
    // template's assistant terminator plus the new user turn. This avoids
    // re-prefilling the previous response when the template renders the
    // assistant text differently (e.g. trims whitespace) from the sampled
    // tokens.
    if (_storeChats && _cacheValid && !_lastResponseEmpty && !_lastPromptTokens.empty()
        && _lastPromptTokens.size() <= promptTokens.size()
        && std::equal(_lastPromptTokens.begin(), _lastPromptTokens.end(), promptTokens.begin()) && _messages.size() >= 2
        && strcmp(_messages[_messages.size() - 1].role, "user") == 0
        && strcmp(_messages[_messages.size() - 2].role, "assistant") == 0) {
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
        common_chat_msg              userMessage;
        userMessage.role    = _messages.back().role;
        userMessage.content = _messages.back().content;
        nextMessages.push_back(std::move(userMessage));

        RenderedPrompt next = _renderPrompt(nextMessages, true);

        const size_t sentinelPos = prev.text.rfind(kSentinel);
        const bool   jsonGlue    = sentinelPos != std::string::npos && [&] {
            const std::string tail = prev.text.substr(sentinelPos + kSentinel.size());
            return tail.find('"') != std::string::npos || tail.find('}') != std::string::npos
                   || tail.find(']') != std::string::npos;
        }();
        const bool sentinelOk = sentinelPos != std::string::npos && prev.text.find(kSentinel) == sentinelPos
                                && !jsonGlue && prev.usedJinja == rendered.usedJinja
                                && next.usedJinja == rendered.usedJinja && next.text.size() >= prev.text.size()
                                && next.text.compare(0, prev.text.size(), prev.text) == 0;

        if (sentinelOk) {
            const std::string        assistantEnd = prev.text.substr(sentinelPos + kSentinel.size());
            const std::string        userTurn     = next.text.substr(prev.text.size());
            const std::string        deltaText    = assistantEnd + userTurn;
            std::vector<llama_token> deltaTokens  = common_tokenize(llama_model_get_vocab(_model), deltaText,
                                                                    /*add_special=*/false,
                                                                    /*parse_special=*/true);
            if (!deltaTokens.empty()) {
                _kvTokens.insert(_kvTokens.end(), deltaTokens.begin(), deltaTokens.end());
                _promptTokens     = deltaTokens;
                _lastPromptTokens = promptTokens;
                _cacheValid       = false;

                _batch          = llama_batch{};
                _batch.token    = _promptTokens.data();
                _batch.n_tokens = _promptTokens.size();
                if (_storeChats) {
                    _messageBoundaries[_messages.size()] = _kvTokens.size();
                }

                LOGi("startCompletion: suffix reuse, prompt=%zu kv=%zu decode=%zu, end=%zu user=%zu",
                     promptTokens.size(), kvSize, _promptTokens.size(), assistantEnd.size(), userTurn.size());
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
            if (!llama_memory_seq_rm(llama_get_memory(_ctx), 0, (llama_pos)reuseLen, -1)) {
                LOGi("startCompletion: partial KV removal failed, resetting");
                reuseLen = 0;
            }
        }
    }
    if (reuseLen == 0) {
        _resetContext();
    } else if (skipUserTurn) {
        // A skipped user turn extends the existing cache with an assistant
        // header. Boundaries that still fall inside the reused prefix remain
        // valid; drop only the ones in the replaced suffix.
        for (auto it = _messageBoundaries.begin(); it != _messageBoundaries.end();) {
            if (it->second > reuseLen) {
                it = _messageBoundaries.erase(it);
            } else {
                ++it;
            }
        }
    } else {
        // The cache was rebuilt from the rendered prompt, so older boundaries
        // no longer map to the new token positions.
        _messageBoundaries.clear();
    }
    // The cache is only reusable again once this turn completes cleanly.
    _cacheValid = false;

    // The KV cache already holds promptTokens[0, reuseLen).
    _kvTokens         = promptTokens;
    _lastPromptTokens = promptTokens;
    _promptTokens.assign(promptTokens.begin() + reuseLen, promptTokens.end());

    // A new user message always adds tokens, but guard against an empty batch
    // in case a template ignores it.
    if (_promptTokens.empty() && !promptTokens.empty()) {
        _resetContext();
        _kvTokens         = promptTokens;
        _lastPromptTokens = promptTokens;
        _promptTokens     = promptTokens;
    }

    // create a llama_batch containing a single sequence
    // see llama_batch_init for more details
    _batch          = llama_batch{};
    _batch.token    = _promptTokens.data();
    _batch.n_tokens = _promptTokens.size();
    if (_storeChats && !skipUserTurn) {
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
            RenderedPrompt           baseRendered = _renderPrompt(baseMessages, false);
            std::vector<llama_token> baseTokens
                = common_tokenize(llama_model_get_vocab(_model), baseRendered.text, /*add_special=*/true,
                                  /*parse_special=*/true);
            if (!baseTokens.empty() && baseTokens.size() < promptTokens.size()
                && std::equal(baseTokens.begin(), baseTokens.end(), promptTokens.begin())) {
                _messageBoundaries[_messages.size() - 1] = baseTokens.size();
            }
        }
        _messageBoundaries[_messages.size()] = _kvTokens.size();
    }

    LOGi("startCompletion: prompt=%zu kv=%zu reuse=%zu decode=%zu", promptTokens.size(), kvSize, reuseLen,
         _promptTokens.size());

    return rendered.usedJinja;
}

// taken from:
// https://github.com/ggerganov/llama.cpp/blob/master/examples/llama.android/llama/src/main/cpp/llama-android.cpp#L38
bool
LLMInference::_isValidUtf8(const char* response) {
    if (!response) {
        return true;
    }
    const unsigned char* bytes = (const unsigned char*)response;
    int                  num;
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
    _nCtxUsed            = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0) + 1;
    if (_nCtxUsed + _batch.n_tokens > contextSize) {
        throw std::runtime_error("context size reached");
    }

    const int n_batch_tokens = _batch.n_tokens;
    auto      start          = ggml_time_us();
    // run the model
    if (llama_decode(_ctx, _batch) < 0) {
        throw std::runtime_error("llama_decode() failed");
    }
    const auto decode_end = ggml_time_us();
    if (n_batch_tokens > 1) {
        const double elapsedMs = (decode_end - start) / 1000.0;
        LOGi("prompt decode: %d tokens in %.1f ms (%.1f tok/s)", n_batch_tokens, elapsedMs,
             elapsedMs > 0 ? n_batch_tokens * 1000.0 / elapsedMs : 0.0);
    }

    // sample a token and check if it is an EOG (end of generation token)
    // convert the integer token to its corresponding word-piece
    _currToken = llama_sampler_sample(_sampler, _ctx, -1);
    if (llama_vocab_is_eog(llama_model_get_vocab(_model), _currToken)) {
        addChatMessage(_response.c_str(), "assistant");
        // The KV cache now holds the prompt and every generated token, so the
        // next turn can reuse it as long as its prompt shares that prefix.
        _lastResponseEmpty            = _response.empty();
        const llama_pos lastPosition  = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0);
        const size_t    decodedTokens = lastPosition >= 0 ? static_cast<size_t>(lastPosition) + 1 : 0;
        if (_storeChats && decodedTokens == _kvTokens.size()) {
            _cacheValid                          = true;
            _messageBoundaries[_messages.size()] = _kvTokens.size();
        } else {
            _cacheValid = false;
        }
        _response.clear();
        return "[EOG]";
    }
    std::string piece = common_token_to_piece(_ctx, _currToken, true);
    auto        end   = ggml_time_us();
    _responseGenerationTime += (end - start);
    _responseNumTokens += 1;
    _cacheResponseTokens += piece;

    // re-init the batch with the newly predicted token
    // key, value pairs of all previous tokens have been cached
    // in the KV cache
    _batch.token    = &_currToken;
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
    _response.clear();
    _cacheResponseTokens.clear();
    if (!_storeChats || _cacheValid) {
        // No chat state to maintain, or completionLoop() already committed the
        // turn to the KV cache on a clean end of generation.
        return;
    }

    // Cancelled or failed turn: the KV cache holds the rendered prompt followed
    // by the tokens generated before the stop. Keep the part that still matches
    // this turn's prompt and drop the rest, so the next turn reuses the prefix
    // instead of re-prefilling the conversation. The aborted reply is not
    // appended to _messages: it is never persisted, and leaving it in the
    // native transcript would desync it from the database.
    //
    // Limit the reusable prefix to tokens actually present in the sequence.
    // _kvTokens is filled with the whole prompt before decoding, so a decode
    // failure can otherwise mark tokens that were never written as valid.
    const llama_pos lastPosition  = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0);
    const size_t    decodedTokens = lastPosition >= 0 ? static_cast<size_t>(lastPosition) + 1 : 0;
    if (decodedTokens > _kvTokens.size()) {
        LOGe("stopCompletion: decoded sequence is ahead of token list, resetting");
        _resetContext();
        return;
    }
    const size_t limit    = std::min(std::min(_kvTokens.size(), _lastPromptTokens.size()), decodedTokens);
    size_t       reusable = 0;
    while (reusable < limit && _kvTokens[reusable] == _lastPromptTokens[reusable]) {
        ++reusable;
    }

    if (reusable == 0) {
        LOGi("stopCompletion: no reusable prefix, discarding KV cache");
        _resetContext();
        return;
    }

    if (reusable < decodedTokens && !llama_memory_seq_rm(llama_get_memory(_ctx), 0, (llama_pos)reusable, -1)) {
        LOGe("stopCompletion: unable to trim KV cache, resetting");
        _resetContext();
        return;
    }
    _kvTokens.resize(reusable);

    // Boundaries past the retained prefix no longer map to cached tokens.
    for (auto it = _messageBoundaries.begin(); it != _messageBoundaries.end();) {
        if (it->second > _kvTokens.size()) {
            it = _messageBoundaries.erase(it);
        } else {
            ++it;
        }
    }

    // The retained tokens are exactly the prompt, so the sentinel fast path is
    // valid only when nothing was truncated.
    _lastPromptTokens  = _kvTokens;
    _lastResponseEmpty = false;
    _cacheValid        = true;
    _nCtxUsed          = static_cast<int>(_kvTokens.size());
    LOGi("stopCompletion: kept %zu cached tokens after an interrupted turn", _kvTokens.size());
}

LLMInference::~LLMInference() {
    // free memory held by the message text in messages
    // (as we had used strdup() to create a malloc'ed copy)
    for (llama_chat_message& message : _messages) {
        free(const_cast<char*>(message.role));
        free(const_cast<char*>(message.content));
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
