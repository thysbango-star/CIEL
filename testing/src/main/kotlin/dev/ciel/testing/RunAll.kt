package dev.ciel.testing

import kotlin.system.exitProcess

fun main() {
    coreTests()
    providerTests()
    taskTests()
    skillTests()
    memoryTests()
    commsTests()
    gameTests()
    researchTests()
    engineTests()
    setupTests()
    val failed = T.runAll()
    exitProcess(if (failed == 0) 0 else 1)
}
