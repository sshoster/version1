package com.bridge.debate.files.application

import java.io.InputStream

/**
 * Object-storage port (docs/security.md T12). Bytes live behind this interface; metadata always
 * stays in MongoDB. Implementations: local disk (default) and any S3-compatible service
 * (AWS S3, Cloudflare R2, MinIO, GCS interop) selected purely by configuration.
 */
interface FileStorage {
    fun store(key: String, content: InputStream, sizeBytes: Long, contentType: String)
    fun open(key: String): InputStream
    fun delete(key: String)
}

/** Malware-scanning hook (design doc §12): no-op locally, pluggable (e.g. ClamAV) in production. */
interface MalwareScanner {
    /** Throws if the content must be rejected. */
    fun scan(key: String, contentType: String, sizeBytes: Long)
}
