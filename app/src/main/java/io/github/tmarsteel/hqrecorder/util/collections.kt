package io.github.tmarsteel.hqrecorder.util

fun <OriginalKey, OriginalValue> Map<OriginalKey, OriginalValue>.inverse(
    handleDuplicateKeyConflict: (key: OriginalValue, value1: OriginalKey, value2: OriginalKey) -> OriginalKey = { key, _, _ ->
        throw RuntimeException("Duplicate key $key")
    },
    into: MutableMap<OriginalValue, OriginalKey> = HashMap(),
): Map<OriginalValue, OriginalKey> {
    for ((key, value) in this@inverse) {
        into.compute(value) { innerOriginalValue, innerExistingOriginalKey ->
            if (innerExistingOriginalKey == null) {
                key
            } else {
                handleDuplicateKeyConflict(innerOriginalValue, innerExistingOriginalKey, key)
            }
        }
    }

    return into
}