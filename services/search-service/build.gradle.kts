import java.net.URI

plugins {
    id("ecommerce.microservice")
}

// No database: the read model lives in Elasticsearch, fed by product.events (CQRS, ADR-0011).
dependencies {
    implementation(project(":messaging"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("co.elastic.clients:elasticsearch-java")
    // Query and product embeddings computed in-process (ADR-0012): no external API, no paid ES ML features.
    implementation(libs.onnxruntime)
    implementation(libs.djl.tokenizers)

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:elasticsearch")
    testImplementation(libs.wiremock.standalone)
}

/**
 * multilingual-e5-small (int8 ONNX export, 118 MB) and its tokenizer, downloaded once into <repo>/models (git-ignored).
 * Tests read it from there; compose mounts the same folder into search-service.
 */
val modelDir = rootProject.layout.projectDirectory.dir("models/multilingual-e5-small")
val downloadEmbeddingModel by tasks.registering {
    val base = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main"
    val files = mapOf("model.onnx" to "$base/onnx/model_quantized.onnx", "tokenizer.json" to "$base/tokenizer.json")
    outputs.files(files.keys.map { modelDir.file(it) })
    doLast {
        files.forEach { (name, url) ->
            val target = modelDir.file(name).asFile
            if (!target.exists()) {
                target.parentFile.mkdirs()
                logger.lifecycle("Downloading $url")
                val partial = File(target.path + ".part")
                URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                partial.renameTo(target)
            }
        }
    }
}

tasks.named<Test>("test") {
    dependsOn(downloadEmbeddingModel)
    systemProperty("ecommerce.search.embedding.model-dir", modelDir.asFile.absolutePath)
}
