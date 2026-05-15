package io.github.nobooooody.intent_modifier.engine

import android.content.Intent

interface IRule {
    fun evaluate(intent: Intent, result: Intent): Boolean
    fun execute(intent: Intent, result: Intent)
}