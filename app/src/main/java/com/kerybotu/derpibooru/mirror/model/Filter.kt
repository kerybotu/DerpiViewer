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
    val hiddenCount: Int,
    val creator: String? = null,
    val createdAt: String? = null,
    val spoileredTagIds: List<Int> = emptyList(),
    val hiddenTagIds: List<Int> = emptyList(),
    val spoileredComplex: String? = null,
    val hiddenComplex: String? = null,
    /** Original server ID when this item is a local import or copy. */
    val sourceId: Int? = null,
    val sourceDomain: String? = null,
    /** Search expression for a locally managed custom filter. */
    val localQuery: String? = null,
    val isLocal: Boolean = false
) : Serializable
