package dev.openrune.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Global Gson instance with consistent configuration
 */
val json: Gson = GsonBuilder().create()

val jsonNoPretty: Gson = json

