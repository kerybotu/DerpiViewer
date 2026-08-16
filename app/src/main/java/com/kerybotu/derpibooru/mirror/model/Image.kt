package com.kerybotu.derpibooru.mirror.model

import java.io.Serializable

data class Image(
    val id: Int,
    val title: String,
    val thumbnailUrl: String?,
    val width: Int,
    val height: Int,
    val score: Int,
    val faves: Int,
    val upvotes: Int,
    val downvotes: Int,
    val commentCount: Int,
    val tags: List<String>,
    val fullUrl: String? = null,
    val uploader: String? = null,
    val createdAt: String? = null,
    val description: String? = null
) : Serializable
