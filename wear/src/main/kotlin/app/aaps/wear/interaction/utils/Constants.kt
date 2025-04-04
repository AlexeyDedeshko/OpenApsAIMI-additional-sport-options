package app.aaps.wear.interaction.utils

@Suppress("unused")
object Constants {

    const val SECOND_IN_MS: Long = 1000
    const val MINUTE_IN_MS: Long = 60000
    const val HOUR_IN_MS: Long = 3600000
    const val DAY_IN_MS: Long = 86400000
    const val WEEK_IN_MS = DAY_IN_MS * 7
    const val MONTH_IN_MS = DAY_IN_MS * 30
    const val STALE_MS = MINUTE_IN_MS * 12

    const val TARGET_DEFAULT_SHIFT_EXERCISE_MODE = 80.0
    const val EXERCISE_MODE_PERCENTAGE_PRESET_1 = 80.0
    const val EXERCISE_MODE_PERCENTAGE_PRESET_2 = 60.0
    const val EXERCISE_MODE_PERCENTAGE_PRESET_3 = 30.0

    const val EXERCISE_MODE_DURATION_PRESET_1 = 30.0
    const val EXERCISE_MODE_DURATION_PRESET_2 = 50.0
    const val EXERCISE_MODE_DURATION_PRESET_3 = 80.0

    const val EXERCISE_MODE_TIMESHIFT_PRESET_1 = 30.0
    const val EXERCISE_MODE_TIMESHIFT_PRESET_2 = 60.0
    const val EXERCISE_MODE_TIMESHIFT_PRESET_3 = 90.0
}