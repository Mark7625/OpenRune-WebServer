package dev.openrune.server.endpoints

/**
 * Helper functions for image resizing calculations
 */
object ImageResizeHelper {
    /**
     * Calculate final dimensions for resizing an image with optional aspect ratio preservation
     * @param originalWidth Original image width
     * @param originalHeight Original image height
     * @param targetWidth Target width (null to keep original)
     * @param targetHeight Target height (null to keep original)
     * @param keepAspectRatio Whether to preserve aspect ratio
     * @return Pair of (finalWidth, finalHeight)
     */
    fun calculateDimensions(
        originalWidth: Int,
        originalHeight: Int,
        targetWidth: Int?,
        targetHeight: Int?,
        keepAspectRatio: Boolean
    ): Pair<Int, Int> {
        return when {
            targetWidth != null && targetHeight != null -> {
                if (keepAspectRatio) {
                    val aspectRatio = originalWidth.toDouble() / originalHeight
                    val targetAspectRatio = targetWidth.toDouble() / targetHeight
                    
                    if (aspectRatio > targetAspectRatio) {
                        // Image is wider, fit to width
                        targetWidth to (targetWidth / aspectRatio).toInt()
                    } else {
                        // Image is taller, fit to height
                        (targetHeight * aspectRatio).toInt() to targetHeight
                    }
                } else {
                    targetWidth to targetHeight
                }
            }
            targetWidth != null -> {
                if (keepAspectRatio) {
                    val aspectRatio = originalWidth.toDouble() / originalHeight
                    targetWidth to (targetWidth / aspectRatio).toInt()
                } else {
                    targetWidth to originalHeight
                }
            }
            targetHeight != null -> {
                if (keepAspectRatio) {
                    val aspectRatio = originalWidth.toDouble() / originalHeight
                    (targetHeight * aspectRatio).toInt() to targetHeight
                } else {
                    originalWidth to targetHeight
                }
            }
            else -> originalWidth to originalHeight
        }
    }
}

