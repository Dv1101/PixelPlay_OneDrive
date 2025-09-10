package com.theveloper.pixelplay.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.theveloper.pixelplay.R
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object GraphAuth {
    private var msalApp: PublicClientApplication? = null
    private val scopes = arrayOf("Files.Read.All", "User.Read")

    suspend fun init(context: Context) = suspendCoroutine<Unit> { cont ->
        if (msalApp != null) {
            cont.resume(Unit)
            return@suspendCoroutine
        }

        PublicClientApplication.create(
            context,
            R.raw.auth_config,
            object : IPublicClientApplication.ApplicationCreatedListener {
                override fun onCreated(application: IPublicClientApplication) {
                    msalApp = application as? PublicClientApplication
                    Log.d("MSAL", "MSAL initialized")
                    cont.resume(Unit)
                }

                override fun onError(exception: MsalException) {
                    Log.e("MSAL", "MSAL init error", exception)
                    cont.resumeWithException(exception)
                }
            }
        )
    }

    suspend fun acquireToken(activity: Activity): String = suspendCoroutine { cont ->
        val app = msalApp
        if (app == null) {
            cont.resumeWithException(IllegalStateException("MSAL not initialized. Call GraphAuth.init(context) first."))
            return@suspendCoroutine
        }

        app.acquireToken(activity, scopes, object : AuthenticationCallback {
            override fun onSuccess(result: IAuthenticationResult) {
                cont.resume(result.accessToken)
            }

            override fun onError(ex: MsalException) {
                cont.resumeWithException(ex)
            }

            override fun onCancel() {
                cont.resumeWithException(Exception("User cancelled login"))
            }
        })
    }
}