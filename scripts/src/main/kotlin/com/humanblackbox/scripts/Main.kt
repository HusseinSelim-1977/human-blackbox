package com.humanblackbox.scripts

import java.io.File

/**
 * Entry point for the non-Android helper module.
 *
 * This command intentionally performs no downloads. Dataset paths, licenses
 * and hardware are operator-supplied inputs; the scripts fail loudly rather
 * than inventing data or results.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "validate-manifest" -> validateManifest(args.getOrNull(1))
        "prepare-audio", "prepare-motion", "prepare-vision" -> {
            error("Use scripts/python/features.py with an operator-supplied dataset path")
        }
        "train", "convert" -> {
            error("Use scripts/python/train_all.py and convert_tflite.py with TensorFlow installed")
        }
        else -> printUsage()
    }
}

private fun validateManifest(path: String?) {
    requireNotNull(path) { "validate-manifest requires a JSON manifest path" }
    val text = File(path).readText()
    require("\"license\"" in text) { "Manifest must declare a license" }
    require("\"provenance\"" in text) { "Manifest must declare provenance" }
    println("Manifest $path declares license and provenance")
}

private fun printUsage() {
    println(
        """
        Human BlackBox v2 helper commands:
          validate-manifest <dataset-manifest.json>
          prepare-audio | prepare-motion | prepare-vision
          train | convert

        Python helpers live in scripts/python/. No network access is performed.
        """.trimIndent(),
    )
}
