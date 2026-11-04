package dev.medusa.core

import kotlinx.serialization.Serializable

/** One entry of `android preview list --format=json`. */
@Serializable
data class PreviewInfo(
  val methodFqn: String,
  val previewParams: Map<String, String> = emptyMap(),
  val parameterProvider: ParameterProvider? = null,
) {
  /** `dev.app.ButtonKt` */
  val className: String get() = methodFqn.substringBeforeLast('.')

  /** `PrimaryButtonPreview` */
  val name: String get() = methodFqn.substringAfterLast('.')

  val hasParameterProvider: Boolean get() = parameterProvider != null
}

@Serializable
data class ParameterProvider(val provider: String)

@Serializable
data class ListResult(
  val success: Boolean = false,
  val globalError: String? = null,
  val previews: List<PreviewInfo> = emptyList(),
)

/** One entry of `android preview render --format=json`. A parameterized preview yields several. */
@Serializable
data class RenderedPreview(
  val previewId: String,
  val methodFqn: String,
  val imagePath: String? = null,
  val componentTreePath: String? = null,
  val error: RenderError? = null,
) {
  /** `ButtonKt/PrimaryButtonPreview_<index>.png`: one image per parameter value or multipreview entry, in declaration order. */
  val index: Int? get() = imagePath?.let { IMAGE_INDEX.find(it)?.groupValues?.get(1)?.toInt() }

  private companion object {
    val IMAGE_INDEX = Regex("""_(\d+)\.png$""")
  }
}

@Serializable
data class RenderError(val status: String? = null, val message: String? = null)

@Serializable
data class RenderResult(
  val success: Boolean = false,
  val globalError: String? = null,
  val results: List<RenderedPreview> = emptyList(),
)
