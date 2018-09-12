/*
 * Copyright (C) 2017-2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.scanner.scanners

import ch.frankel.slf4k.*

import com.fasterxml.jackson.databind.JsonNode

import com.here.ort.model.EMPTY_JSON_NODE
import com.here.ort.model.LicenseFinding
import com.here.ort.model.Provenance
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanSummary
import com.here.ort.model.ScannerDetails
import com.here.ort.model.config.ScannerConfiguration
import com.here.ort.model.yamlMapper
import com.here.ort.scanner.LocalScanner
import com.here.ort.scanner.ScanException
import com.here.ort.scanner.AbstractScannerFactory
import com.here.ort.scanner.HTTP_CACHE_PATH
import com.here.ort.utils.CommandLineTool2
import com.here.ort.utils.OkHttpClientHelper
import com.here.ort.utils.OS
import com.here.ort.utils.log

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.time.Instant

import okhttp3.Request

import okio.Okio

object AskalonoCommand : CommandLineTool2() {
    override fun command(workingDir: File?): String {
        val extension = when {
            OS.isLinux -> "linux"
            OS.isMac -> "osx"
            OS.isWindows -> "exe"
            else -> throw IllegalArgumentException("Unsupported operating system.")
        }

        return "askalono.$extension"
    }

    override fun transformVersion(output: String) =
            // "askalono --version" returns a string like "askalono 0.2.0-beta.1", so simply remove the prefix.
            output.substringAfter("askalono ")
}

class Askalono(config: ScannerConfiguration) : LocalScanner(config, AskalonoCommand) {
    class Factory : AbstractScannerFactory<Askalono>() {
        override fun create(config: ScannerConfiguration) = Askalono(config)
    }

    // This is used in bootstrap() which is called by the base classes' constructor, so it has to have a getter to get
    // dynamically evaluated before this scanner is fully initialized.
    override val requiredVersion
        get() = "0.2.0"

    override val resultFileExt = "txt"

    override fun bootstrap(): File {
        val name = scanner.command()
        val url = "https://github.com/amzn/askalono/releases/download/$requiredVersion/$name"

        log.info { "Downloading $this from '$url'... " }

        val request = Request.Builder().get().url(url).build()

        return OkHttpClientHelper.execute(HTTP_CACHE_PATH, request).use { response ->
            val body = response.body()

            if (response.code() != HttpURLConnection.HTTP_OK || body == null) {
                throw IOException("Failed to download $this from $url.")
            }

            if (response.cacheResponse() != null) {
                log.info { "Retrieved $this from local cache." }
            }

            val scannerDir = createTempDir().apply { deleteOnExit() }

            val scannerFile = File(scannerDir, name)
            Okio.buffer(Okio.sink(scannerFile)).use { it.writeAll(body.source()) }

            if (!OS.isWindows) {
                // Ensure the executable Unix mode bit to be set.
                scannerFile.setExecutable(true)
            }

            scannerDir
        }
    }

    override fun getConfiguration() = ""

    override fun scanPath(scannerDetails: ScannerDetails, path: File, provenance: Provenance, resultsFile: File)
            : ScanResult {
        try {
            val startTime = Instant.now()
            val process = scanner.run("crawl", path.absolutePath)
            val endTime = Instant.now()

            process.stdoutFile.copyTo(resultsFile)
            val result = getResult(resultsFile)
            val summary = generateSummary(startTime, endTime, result)
            return ScanResult(provenance, scannerDetails, summary, result)
        } catch (e: IOException) {
            throw ScanException(e.message)
        }
    }

    override fun getResult(resultsFile: File): JsonNode {
        if (!resultsFile.isFile || resultsFile.length() == 0L) return EMPTY_JSON_NODE

        val yamlNodes = resultsFile.readLines().chunked(3) { (path, license, score) ->
            val licenseNoOriginalText = license.substringBeforeLast(" (original text)")
            val yamlString = listOf("Path: $path", licenseNoOriginalText, score).joinToString("\n")
            yamlMapper.readTree(yamlString)
        }

        return yamlMapper.createArrayNode().apply { addAll(yamlNodes) }
    }

    override fun generateSummary(startTime: Instant, endTime: Instant, result: JsonNode): ScanSummary {
        val findings = result.map { LicenseFinding(it["License"].textValue()) }.toSortedSet()
        return ScanSummary(startTime, endTime, result.size(), findings, errors = mutableListOf())
    }
}
