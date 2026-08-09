package dev.kotrace

/**
 * Severity of a [SpanEvent], ordered least → most severe. Capture is set-based, not threshold-based
 * (see [captureLevels]): the default keeps INFO and ERROR but drops the noisier DEBUG, which a
 * threshold at INFO could not express without also dropping WARN.
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }
