package com.example

import android.app.Application
import android.content.ComponentName
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DispatchLogEntity
import com.example.data.local.SandeshDatabase
import com.example.data.local.ScheduledMessageEntity
import com.example.data.media.MediaStorageManager
import com.example.data.model.MediaAttachmentType
import com.example.data.model.RecurrenceType
import com.example.data.model.ScheduleStatus
import com.example.data.repository.SandeshRepository
import com.example.dispatch.AlarmScheduler
import com.example.dispatch.DispatchCoordinator
import com.example.dispatch.ActiveDispatchSession
import com.example.dispatch.PhoneNormalizer
import com.example.dispatch.RecurrenceCalculator
import com.example.oem.IndianOemBrand
import com.example.service.JitAccessibilityController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StressSimulationTest {

    @Test
    fun `benchmark 1 year and 2 year workload on real Room repository and core engines`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dbFile = app.getDatabasePath("stress_sim_2yr.db")
        if (dbFile.exists()) dbFile.delete()

        val db = Room.databaseBuilder(app, SandeshDatabase::class.java, "stress_sim_2yr.db")
            .allowMainThreadQueries()
            .build()

        val repo = SandeshRepository(
            db.scheduledMessageDao(),
            db.messageTemplateDao(),
            db.dispatchLogDao(),
            MediaStorageManager(app)
        )

        val baseTime = 1_760_000_000_000L
        val oneYearCount = 50 * 365 // 18,250
        val twoYearCount = 50 * 730 // 36,500

        // 1. Seed 1 Year (18,250 schedules + 18,250 logs) inside transaction for benchmark speed
        val year1InsertMs = measureNanoTime {
            db.runInTransaction {
                val sqlDb = db.openHelper.writableDatabase
                val schedStmt = sqlDb.compileStatement(
                    "INSERT INTO scheduled_messages (recipientName, phoneNumber, isGroup, targetPackage, targetUserSerial, targetInstanceLabel, messageBody, mediaType, mediaPath, mediaMimeType, mediaDisplayName, scheduledTimeMillis, recurrence, status, retryCount, lastError, retryAtMillis, anchorDayOfMonth, createdAtMillis, updatedAtMillis) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                )
                val logStmt = sqlDb.compileStatement(
                    "INSERT INTO dispatch_logs (scheduleId, recipientSnapshot, instanceLabel, messagePreview, hasMedia, executedAtMillis, isSuccess, durationMillis, detailMessage) VALUES (?,?,?,?,?,?,?,?,?)"
                )
                for (i in 1..oneYearCount) {
                    val ts = baseTime + i * 1_728_000L
                    schedStmt.clearBindings()
                    schedStmt.bindString(1, "Contact $i")
                    schedStmt.bindString(2, "+9198${(10000000 + i).toString().takeLast(8)}")
                    schedStmt.bindLong(3, if (i % 5 == 0) 1L else 0L)
                    schedStmt.bindString(4, if (i % 4 == 0) "com.whatsapp.w4b" else "com.whatsapp")
                    schedStmt.bindLong(5, if (i % 6 == 0) 999L else 0L)
                    schedStmt.bindString(6, if (i % 6 == 0) "WhatsApp (Clone)" else "WhatsApp")
                    schedStmt.bindString(7, "Scheduled invoice & reminder update #$i for today's dispatch batch.")
                    schedStmt.bindString(8, if (i % 3 == 0) MediaAttachmentType.IMAGE.name else MediaAttachmentType.NONE.name)
                    if (i % 3 == 0) {
                        schedStmt.bindString(9, "/data/user/0/pkg/files/scheduled_media/m_$i.jpg")
                        schedStmt.bindString(10, "image/jpeg")
                        schedStmt.bindString(11, "photo_$i.jpg")
                    } else {
                        schedStmt.bindNull(9)
                        schedStmt.bindNull(10)
                        schedStmt.bindNull(11)
                    }
                    schedStmt.bindLong(12, ts)
                    schedStmt.bindString(13, RecurrenceType.ONCE.name)
                    schedStmt.bindString(14, ScheduleStatus.SENT.name)
                    schedStmt.bindLong(15, 0L)
                    schedStmt.bindNull(16)
                    schedStmt.bindLong(17, 0L)
                    schedStmt.bindLong(18, 15L)
                    schedStmt.bindLong(19, ts - 60_000L)
                    schedStmt.bindLong(20, ts)
                    schedStmt.executeInsert()

                    logStmt.clearBindings()
                    logStmt.bindLong(1, i.toLong())
                    logStmt.bindString(2, "Contact $i")
                    logStmt.bindString(3, "WhatsApp")
                    logStmt.bindString(4, "Scheduled invoice & reminder update #$i")
                    logStmt.bindLong(5, if (i % 3 == 0) 1L else 0L)
                    logStmt.bindLong(6, ts)
                    logStmt.bindLong(7, 1L)
                    logStmt.bindLong(8, 2450L)
                    logStmt.bindString(9, "Delivered via WhatsApp")
                    logStmt.executeInsert()
                }
            }
        } / 1_000_000.0

        val year1SnapshotMs: Double
        val year1JsonMs: Double
        val year1JsonBytes: Int
        val year1DbBytes = dbFile.length()

        val snap1: List<ScheduledMessageEntity>
        year1SnapshotMs = measureNanoTime {
            snap1 = db.scheduledMessageDao().getAllSnapshot()
        } / 1_000_000.0
        assertEquals(oneYearCount, snap1.size)

        var json1 = ""
        year1JsonMs = measureNanoTime {
            json1 = repo.exportBackupJson()
        } / 1_000_000.0
        year1JsonBytes = json1.toByteArray(Charsets.UTF_8).size

        // 2. Extend to Year 2 (total 36,500 schedules + 36,500 logs)
        val year2InsertMs = measureNanoTime {
            db.runInTransaction {
                val sqlDb = db.openHelper.writableDatabase
                val schedStmt = sqlDb.compileStatement(
                    "INSERT INTO scheduled_messages (recipientName, phoneNumber, isGroup, targetPackage, targetUserSerial, targetInstanceLabel, messageBody, mediaType, mediaPath, mediaMimeType, mediaDisplayName, scheduledTimeMillis, recurrence, status, retryCount, lastError, retryAtMillis, anchorDayOfMonth, createdAtMillis, updatedAtMillis) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                )
                val logStmt = sqlDb.compileStatement(
                    "INSERT INTO dispatch_logs (scheduleId, recipientSnapshot, instanceLabel, messagePreview, hasMedia, executedAtMillis, isSuccess, durationMillis, detailMessage) VALUES (?,?,?,?,?,?,?,?,?)"
                )
                for (i in (oneYearCount + 1)..twoYearCount) {
                    val ts = baseTime + i * 1_728_000L
                    schedStmt.clearBindings()
                    schedStmt.bindString(1, "Contact $i")
                    schedStmt.bindString(2, "+9198${(10000000 + i).toString().takeLast(8)}")
                    schedStmt.bindLong(3, if (i % 5 == 0) 1L else 0L)
                    schedStmt.bindString(4, if (i % 4 == 0) "com.whatsapp.w4b" else "com.whatsapp")
                    schedStmt.bindLong(5, if (i % 6 == 0) 999L else 0L)
                    schedStmt.bindString(6, if (i % 6 == 0) "WhatsApp (Clone)" else "WhatsApp")
                    schedStmt.bindString(7, "Scheduled invoice & reminder update #$i for today's dispatch batch.")
                    schedStmt.bindString(8, MediaAttachmentType.NONE.name)
                    schedStmt.bindNull(9)
                    schedStmt.bindNull(10)
                    schedStmt.bindNull(11)
                    schedStmt.bindLong(12, ts)
                    schedStmt.bindString(13, RecurrenceType.ONCE.name)
                    schedStmt.bindString(14, if (i > twoYearCount - 50) ScheduleStatus.PENDING.name else ScheduleStatus.SENT.name)
                    schedStmt.bindLong(15, 0L)
                    schedStmt.bindNull(16)
                    schedStmt.bindLong(17, 0L)
                    schedStmt.bindLong(18, 15L)
                    schedStmt.bindLong(19, ts - 60_000L)
                    schedStmt.bindLong(20, ts)
                    schedStmt.executeInsert()

                    logStmt.clearBindings()
                    logStmt.bindLong(1, i.toLong())
                    logStmt.bindString(2, "Contact $i")
                    logStmt.bindString(3, "WhatsApp")
                    logStmt.bindString(4, "Scheduled invoice & reminder update #$i")
                    logStmt.bindLong(5, 0L)
                    logStmt.bindLong(6, ts)
                    logStmt.bindLong(7, 1L)
                    logStmt.bindLong(8, 2300L)
                    logStmt.bindString(9, "Delivered via WhatsApp")
                    logStmt.executeInsert()
                }
            }
        } / 1_000_000.0

        val snap2: List<ScheduledMessageEntity>
        val year2SnapshotMs = measureNanoTime {
            snap2 = db.scheduledMessageDao().getAllSnapshot()
        } / 1_000_000.0
        assertEquals(twoYearCount, snap2.size)

        val year2PendingMs: Double
        val pending2: List<ScheduledMessageEntity>
        year2PendingMs = measureNanoTime {
            pending2 = db.scheduledMessageDao().getByStatus(ScheduleStatus.PENDING.name)
        } / 1_000_000.0

        val year2RecordOutcomeMs = measureNanoTime {
            repo.recordDispatchOutcome(
                scheduleId = pending2.first().id,
                success = true,
                durationMillis = 2400L,
                detailMessage = "Delivered via WhatsApp"
            )
        } / 1_000_000.0

        var json2 = ""
        val year2JsonMs = measureNanoTime {
            json2 = repo.exportBackupJson()
        } / 1_000_000.0
        val year2JsonBytes = json2.toByteArray(Charsets.UTF_8).size
        val year2DbBytes = dbFile.length()

        // 3. Benchmark RecurrenceCalculator (50 recurring items x 730 days = 36,500 hops)
        val recurrenceMs = measureNanoTime {
            for (item in 1..50) {
                var t = baseTime
                for (day in 1..730) {
                    t = RecurrenceCalculator.computeNextOccurrence(t, RecurrenceType.DAILY, t + 1000L)!!
                }
            }
        } / 1_000_000.0

        // 4. Benchmark PhoneNormalizer + JIT string helper over 36,500 dispatches
        val talkBack = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        val comp = ComponentName(app.packageName, "com.example.service.WhatsAppAccessibilityService")
        val jitStringOpsMs = measureNanoTime {
            for (i in 1..twoYearCount) {
                PhoneNormalizer.normalizeForWhatsApp("+91 98765 43210")
                val added = JitAccessibilityController.addComponentToEnabledList(talkBack, comp)
                JitAccessibilityController.removeComponentFromEnabledList(added, comp)
            }
        } / 1_000_000.0

        // 5. Verify rolling 250-alarm window with 300 DB rows + FIFO queue serialization
        DispatchCoordinator.clearAll()
        assertTrue(DispatchCoordinator.enqueueSchedule(101L))
        assertTrue(DispatchCoordinator.enqueueSchedule(102L))
        val firstDequeued = DispatchCoordinator.dequeueNextSchedule()
        val secondDequeued = DispatchCoordinator.dequeueNextSchedule()
        val hydratedCount = AlarmScheduler.hydrateAlarmWindow(app, repo)

        println("=== BENCHMARK_METRICS_START ===")
        println("YEAR1_ROWS=$oneYearCount, DB_MB=${"%.2f".format(year1DbBytes / 1048576.0)}, SNAPSHOT_MS=${"%.2f".format(year1SnapshotMs)}, JSON_EXPORT_MS=${"%.2f".format(year1JsonMs)}, JSON_MB=${"%.2f".format(year1JsonBytes / 1048576.0)}")
        println("YEAR2_ROWS=$twoYearCount, DB_MB=${"%.2f".format(year2DbBytes / 1048576.0)}, SNAPSHOT_MS=${"%.2f".format(year2SnapshotMs)}, PENDING_QUERY_MS=${"%.2f".format(year2PendingMs)}, OUTCOME_WRITE_MS=${"%.2f".format(year2RecordOutcomeMs)}, JSON_EXPORT_MS=${"%.2f".format(year2JsonMs)}, JSON_MB=${"%.2f".format(year2JsonBytes / 1048576.0)}")
        println("RECURRENCE_36500_HOPS_MS=${"%.2f".format(recurrenceMs)}, JIT_PHONE_36500_MS=${"%.2f".format(jitStringOpsMs)}")
        println("HYDRATED_WINDOW=$hydratedCount, FIFO_ORDER=${firstDequeued}->${secondDequeued}")
        println("=== BENCHMARK_METRICS_END ===")

        db.close()
        dbFile.delete()
        assertTrue(firstDequeued == 101L && secondDequeued == 102L)
        assertTrue(hydratedCount <= AlarmScheduler.MAX_ACTIVE_ALARMS_WINDOW)
    }

    @Test
    fun `simulate 1000 Indian users x 50 daily schedules over 1 and 2 years`() {
        val rng = Random(20261115)

        data class CohortProfile(
            val label: String,
            val brand: IndianOemBrand,
            val count: Int,
            val oemShieldRate: Double,
            val jitRate: Double,
            val manualPauseRate: Double,
            val ramMb: Int,
            val aggressiveKillBaseProb: Double // per-dispatch kill risk if OEM shield NOT completed
        )

        val cohorts = listOf(
            CohortProfile("Xiaomi / Redmi / POCO (MIUI/HyperOS)", IndianOemBrand.XIAOMI, 260, oemShieldRate = 0.72, jitRate = 0.35, manualPauseRate = 0.45, ramMb = 6144, aggressiveKillBaseProb = 0.28),
            CohortProfile("Samsung (One UI)", IndianOemBrand.SAMSUNG, 200, oemShieldRate = 0.80, jitRate = 0.35, manualPauseRate = 0.45, ramMb = 6144, aggressiveKillBaseProb = 0.14),
            CohortProfile("Vivo / iQOO (FuntouchOS/OriginOS)", IndianOemBrand.VIVO, 180, oemShieldRate = 0.68, jitRate = 0.30, manualPauseRate = 0.50, ramMb = 6144, aggressiveKillBaseProb = 0.32),
            CohortProfile("Oppo / Realme / OnePlus (ColorOS)", IndianOemBrand.REALME, 240, oemShieldRate = 0.74, jitRate = 0.35, manualPauseRate = 0.45, ramMb = 8192, aggressiveKillBaseProb = 0.24),
            CohortProfile("Motorola / Nothing / Pixel / Stock", IndianOemBrand.MOTOROLA, 120, oemShieldRate = 0.90, jitRate = 0.40, manualPauseRate = 0.45, ramMb = 8192, aggressiveKillBaseProb = 0.04)
        )

        var totalUsers = 0
        var totalAttempted2Yr = 0L
        var totalSucceeded2Yr = 0L
        var failedCollision2Yr = 0L
        var failedOemKill2Yr = 0L
        var failedManualPaused2Yr = 0L
        var failedAlarmCap2Yr = 0L
        var failedWaUiDriftOrNetwork2Yr = 0L
        var bankingWarnIncidentsIdleJit = 0L
        var bankingWarnIncidentsManualAlwaysOn = 0L
        var bankingWarnIncidentsManualTile = 0L

        val perOemSummary = StringBuilder()

        for (cohort in cohorts) {
            var oemAttempts = 0L
            var oemSuccess = 0L
            var oemCollisions = 0L
            var oemKills = 0L

            for (u in 0 until cohort.count) {
                totalUsers++
                val hasOemShield = rng.nextDouble() < cohort.oemShieldRate
                val modeRoll = rng.nextDouble()
                val isJit = modeRoll < cohort.jitRate
                val usesQuickTile = !isJit && modeRoll < (cohort.jitRate + cohort.manualPauseRate)
                val isBurstyBatchUser = rng.nextDouble() < 0.25 // 25% schedule batches in tight morning/evening windows
                val preSchedulesOneTimeYearBulk = rng.nextDouble() < 0.10 // 10% load >500 ONCE rows ahead of time

                // Simulate 730 days (2 years) x 50 messages/day at day-level fidelity
                for (day in 0 until 730) {
                    // Generate 50 minute slots in 07:00..23:00 (960 mins) or bursty 08:30..09:30 (60 mins)
                    val minuteCounts = HashMap<Int, Int>(64)
                    for (m in 0 until 50) {
                        val slot = if (isBurstyBatchUser && m < 15) {
                            rng.nextInt(510, 540) // 30-min morning batch
                        } else {
                            rng.nextInt(420, 1380) // spread across 16 waking hours
                        }
                        minuteCounts[slot] = (minuteCounts[slot] ?: 0) + 1
                    }

                    // Each slot > 1 drops overlapping dispatches due to single-slot DispatchCoordinator
                    var dayCollisions = 0
                    for ((_, cnt) in minuteCounts) {
                        if (cnt > 1) {
                            dayCollisions += (cnt - 1)
                        }
                    }

                    val activeSlots = 50 - dayCollisions
                    oemAttempts += 50
                    totalAttempted2Yr += 50
                    oemCollisions += dayCollisions
                    failedCollision2Yr += dayCollisions

                    // Banking app opens (avg 2/day across UPI users)
                    if (isJit) {
                        // Only ~0.2% overlap if user opens GPay during exact 4s send window
                        if (rng.nextDouble() < 0.002) bankingWarnIncidentsIdleJit++
                    } else if (usesQuickTile) {
                        // Forgot to tap Quick Tile ~12% of days
                        if (rng.nextDouble() < 0.12) bankingWarnIncidentsManualTile++
                    } else {
                        bankingWarnIncidentsManualAlwaysOn += 2
                    }

                    for (s in 0 until activeSlots) {
                        when {
                            preSchedulesOneTimeYearBulk && s >= 10 -> {
                                // On bulk ONCE queues >500 alarms after a reboot (~1 reboot/45 days)
                                if (day % 45 > 0 && day % 45 < 5) {
                                    failedAlarmCap2Yr++
                                } else {
                                    if (!hasOemShield && rng.nextDouble() < cohort.aggressiveKillBaseProb) {
                                        failedOemKill2Yr++
                                        oemKills++
                                    } else if (rng.nextDouble() < 0.008) {
                                        failedWaUiDriftOrNetwork2Yr++
                                    } else {
                                        totalSucceeded2Yr++
                                        oemSuccess++
                                    }
                                }
                            }
                            usesQuickTile && rng.nextDouble() < 0.025 -> {
                                // User paused Quick Tile for UPI and forgot to re-enable for 1 dispatch
                                failedManualPaused2Yr++
                            }
                            !hasOemShield && rng.nextDouble() < cohort.aggressiveKillBaseProb -> {
                                failedOemKill2Yr++
                                oemKills++
                            }
                            rng.nextDouble() < 0.008 -> {
                                // Transient WhatsApp update layout shift / lock bouncer retry miss
                                failedWaUiDriftOrNetwork2Yr++
                            }
                            else -> {
                                totalSucceeded2Yr++
                                oemSuccess++
                            }
                        }
                    }
                }
            }
            val pct = (oemSuccess * 100.0) / oemAttempts
            perOemSummary.appendLine(
                "OEM=${cohort.brand.name}, USERS=${cohort.count}, ATTEMPTS=$oemAttempts, SUCCESS=$oemSuccess (${"%.2f".format(pct)}%), COLLISIONS=$oemCollisions, OEM_KILLS=$oemKills"
            )
        }

        val overallSuccessPct = (totalSucceeded2Yr * 100.0) / totalAttempted2Yr
        println("=== COHORT_SIMULATION_START ===")
        println("TOTAL_USERS=$totalUsers, TOTAL_ATTEMPTS_2YR=$totalAttempted2Yr, TOTAL_SUCCESS_2YR=$totalSucceeded2Yr (${"%.2f".format(overallSuccessPct)}%)")
        println("FAIL_COLLISION=$failedCollision2Yr, FAIL_OEM_KILL=$failedOemKill2Yr, FAIL_MANUAL_PAUSED=$failedManualPaused2Yr, FAIL_ALARM_500_CAP=$failedAlarmCap2Yr, FAIL_WA_UI_TRANSIENT=$failedWaUiDriftOrNetwork2Yr")
        println("BANK_WARNS_JIT=$bankingWarnIncidentsIdleJit, BANK_WARNS_QUICK_TILE=$bankingWarnIncidentsManualTile, BANK_WARNS_ALWAYS_ON=$bankingWarnIncidentsManualAlwaysOn")
        print(perOemSummary.toString())
        println("=== COHORT_SIMULATION_END ===")

        assertEquals(1000, totalUsers)
        assertEquals(36_500_000L, totalAttempted2Yr)
    }
}
