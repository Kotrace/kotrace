package dev.kotrace.okhttp

/**
 * The span keys this module owns. [LAYER] is a **fixed** filter attribute set at span birth
 * ([TracingCallFactory]); [STATUS] is late **emitted info** written on completion ([TracingInterceptor]),
 * never a filter key (ADR-001). Constants, not an enum: the values ride a `Map<String, String>`, and
 * kotrace holds no closed attribute vocabulary — these are only this adapter's own conventions.
 */
internal object HttpSpan {
    const val LAYER = "layer"
    const val LAYER_HTTP = "http"
    const val STATUS = "http.status"
}
