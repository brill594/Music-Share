package com.musicshare.android.poweramp

object PowerampContract {
    const val actionTrackChanged = "com.maxmpz.audioplayer.TRACK_CHANGED"
    const val actionTrackChangedExplicit = "com.maxmpz.audioplayer.TRACK_CHANGED_EXPLICIT"
    const val actionStatusChanged = "com.maxmpz.audioplayer.STATUS_CHANGED"
    const val actionStatusChangedExplicit = "com.maxmpz.audioplayer.STATUS_CHANGED_EXPLICIT"

    const val extraTimestamp = "ts"
    const val extraState = "state"
    const val extraPaused = "paused"
    const val extraId = "id"
    const val extraTrack = "track"

    const val trackId = "id"
    const val trackPath = "path"
    const val trackTitle = "title"
    const val trackArtist = "artist"
    const val trackAlbum = "album"
    const val trackDurationSeconds = "dur"
    const val trackDurationMs = "durMs"

    const val stateStopped = 0
    const val statePlaying = 1
    const val statePaused = 2
}
