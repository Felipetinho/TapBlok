package com.cj.tapblok.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(blockedApp: BlockedApp)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(blockedApps: List<BlockedApp>)

    @Query("DELETE FROM blocked_apps")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(blockedApp: BlockedApp)

    @Query("SELECT * FROM blocked_apps")
    fun getAllBlockedApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps")
    suspend fun getAllBlockedAppsList(): List<BlockedApp>

    // Define o orçamento diário do app, em minutos. 0 = sem orçamento (bloqueio comum).
    @Query("UPDATE blocked_apps SET dailyBudgetMinutes = :minutes WHERE packageName = :pkg")
    suspend fun setBudgetMinutes(pkg: String, minutes: Int)

    // Define o tempo de cooldown do app, em minutos (ex: 180 = 3h).
    @Query("UPDATE blocked_apps SET cooldownMinutes = :minutes WHERE packageName = :pkg")
    suspend fun setCooldownMinutes(pkg: String, minutes: Int)

    // Chamado pelo loop de monitoramento pra salvar o tempo usado no dia.
    @Query(
        "UPDATE blocked_apps SET usedMillisToday = :usedMillis, " +
            "lastResetEpochDay = :day WHERE packageName = :pkg"
    )
    suspend fun updateUsage(pkg: String, usedMillis: Long, day: Long)

    // Chamado quando o orçamento acaba: liga a trava e grava o horário em que ela começou.
    @Query("UPDATE blocked_apps SET lockedUntilScan = 1, lockedAtMillis = :now WHERE packageName = :pkg")
    suspend fun lockApp(pkg: String, now: Long)

    // Chamado pela tag OU pelo fim do cooldown: desliga a trava e zera o tempo usado (novo período).
    @Query("UPDATE blocked_apps SET lockedUntilScan = 0, lockedAtMillis = 0, usedMillisToday = 0 WHERE packageName = :pkg")
    suspend fun clearLock(pkg: String)
}
