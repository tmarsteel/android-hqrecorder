package io.github.tmarsteel.hqrecorder.util

import android.os.Bundle
import android.os.PersistableBundle
import com.google.gson.GsonBuilder

val gson = GsonBuilder().create()

inline fun <reified T> Bundle.getGsonObject(key: String): T? {
    return getString(key)?.let { gson.fromJson<T>(it, T::class.java) }
}

inline fun <reified T> PersistableBundle.putGsonObject(key: String, value: T) {
    putString(key, gson.toJson(value, value::class.java))
}

inline fun <reified T> Bundle.putGsonObject(key: String, value: T) {
    putString(key, gson.toJson(value, value::class.java))
}