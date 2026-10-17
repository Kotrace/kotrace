package dev.kotrace.okhttp

import dev.kotrace.span
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The README's OkHttp snippets, compiled against this module. Each `README snippet` region is the README block
 * verbatim (imports aside); the core `ReadmeSnippetsTest` fails if the two drift. Never executed.
 */
@Suppress("unused", "UNUSED_VARIABLE", "UnusedVariable")
internal object ReadmeSnippets {

    private const val pricingUrl = "https://api.example.com/pricing"
    private fun routeTemplateOf(request: Request): String = request.url.encodedPath

    fun setup() {
        // README snippet: okhttp-setup
        // 1. Add the interceptor to the client.
        val http = OkHttpClient.Builder()
            .addInterceptor(TracingInterceptor())
            .build()

        // 2. Wrap the client (a Call.Factory) with the factory — use THIS to make calls.
        val calls: Call.Factory = TracingCallFactory(http)

        // 3. Call inside a span. traceparent goes on the wire; the span emits http.status as info
        //    (on every record off the span, not a filter key — ADR-001), and a non-2xx flips it to ERROR.
        suspend fun price(): String = span("pricing.fetch") {
            calls.newCall(Request.Builder().url(pricingUrl).build())
                .execute().use { it.body?.string().orEmpty() }
        }
        // end README snippet
    }

    fun spanName(http: OkHttpClient) {
        // README snippet: okhttp-span-name
        val calls = TracingCallFactory(http, spanName = { req -> "http ${req.method} ${routeTemplateOf(req)}" })  // e.g. /users/{id}
        // end README snippet
    }
}
