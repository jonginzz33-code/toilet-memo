package kr.mytoilet.app

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/** 앱 안 문의 (사용자 ↔ 운영자 채팅). inquiries/{사용자 uid}/messages */
object Inquiry {
    data class Msg(val fromAdmin: Boolean, val text: String, val at: Long)
    data class Thread(val uid: String, val name: String, val last: String, val lastAt: Long, val unreadAdmin: Boolean)

    private val db get() = FirebaseFirestore.getInstance()
    private fun ref(uid: String) = db.collection("inquiries").document(uid)

    suspend fun messages(uid: String): List<Msg> =
        ref(uid).collection("messages").orderBy("at").get().await().documents.map {
            Msg(it.getString("from") == "admin", it.getString("text") ?: "", it.getLong("at") ?: 0L)
        }

    /** 사용자가 보내기 */
    suspend fun send(text: String) {
        val u = Cloud.uid ?: throw IllegalStateException("로그인이 필요해요")
        val now = System.currentTimeMillis()
        ref(u).collection("messages").add(mapOf("from" to "user", "text" to text, "at" to now)).await()
        ref(u).set(mapOf("name" to Cloud.myName, "last" to text, "lastAt" to now, "unreadAdmin" to true), SetOptions.merge()).await()
    }

    /** 운영자가 답장 */
    suspend fun reply(uid: String, text: String) {
        val now = System.currentTimeMillis()
        ref(uid).collection("messages").add(mapOf("from" to "admin", "text" to text, "at" to now)).await()
        ref(uid).set(mapOf("last" to "운영자: $text", "lastAt" to now, "unreadUser" to true, "unreadAdmin" to false), SetOptions.merge()).await()
    }

    suspend fun markRead(uid: String, asAdmin: Boolean) {
        try { ref(uid).set(mapOf(if (asAdmin) "unreadAdmin" to false else "unreadUser" to false), SetOptions.merge()).await() } catch (_: Exception) {}
    }

    /** 내 문의에 새 답장이 있는지 */
    suspend fun myUnread(): Boolean {
        val u = Cloud.uid ?: return false
        return try { ref(u).get().await().getBoolean("unreadUser") == true } catch (_: Exception) { false }
    }

    /** 운영자: 문의 목록 (최근 순) */
    suspend fun threads(): List<Thread> =
        db.collection("inquiries").orderBy("lastAt", Query.Direction.DESCENDING).limit(200).get().await().documents.map {
            Thread(it.id, it.getString("name") ?: "사용자", it.getString("last") ?: "", it.getLong("lastAt") ?: 0L, it.getBoolean("unreadAdmin") == true)
        }
}
