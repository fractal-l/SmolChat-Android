#pragma once
#include "chat.h"
#include "common.h"
#include "llama.h"
#include <cstddef>
#include <map>
#include <string>
#include <vector>

class LLMInference {
    // llama.cpp-specific types
    llama_context* _ctx;
    llama_model*   _model;
    llama_sampler* _sampler;
    llama_token    _currToken;
    llama_batch    _batch{};

    llama_batch g_batch;

    // container to store user/assistant messages in the chat
    std::vector<llama_chat_message> _messages;
    // stores the string generated after applying
    // the chat-template to all messages in `_messages`
    std::vector<char> _formattedMessages;
    // stores the tokens for the last query
    // appended to `_messages`
    std::vector<llama_token> _promptTokens;
    const char*              _chatTemplate;

    // Token sequence currently stored in the KV cache: the last rendered
    // prompt followed by the tokens generated for it. On the next turn the
    // newly rendered prompt is tokenized and only the suffix after the
    // longest common prefix is decoded.
    std::vector<llama_token> _kvTokens;
    // Rendered prompt of the previous turn (before generation). Used to verify
    // that the conversation prefix did not change since the last turn.
    std::vector<llama_token> _lastPromptTokens;
    // Clean turn boundaries mapped from message count to KV-token count. These
    // allow an edit/rollback to keep the cached prefix instead of re-prefilling
    // the whole conversation.
    std::map<size_t, size_t> _messageBoundaries;
    // True only after a turn completed cleanly. A cancelled or failed turn
    // leaves it false so the next turn rebuilds the context from scratch.
    bool                     _cacheValid = false;
    // Whether the previous turn produced an empty response.
    bool                     _lastResponseEmpty = true;

    struct RenderedPrompt {
        std::string text;
        bool        usedJinja = true;
    };

    // stores the complete response for the given query
    std::string _response;
    std::string _cacheResponseTokens;
    // whether to cache previous messages in `_messages`
    bool _storeChats;

    // response generation metrics
    int64_t _responseGenerationTime = 0;
    long    _responseNumTokens      = 0;

    // length of context window consumed during the conversation
    int _nCtxUsed = 0;

    bool _isValidUtf8(const char* response);

    RenderedPrompt _renderPrompt(bool addGenerationPrompt);

    RenderedPrompt _renderPrompt(
        const std::vector<common_chat_msg>& messages, bool addGenerationPrompt);

    void _resetContext();

  public:
    void loadModel(const char* modelPath, float minP, float temperature, bool storeChats, long contextSize,
                   const char* chatTemplate, int nThreads, bool useMmap, bool useMlock, int topK, float topP,
                   float repeatPenalty, int repeatLastN, int seed);

    std::string benchModel(int pp, int tg, int pl, int nr);

    void addChatMessage(const char* message, const char* role);

    // Clears all messages and KV-cache state without unloading the model.
    void clearChat();

    // Drops messages after `messageCount` and truncates the KV cache to the
    // matching clean turn boundary. Returns false when no safe boundary exists.
    bool trimToMessageCount(size_t messageCount);

    float getResponseGenerationTime() const;

    int getContextSizeUsed() const;

    // Returns true if Jinja template was used, false if legacy fallback was needed.
    bool startCompletion(const char* query);

    std::string completionLoop();

    void stopCompletion();

    ~LLMInference();
};