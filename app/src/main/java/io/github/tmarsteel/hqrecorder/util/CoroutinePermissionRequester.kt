package io.github.tmarsteel.hqrecorder.util

import android.app.Activity
import android.content.pm.PackageManager
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CoroutinePermissionRequester(
    private val activity: Activity,
) {
    private val activeRequests = mutableMapOf<Int, Continuation<Set<String>>>()

    suspend fun requestPermissions(
        permissions: Array<String>,
        requestCode: Int,
    ): Set<String> {
        check(requestCode !in activeRequests) {
            "double-use request code $requestCode"
        }

        if (permissions.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            return permissions.toSet()
        }

        return suspendCoroutine { continuation ->
            activeRequests[requestCode] = continuation
            activity.requestPermissions(permissions, requestCode)
        }
    }

    /**
     * @see Activity.onRequestPermissionsResult
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        val action = activeRequests[requestCode] ?: return
        activeRequests.remove(requestCode)
        val grantedPermissions = HashSet<String>()
        for (idx in permissions.indices) {
            if (grantResults[idx] == PackageManager.PERMISSION_GRANTED) {
                grantedPermissions.add(permissions[idx])
            }
        }
        action.resume(grantedPermissions)
    }

    data class RequestPermissionsResult(
        val requestCode: Int,
        val grantedPermissions: Set<String>,
    )
}