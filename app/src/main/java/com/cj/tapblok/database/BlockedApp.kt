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
    // Vira true quando o orçamento do dia acabou. Libera com a tag OU quando o cooldown termina.
    val lockedUntilScan: Boolean = false,
    // Tempo de espera (em minutos) até o app destravar sozinho depois que o orçamento acaba.
    // 0 = sem cooldown automático (só a tag libera).
    val cooldownMinutes: Int = 0,
    // Momento (System.currentTimeMillis()) em que a trava começou. 0 = não está travado.
    // É a partir daqui que o cooldown é contado.
    val lockedAtMillis: Long = 0
)

