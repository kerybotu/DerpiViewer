package com.kerybotu.derpibooru.mirror.model

/** Explicit source selection for the tag/filter search entry point. */
enum class TagSearchMode { LOCAL_TAG, REMOTE_TAG, REMOTE_FILTER }

object TagSearchRouting {
    enum class Handler { LOCAL_DICTIONARY, IMAGE_SEARCH, FILTER_HTML }

    fun handler(mode: TagSearchMode): Handler = when (mode) {
        TagSearchMode.LOCAL_TAG -> Handler.LOCAL_DICTIONARY
        TagSearchMode.REMOTE_TAG -> Handler.IMAGE_SEARCH
        TagSearchMode.REMOTE_FILTER -> Handler.FILTER_HTML
    }
}
