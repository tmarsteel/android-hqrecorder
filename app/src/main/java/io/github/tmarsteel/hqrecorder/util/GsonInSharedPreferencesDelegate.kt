package io.github.tmarsteel.hqrecorder.util

import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.GsonBuilder
import kotlin.reflect.KProperty

class GsonInSharedPreferencesDelegate<T : Any>(
    val typeOfT: Class<T>,
    val getPreferences: () -> SharedPreferences,
    val getDefault: () -> T,
) {
    tailrec operator fun getValue(thisRef: Any?, prop: KProperty<*>): T {
        val savedValue = getPreferences().getString(prop.name, null)
        if (savedValue == null) {
            setValue(thisRef, prop, getDefault())
            return getValue(thisRef, prop)
        }

        return gson.fromJson(savedValue, typeOfT)
    }

    operator fun setValue(thisRef: Any?, prop: KProperty<*>, value: T) {
        getPreferences().edit {
            putString(prop.name, gson.toJson(value, typeOfT))
        }
    }

    companion object {
        private val gson = GsonBuilder().create()

        inline fun <reified T : Any> gsonInSharedPreferences(
            noinline getPreferences: () -> SharedPreferences,
            noinline getDefault: () -> T,
        ) = GsonInSharedPreferencesDelegate(
            T::class.java,
            getPreferences,
            getDefault,
        )
    }
}