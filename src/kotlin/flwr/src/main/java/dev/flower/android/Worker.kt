package dev.flower.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.UUID
import java.util.concurrent.TimeUnit

class FlwrWorker(private val context: Context,
                 workerParams: WorkerParameters,
                 private val flwrClient: Client,
                 private val grpcRere: Boolean) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        if (grpcRere) {
            inputData.getString("serverAddress")?.let { startFlowerRere(it, false, flwrClient) }
        } else {
            inputData.getString("serverAddress")?.let { startClient(it, false, flwrClient) }
        }
        val workManager: WorkManager = WorkManager.getInstance(context)
        workManager.cancelWorkById(id)
        return Result.success()
    }

    /**
     * Start a PeriodicWorker that runs Flower client in the background.
     *
     * @param interval The interval for the PeriodicWorker to resume its work in minutes.
     * @param serverAddress The IPv4 or IPv6 address of the server. If the Flower server runs on the
     * same machine on port 8080, then server_address would be “[::]:8080”.
     */
    fun startFlwrPeriodicWorker(interval: Long, serverAddress: String) {
        val constraints: Constraints = Constraints.Builder().build()

        val workRequest: PeriodicWorkRequest = PeriodicWorkRequestBuilder<FlwrWorker>(
             interval, TimeUnit.MINUTES
        )
            .setInitialDelay(0, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString("serverAddress", serverAddress)
                    .build()
            )
            .setConstraints(constraints)
            .build()

        val uniqueWorkName = "FlwrWorker${UUID.randomUUID()}"

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(uniqueWorkName, ExistingPeriodicWorkPolicy.KEEP, workRequest)
    }

}
