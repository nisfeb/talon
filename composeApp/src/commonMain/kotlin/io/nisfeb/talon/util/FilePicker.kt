package io.nisfeb.talon.util

/**
 * Platform-agnostic image picker. The desktop implementation uses
 * `javax.swing.JFileChooser`. The Android implementation requires
 * an `ActivityResultRegistry` to drive `PickVisualMedia` and is
 * therefore stubbed at this stage — Stage D wires the actual
 * picker through `LocalActivityResultRegistryOwner` from inside the
 * affected Composables.
 */
interface FilePicker {
    /**
     * Open the host's image-picker UI, suspending until the user
     * picks a file or cancels. Returns null on cancel or any failure.
     * Implementations must NOT throw — return null instead so call
     * sites can fall through to "user dismissed".
     */
    suspend fun pickImage(): PickedImage?

    /**
     * Like [pickImage] but with no extension filter — suitable for
     * sending arbitrary attachments (PDFs, archives, etc.). The
     * returned [PickedImage]'s mime type comes from the OS where
     * available, otherwise application/octet-stream.
     */
    suspend fun pickAnyFile(): PickedImage?
}

/**
 * A picked image's bytes, MIME type, and display name. Callers
 * (e.g. the chat composer) hand the bytes to S3Uploader to upload
 * and get back a URL to embed in the message.
 */
data class PickedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val displayName: String,
) {
    // ByteArray needs equals/hashCode disabled to keep this a sane
    // data class. Compose recompositions never compare PickedImage
    // values for structural equality (it flows through state once
    // and disappears), but Kotlin's data class default for ByteArray
    // is reference equality which would break test fixtures.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
