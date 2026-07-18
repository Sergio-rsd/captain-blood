package captainblood

import agent.indexing.DocumentIndex

/**
 * Выводит пронумерованный список источников в индексе с количеством чанков по стратегии
 * (используется командой `!sources` клиент/админ-режима).
 */
internal fun optPrintSources(index: DocumentIndex) {
    val sources = index.listSources()
    if (sources.isEmpty()) { println("  Индекс пуст."); return }
    println()
    println("  ${"№".padStart(3)}  ${"Источник".padEnd(46)} ${"fixed".padStart(5)}  ${"structural".padStart(10)}  ${"telegram".padStart(9)}")
    println("  ${"─".repeat(80)}")
    sources.forEachIndexed { i, s ->
        val f = index.countBySource(s, "fixed")
        val st = index.countBySource(s, "structural")
        val tg = index.countBySource(s, "telegram_thread")
        println("  ${(i + 1).toString().padStart(3)}  ${s.padEnd(46)} ${f.toString().padStart(5)}  ${st.toString().padStart(10)}  ${tg.toString().padStart(9)}")
    }
    println("  ${"─".repeat(80)}")
    println("  Итого: ${sources.size} источников, ${index.count()} чанков")
    println()
}
