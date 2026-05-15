package io.github.nobooooody.intent_modifier.compiler

import java.io.File
import java.io.PrintWriter

class JavaPrintWriter(file: File) : PrintWriter(file) {
    constructor(fileName: String) : this(File(fileName))
}