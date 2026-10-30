package com.prahari.verify

import android.app.Application
import com.prahari.verify.data.api.PrahariApi
import com.prahari.verify.data.secure.SecureStorage

/**
 * App holder — sirf SecureStorage + PrahariApi.
 * (AutoFill me yahan Firebase + Gemini bhi the; is app me dono NAHI hain —
 * na photo, na Firebase. Chat Fill sirf text command chat hai, AI chat nahi.)
 */
class PrahariApp : Application() {
    lateinit var secureStorage: SecureStorage
        private set
    lateinit var prahariApi: PrahariApi
        private set

    override fun onCreate() {
        super.onCreate()
        secureStorage = SecureStorage(this)
        prahariApi = PrahariApi(secureStorage)
    }
}
