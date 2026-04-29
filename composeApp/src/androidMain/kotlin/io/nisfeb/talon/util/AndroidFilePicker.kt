package io.nisfeb.talon.util

/**
 * Stub Android impl — the real picker requires an
 * `ActivityResultRegistry` (via `LocalActivityResultRegistryOwner`)
 * and a Composable scope to host the `rememberLauncherForActivityResult`
 * call. Stage D wires that up inside the chat composer / profile
 * edit screen, replacing this stub at the call site.
 *
 * Calling `pickImage()` on this stub throws — if you hit it, you've
 * tried to invoke the picker from a non-Composable code path, which
 * the new architecture doesn't support.
 */
class AndroidFilePicker : FilePicker {
    override suspend fun pickImage(): PickedImage? {
        throw NotImplementedError(
            "AndroidFilePicker is a stub. Stage D's Compose-bound picker " +
                "replaces this. Use rememberLauncherForActivityResult + " +
                "PickVisualMedia from inside the Composable that needs it."
        )
    }

    override suspend fun pickAnyFile(): PickedImage? {
        throw NotImplementedError(
            "AndroidFilePicker is a stub. Use rememberAnyFilePicker() " +
                "from inside a Composable instead."
        )
    }
}
