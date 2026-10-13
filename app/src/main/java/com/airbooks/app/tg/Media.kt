package com.airbooks.app.tg

data class Media(
    val id: Long,
    val title: String,
    val filePath: String? = null,
    val mimeType: String? = null
)
