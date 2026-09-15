package com.bridge.debate.files.infrastructure

import com.bridge.debate.files.application.FileStorage
import com.bridge.debate.files.application.MalwareScanner
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Default: bytes under a configurable local directory (docker-compose mounts it as a volume). */
@Component
@ConditionalOnProperty("app.storage.provider", havingValue = "local", matchIfMissing = true)
class LocalDiskStorage(
    @param:Value("\${app.storage.local.path:./data/uploads}") private val basePath: String,
) : FileStorage {

    private val root: Path = Path.of(basePath).toAbsolutePath().normalize().also { Files.createDirectories(it) }

    private fun resolve(key: String): Path {
        val path = root.resolve(key).normalize()
        require(path.startsWith(root)) { "Invalid storage key" } // no path traversal, ever
        return path
    }

    override fun store(key: String, content: InputStream, sizeBytes: Long, contentType: String) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        content.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    override fun open(key: String): InputStream = Files.newInputStream(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }
}

/**
 * S3-compatible storage: AWS S3 (leave endpoint empty), Cloudflare R2, or MinIO (set endpoint +
 * path-style). Selected with app.storage.provider=s3; credentials via env only.
 */
@Component
@ConditionalOnProperty("app.storage.provider", havingValue = "s3")
class S3FileStorage(
    @param:Value("\${app.storage.s3.bucket}") private val bucket: String,
    @Value("\${app.storage.s3.region:us-east-1}") region: String,
    @Value("\${app.storage.s3.endpoint:}") endpoint: String,
    @Value("\${app.storage.s3.access-key:}") accessKey: String,
    @Value("\${app.storage.s3.secret-key:}") secretKey: String,
    @Value("\${app.storage.s3.path-style:true}") pathStyle: Boolean,
) : FileStorage {

    private val client: S3Client = S3Client.builder()
        .region(Region.of(region))
        .apply {
            if (endpoint.isNotBlank()) endpointOverride(URI.create(endpoint))
            if (accessKey.isNotBlank()) {
                credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            }
            serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
        }
        .build()

    override fun store(key: String, content: InputStream, sizeBytes: Long, contentType: String) {
        client.putObject(
            { it.bucket(bucket).key(key).contentType(contentType) },
            RequestBody.fromInputStream(content, sizeBytes),
        )
    }

    override fun open(key: String): InputStream =
        client.getObject { it.bucket(bucket).key(key) }

    override fun delete(key: String) {
        client.deleteObject { it.bucket(bucket).key(key) }
    }
}

/** No-op scanner for local development; replace with a real engine (e.g. ClamAV) in production. */
@Component
class NoopMalwareScanner : MalwareScanner {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun scan(key: String, contentType: String, sizeBytes: Long) {
        log.debug("Malware scan skipped (no scanner configured) for key {}", key)
    }
}
