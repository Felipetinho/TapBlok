package com.cj.tapblok.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey
    val packageName: String,
    // 0 = sem orçamento (app fica totalmente bloqueado, comportamento antigo).
    // >0 = app liberado até gastar essa quantidade de minutos por dia.
    val dailyBudgetMinutes: Int = 0,
    val usedMillisToday: Long = 0,
    // Dia (em "epoch day") em que os contadores acima foram zerados pela última vez.
    val lastResetEpochDay: Long = 0,
    // Vira true quando o orçamento do dia acabou; só um scan NFC libera.
    val lockedUntilScan: Boolean = false
)

