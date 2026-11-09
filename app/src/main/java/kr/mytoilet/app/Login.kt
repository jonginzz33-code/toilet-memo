package kr.mytoilet.app

import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** 구글 로그인 */
object Login {

    /** google-services.json 에 구글 로그인 정보가 있는지 */
    fun ready(a: MainActivity): Boolean =
        a.resources.getIdentifier("default_web_client_id", "string", a.packageName) != 0

    fun start(a: MainActivity, then: (() -> Unit)? = null) {
        val id = a.resources.getIdentifier("default_web_client_id", "string", a.packageName)
        if (id == 0) {
            a.toast("구글 로그인 준비 중이에요 (설정 파일 업데이트 필요)")
            return
        }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(a.getString(id))
            .build()
        val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
        a.lifecycleScope.launch {
            a.busy(true)
            try {
                val res = CredentialManager.create(a).getCredential(a, req)
                val cred = res.credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val g = GoogleIdTokenCredential.createFrom(cred.data)
                    Cloud.auth.signInWithCredential(GoogleAuthProvider.getCredential(g.idToken, null)).await()
                    a.toast("${Cloud.myName}님, 로그인했어요")
                    a.afterLogin()
                    then?.invoke()
                } else {
                    a.toast("구글 계정 정보를 받지 못했어요")
                }
            } catch (e: Exception) {
                a.toast("로그인하지 못했어요: ${e.message ?: "취소됨"}")
            } finally {
                a.busy(false)
            }
        }
    }

    fun logout(a: MainActivity) {
        Cloud.auth.signOut()
        a.isAdmin = false
        a.prefs.uploadedLocal = false
        a.lifecycleScope.launch {
            try { CredentialManager.create(a).clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) } catch (_: Exception) {}
            a.reloadMine()
        }
        a.toast("로그아웃했어요")
    }

    /** 로그인이 필요한 기능 */
    fun need(a: MainActivity, what: String, action: () -> Unit) {
        if (Cloud.loggedIn) { action(); return }
        a.confirm("로그인이 필요해요", "$what 하려면 구글 로그인이 필요해요.", "구글로 로그인") { start(a, action) }
    }
}
