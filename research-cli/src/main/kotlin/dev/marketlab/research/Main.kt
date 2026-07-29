package dev.marketlab.research

import kotlinx.coroutines.runBlocking

fun main(arguments: Array<String>) {
    val config = try {
        ResearchCliParser.parse(arguments)
    } catch (help: HelpRequestedException) {
        println(help.message)
        return
    }
    val report = runBlocking {
        FundingResearchSuite(config).run()
    }
    val artifact = ReportArtifactWriter(config.artifactRoot).write(report)
    println("report=${artifact.reportPath}")
    println("sha256=${artifact.sha256}")
    println("checksum=${artifact.checksumPath}")
    println("bytes=${artifact.byteCount}")
}
