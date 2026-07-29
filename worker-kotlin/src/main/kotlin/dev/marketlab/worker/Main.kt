package dev.marketlab.worker

import dev.marketlab.contracts.ArtifactId
import dev.marketlab.contracts.Sha256Digest
import dev.marketlab.contracts.worker.DatasetRef
import dev.marketlab.contracts.worker.FitPredictRequest
import dev.marketlab.contracts.worker.FitPredictResponse
import dev.marketlab.contracts.worker.PredictionArtifactRef
import dev.marketlab.contracts.worker.WorkerInvocationManifest
import dev.marketlab.contracts.worker.WorkerRuntime
import dev.marketlab.contracts.worker.WorkerTask
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.hipparchus.stat.regression.OLSMultipleLinearRegression
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    classDiscriminator = "type"
    prettyPrint = true
}

fun main(arguments: Array<String>) {
    val invocation = WorkerArguments.parse(arguments)
    val output = invocation.output.toAbsolutePath().normalize()
    output.createDirectories()

    val manifestText = Files.readString(invocation.manifest)
    val manifest = json.decodeFromString<WorkerInvocationManifest>(manifestText)
    require(output == Path(manifest.outputConstraints.outputDirectory).toAbsolutePath().normalize()) {
        "CLI output directory differs from the authorized manifest"
    }

    val response = runCatching { execute(manifest, output) }
        .getOrElse { failure ->
            val request = (manifest.task as WorkerTask.FitPredict).request
            FitPredictResponse.Failure(
                requestId = request.requestId,
                code = "WORKER_EXECUTION_FAILED",
                message = failure.message ?: failure::class.simpleName.orEmpty(),
                retryable = false,
            )
        }

    val resultPath = output.resolve("result.json")
    Files.writeString(
        resultPath,
        json.encodeToString<FitPredictResponse>(response),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
    require(directorySize(output) <= manifest.outputConstraints.maximumBytes) {
        "worker output exceeded the authorized byte limit"
    }
    if (response is FitPredictResponse.Failure) {
        error(response.message)
    }
}

private fun execute(
    manifest: WorkerInvocationManifest,
    output: Path,
): FitPredictResponse.Success {
    val request = (manifest.task as? WorkerTask.FitPredict)?.request
        ?: error("Unsupported worker task")
    require(request.runtime == WorkerRuntime.KOTLIN) { "Kotlin worker cannot execute another runtime" }
    require(Instant.now().toEpochMilli() < manifest.deadline.epochMillis) {
        "worker invocation deadline has expired"
    }
    require(
        manifest.outputConstraints.allowedMediaTypes.containsAll(
            listOf("application/json", "application/vnd.apache.parquet"),
        ),
    ) {
        "worker output contract does not authorize its required media types"
    }
    validateDataset(request.training.features, manifest)
    validateDataset(request.testFeatures, manifest)

    val trainingPath = authorizedInputPath(request.training.features)
    val testPath = authorizedInputPath(request.testFeatures)
    val predictionsPath = output.resolve("predictions.parquet")
    val predictions = DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        configureDeterminism(connection)
        fitPredict(connection, request, trainingPath, testPath)
            .also { writePredictions(connection, it, predictionsPath) }
    }
    require(predictions.isNotEmpty()) { "estimator emitted no predictions" }

    val digest = sha256(predictionsPath)
    return FitPredictResponse.Success(
        requestId = request.requestId,
        predictions = PredictionArtifactRef(
            artifactId = ArtifactId("predictions-${digest.hex.take(24)}"),
            uri = predictionsPath.toUri().toString(),
            contentHash = digest,
            rowIdColumn = request.testFeatures.rowIdColumn,
            predictionColumn = "prediction",
        ),
        diagnostics = mapOf(
            "runtime" to System.getProperty("java.runtime.version"),
            "estimator" to request.estimator,
            "rows" to predictions.size.toString(),
            "strictDeterminism" to "true",
        ),
    )
}

private data class Prediction(
    val rowId: String,
    val value: Double,
)

private fun fitPredict(
    connection: Connection,
    request: FitPredictRequest,
    trainingPath: Path,
    testPath: Path,
): List<Prediction> {
    val featureColumns = request.training.features.featureColumns
    featureColumns.forEach(::requireSafeColumn)
    requireSafeColumn(request.training.labelColumn)
    requireSafeColumn(request.testFeatures.rowIdColumn)
    val trainingRelation = parquetRelation(trainingPath)
    val testRelation = parquetRelation(testPath)

    return when (request.estimator) {
        "zero-return" -> readTestRows(connection, testRelation, request.testFeatures.rowIdColumn)
            .map { Prediction(it.first, 0.0) }

        "historical-mean" -> {
            val label = quoteIdentifier(request.training.labelColumn)
            val mean = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT avg($label)::DOUBLE FROM $trainingRelation").use { result ->
                    require(result.next()) { "training dataset is empty" }
                    result.getDouble(1).also { require(!result.wasNull() && it.isFinite()) }
                }
            }
            readTestRows(connection, testRelation, request.testFeatures.rowIdColumn)
                .map { Prediction(it.first, mean) }
        }

        "ols" -> fitOls(connection, request, trainingRelation, testRelation)
        else -> error("Estimator is not registered in kotlin-backtest-v1: ${request.estimator}")
    }
}

private fun fitOls(
    connection: Connection,
    request: FitPredictRequest,
    trainingRelation: String,
    testRelation: String,
): List<Prediction> {
    val features = request.training.features.featureColumns
    val label = quoteIdentifier(request.training.labelColumn)
    val featureSql = features.joinToString(", ") { "${quoteIdentifier(it)}::DOUBLE" }
    val training = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT $label::DOUBLE, $featureSql FROM $trainingRelation").use { result ->
            buildList {
                while (result.next()) {
                    val observed = result.getDouble(1)
                    val values = DoubleArray(features.size) { index -> result.getDouble(index + 2) }
                    require(observed.isFinite() && values.all(Double::isFinite)) {
                        "OLS input contains a non-finite value"
                    }
                    add(observed to values)
                }
            }
        }
    }
    require(training.size > features.size + 1) { "OLS has insufficient training observations" }
    val regression = OLSMultipleLinearRegression().apply {
        isNoIntercept = false
        newSampleData(
            training.map(Pair<Double, DoubleArray>::first).toDoubleArray(),
            training.map(Pair<Double, DoubleArray>::second).toTypedArray(),
        )
    }
    val beta = regression.estimateRegressionParameters()
    require(beta.size == features.size + 1 && beta.all(Double::isFinite)) {
        "OLS returned invalid coefficients"
    }

    val rowId = quoteIdentifier(request.testFeatures.rowIdColumn)
    return connection.createStatement().use { statement ->
        statement.executeQuery("SELECT $rowId::VARCHAR, $featureSql FROM $testRelation ORDER BY $rowId").use { result ->
            buildList {
                while (result.next()) {
                    val values = DoubleArray(features.size) { index -> result.getDouble(index + 2) }
                    require(values.all(Double::isFinite)) { "test input contains a non-finite value" }
                    val predicted = beta[0] + values.indices.sumOf { index -> beta[index + 1] * values[index] }
                    add(Prediction(result.getString(1), predicted))
                }
            }
        }
    }
}

private fun readTestRows(
    connection: Connection,
    relation: String,
    rowIdColumn: String,
): List<Pair<String, Unit>> {
    val rowId = quoteIdentifier(rowIdColumn)
    return connection.createStatement().use { statement ->
        statement.executeQuery("SELECT $rowId::VARCHAR FROM $relation ORDER BY $rowId").use { result ->
            buildList {
                while (result.next()) add(result.getString(1) to Unit)
            }
        }
    }
}

private fun writePredictions(
    connection: Connection,
    predictions: List<Prediction>,
    output: Path,
) {
    require(!output.exists()) { "prediction output already exists" }
    connection.createStatement().use { statement ->
        statement.execute("CREATE TABLE worker_predictions(row_id VARCHAR NOT NULL, prediction DOUBLE NOT NULL)")
    }
    connection.prepareStatement("INSERT INTO worker_predictions VALUES (?, ?)").use { insert ->
        predictions.forEach {
            insert.setString(1, it.rowId)
            insert.setDouble(2, it.value)
            insert.addBatch()
        }
        insert.executeBatch()
    }
    connection.createStatement().use { statement ->
        statement.execute(
            "COPY (SELECT * FROM worker_predictions ORDER BY row_id) TO " +
                "'${sqlString(output.absolutePathString())}' (FORMAT PARQUET, COMPRESSION ZSTD)",
        )
    }
}

private fun configureDeterminism(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute("SET threads = 1")
        statement.execute("SET preserve_insertion_order = true")
    }
}

private fun validateDataset(dataset: DatasetRef, manifest: WorkerInvocationManifest) {
    require(dataset.contentHash in manifest.immutableInputHashes) {
        "dataset hash is not authorized by the invocation manifest"
    }
    val path = authorizedInputPath(dataset)
    require(path.exists() && path.isRegularFile()) { "dataset does not exist" }
    require(sha256(path) == dataset.contentHash) { "dataset hash does not match its contract" }
}

private fun authorizedInputPath(dataset: DatasetRef): Path {
    val uri = URI(dataset.uri)
    require(uri.scheme == "file") { "worker datasets must use file URIs" }
    val path = Paths.get(uri).toAbsolutePath().normalize()
    val root = Path("/work/input").toAbsolutePath().normalize()
    require(path.startsWith(root)) { "dataset escaped the read-only input root" }
    val realRoot = root.toRealPath()
    val realPath = path.toRealPath()
    require(realPath.startsWith(realRoot)) { "dataset symlink escaped the read-only input root" }
    return realPath
}

private fun parquetRelation(path: Path): String =
    "read_parquet('${sqlString(path.absolutePathString())}')"

private fun requireSafeColumn(column: String) {
    require(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}").matches(column)) {
        "invalid dataset column name"
    }
}

private fun quoteIdentifier(identifier: String): String {
    requireSafeColumn(identifier)
    return "\"$identifier\""
}

private fun sqlString(value: String): String = value.replace("'", "''")

private fun sha256(path: Path): Sha256Digest {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return Sha256Digest(digest.digest().joinToString("") { "%02x".format(it) })
}

private fun directorySize(root: Path): Long =
    Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).mapToLong(Files::size).sum()
    }

private data class WorkerArguments(
    val manifest: Path,
    val output: Path,
) {
    companion object {
        fun parse(arguments: Array<String>): WorkerArguments {
            val args = arguments.toMutableList()
            if (args.firstOrNull() == "worker") args.removeFirst()
            require(args.size == 4 && args[0] == "--manifest" && args[2] == "--output") {
                "Usage: worker --manifest /work/input/manifest.json --output /work/output"
            }
            return WorkerArguments(
                manifest = Path(args[1]).toAbsolutePath().normalize(),
                output = Path(args[3]).toAbsolutePath().normalize(),
            )
        }
    }
}
