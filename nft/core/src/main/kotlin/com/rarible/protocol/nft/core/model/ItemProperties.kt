package com.rarible.protocol.nft.core.model

data class ItemProperties(
    val name: String,
    val description: String?,
    val image: String?,
    val imagePreview: String?,
    val imageBig: String?,
    val animationUrl: String?,
    val attributes: List<ItemAttribute>,
    val rawJsonContent: String?
) {
    companion object {
        val EMPTY = ItemProperties(
            name = "Untitled",
            description = null,
            image = null,
            imagePreview = null,
            imageBig = null,
            animationUrl = null,
            attributes = listOf(),
            rawJsonContent = null
        )
    }
}

data class ItemAttribute(
    val key: String,
    val value: String? = null,
    val type: String? = null,
    val format: String? = null
)
