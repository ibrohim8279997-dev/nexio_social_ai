package com.nexiolab.socialai

import kotlinx.coroutines.flow.MutableStateFlow

/** Carries the OAuth redirect from the deep link to the Instagram screen. */
object InstagramCallback {
    val pending = MutableStateFlow<String?>(null)
}
