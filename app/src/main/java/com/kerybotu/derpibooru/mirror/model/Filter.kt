package com.kerybotu.derpibooru.mirror.model

import java.io.Serializable

data class Filter(
    val id: Int,
    val name: String,
    val description: String,
    val userId: Int?,
    val system: Boolean,
    val public: Boolean,
    val spoilerCount: Int,
    val hiddenCount: Int
) : Serializable
