package com.craftersy.treadmill.workout.data

import android.content.Context
import com.craftersy.treadmill.workout.R

/**
 * Workouts offered on an empty library, so there is something to run and to
 * learn the format from. They live in res/raw, translated in res/raw-pl.
 */
private val SAMPLES = listOf(R.raw.sample_intervals, R.raw.sample_hill_walk, R.raw.sample_pyramid)

/** The sample workout files in [context]'s language. */
fun samples(context: Context): List<String> =
    SAMPLES.map { id -> context.resources.openRawResource(id).use { it.readBytes().decodeToString() } }
