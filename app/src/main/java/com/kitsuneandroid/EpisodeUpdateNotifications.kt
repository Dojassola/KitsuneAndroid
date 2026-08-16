package com.kitsuneandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object EpisodeUpdateNotifications {
    private const val PREFERENCES = "episode_updates"
    private const val ENABLED = "enabled"
    private const val EPISODE_PREFIX = "episode:"
    private const val CHANNEL_ID = "episode_updates"
    private const val JOB_ID = 0x4b17
    private const val INTERVAL_MS = 12 * 60 * 60 * 1_000L

    fun enabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()

        if (enabled) {
            schedule(context)
        } else {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }

    fun ensureScheduled(context: Context) {
        if (enabled(context)) {
            schedule(context)
        }
    }

    fun check(context: Context) {
        val trackedIds = MediaListRepository.trackedItems(context)
            .map(Anime::id)
            .filter { id -> id > 0 }
            .toSet()

        if (trackedIds.isEmpty()) {
            return
        }

        val anime = trackedIds.chunked(50).flatMap { ids ->
            AnimeApi.favorites(ids.toSet())
        }
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val editor = preferences.edit()

        anime.forEach { item ->
            val releasedEpisodes = releasedEpisodes(item) ?: return@forEach
            val key = "$EPISODE_PREFIX${item.id}"
            val previousEpisodes = preferences.getInt(key, -1)

            if (previousEpisodes >= 0 && releasedEpisodes > previousEpisodes) {
                notify(context, item, releasedEpisodes)
            }

            if (releasedEpisodes > previousEpisodes) {
                editor.putInt(key, releasedEpisodes)
            }
        }
        editor.apply()
    }

    private fun schedule(context: Context) {
        val service = ComponentName(context, EpisodeUpdateJobService::class.java)
        val job = JobInfo.Builder(JOB_ID, service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(INTERVAL_MS)
            .build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }

    internal fun releasedEpisodes(anime: Anime): Int? {
        return when (anime.status) {
            "NOT_YET_RELEASED" -> 0
            "RELEASING" -> anime.nextAiringEpisode?.minus(1)?.coerceAtLeast(0)
            else -> anime.episodes
        }
    }

    private fun notify(context: Context, anime: Anime, episode: Int) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.episode_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val notificationIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ANIME_ID, anime.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val intent = PendingIntent.getActivity(
            context,
            anime.id,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(anime.title)
            .setContentText(context.getString(R.string.new_episode_available, episode))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        manager.notify(anime.id, notification)
    }
}

internal class EpisodeUpdateJobService : JobService() {
    private var work: Job? = null

    override fun onStartJob(parameters: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch {
            var shouldReschedule = false

            try {
                EpisodeUpdateNotifications.check(this@EpisodeUpdateJobService)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                shouldReschedule = true
            } finally {
                jobFinished(parameters, shouldReschedule)
            }
        }
        return true
    }

    override fun onStopJob(parameters: JobParameters): Boolean {
        work?.cancel()
        work = null
        return true
    }

    override fun onDestroy() {
        work?.cancel()
        super.onDestroy()
    }
}
