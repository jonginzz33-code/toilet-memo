package kr.mytoilet.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

/** 서버(Firebase) 작업 모음 */
object Cloud {
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    val uid: String? get() = auth.currentUser?.uid
    val loggedIn: Boolean get() = auth.currentUser != null
    val myName: String get() = auth.currentUser?.displayName ?: "사용자"

    const val DAY = 24L * 60 * 60 * 1000

    // ---------- 사용자 ----------

    suspend fun touchUser() {
        val u = uid ?: return
        db.collection("users").document(u).set(
            mapOf("name" to myName, "lastSeen" to System.currentTimeMillis(), "created" to FieldValue.serverTimestamp()),
            SetOptions.mergeFields("name", "lastSeen")
        ).await()
        // 처음 만드는 경우 created 기록
        val doc = db.collection("users").document(u).get().await()
        if (!doc.contains("created")) {
            db.collection("users").document(u).set(mapOf("created" to System.currentTimeMillis()), SetOptions.merge()).await()
        }
    }

    // ---------- 내 화장실 ----------

    suspend fun myToilets(): List<Toilet> {
        val u = uid ?: return emptyList()
        return db.collection("users").document(u).collection("toilets").get().await()
            .documents.mapNotNull { it.data?.let { m -> Toilet.fromMap(m).apply { scope = "mine" } } }
    }

    suspend fun saveMine(t: Toilet) {
        val u = uid ?: return
        db.collection("users").document(u).collection("toilets").document(t.id).set(t.toMap()).await()
    }

    suspend fun deleteMine(id: String) {
        val u = uid ?: return
        db.collection("users").document(u).collection("toilets").document(id).delete().await()
    }

    // ---------- 그룹 ----------

    suspend fun myGroups(): List<Group> {
        val u = uid ?: return emptyList()
        return db.collection("groups").whereArrayContains("members", u).get().await().documents.map {
            @Suppress("UNCHECKED_CAST")
            Group(it.id, it.getString("name") ?: "그룹", it.getString("owner") ?: "", it.getString("code") ?: "",
                (it.get("members") as? List<String>) ?: emptyList())
        }
    }

    private fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    suspend fun createGroup(name: String): Group {
        val u = uid ?: throw IllegalStateException("로그인이 필요해요")
        val code = newCode()
        val ref = db.collection("groups").document()
        ref.set(mapOf("name" to name, "owner" to u, "code" to code, "members" to listOf(u), "created" to System.currentTimeMillis())).await()
        return Group(ref.id, name, u, code, listOf(u))
    }

    suspend fun joinGroup(code: String): Group? {
        val u = uid ?: return null
        val q = db.collection("groups").whereEqualTo("code", code.uppercase()).limit(1).get().await()
        val d = q.documents.firstOrNull() ?: return null
        d.reference.update("members", FieldValue.arrayUnion(u)).await()
        return Group(d.id, d.getString("name") ?: "그룹", d.getString("owner") ?: "", code, emptyList())
    }

    suspend fun renameGroup(id: String, name: String) {
        db.collection("groups").document(id).update("name", name).await()
    }

    suspend fun leaveGroup(id: String) {
        val u = uid ?: return
        db.collection("groups").document(id).update("members", FieldValue.arrayRemove(u)).await()
    }

    suspend fun deleteGroup(id: String) {
        val tl = db.collection("groups").document(id).collection("toilets").get().await()
        for (d in tl.documents) d.reference.delete().await()
        db.collection("groups").document(id).delete().await()
    }

    suspend fun groupToilets(g: Group): List<Toilet> =
        db.collection("groups").document(g.id).collection("toilets").get().await().documents.mapNotNull {
            it.data?.let { m -> Toilet.fromMap(m).apply { scope = "group"; groupId = g.id; groupName = g.name } }
        }

    suspend fun saveGroupToilet(t: Toilet) {
        t.by = uid ?: ""
        db.collection("groups").document(t.groupId).collection("toilets").document(t.id).set(t.toMap()).await()
    }

    suspend fun deleteGroupToilet(groupId: String, id: String) {
        db.collection("groups").document(groupId).collection("toilets").document(id).delete().await()
    }

    // ---------- 공중화장실 ----------

    val cellCache = HashMap<String, List<PublicToilet>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun publicCell(cell: String): List<PublicToilet> {
        cellCache[cell]?.let { return it }
        // 먼저 GitHub에서 받아 둔 파일, 안 되면 서버(관리자가 올린 데이터)
        val items = try {
            PublicData.cell(cell)
        } catch (e: Exception) {
            val doc = db.collection("publicCells").document(cell).get().await()
            (doc.get("items") as? List<Map<String, Any?>>)?.map { PublicToilet.fromMap(it) } ?: emptyList()
        }
        cellCache[cell] = items
        return items
    }

    /** 최근 24시간 사용불가 신고: 화장실 id → (건수, 가장 최근 시각, 사유들) */
    data class Unavail(val count: Int, val last: Long, val reasons: Map<String, Int>)

    suspend fun unavailable(): Map<String, Unavail> {
        val since = System.currentTimeMillis() - DAY
        val docs = db.collection("unavail").whereGreaterThan("at", since).get().await().documents
        val byToilet = docs.groupBy { it.getString("toiletId") ?: "" }
        val out = HashMap<String, Unavail>()
        for ((tid, list) in byToilet) {
            if (tid.isEmpty()) continue
            val reopen = try {
                db.collection("status").document(tid).get().await().getLong("reopenAt") ?: 0L
            } catch (e: Exception) { 0L }
            val valid = list.filter { (it.getLong("at") ?: 0L) > reopen }
            if (valid.isEmpty()) continue
            val reasons = valid.groupingBy { it.getString("reason") ?: "기타" }.eachCount()
            out[tid] = Unavail(valid.size, valid.maxOf { it.getLong("at") ?: 0L }, reasons)
        }
        return out
    }

    suspend fun reportUnavailable(toiletId: String, reason: String) {
        val u = uid ?: return
        db.collection("unavail").document("${toiletId}_$u").set(
            mapOf("toiletId" to toiletId, "uid" to u, "reason" to reason, "at" to System.currentTimeMillis())
        ).await()
    }

    suspend fun reopen(toiletId: String) {
        db.collection("status").document(toiletId).set(mapOf("reopenAt" to System.currentTimeMillis(), "by" to (uid ?: "")), SetOptions.merge()).await()
    }

    /** 공중화장실 '다녀왔어요': 최근 방문 날짜를 모두에게 보여 줌 */
    suspend fun visit(toiletId: String) {
        db.collection("status").document(toiletId).set(mapOf("visitAt" to System.currentTimeMillis(), "visitBy" to (uid ?: "")), SetOptions.merge()).await()
    }

    suspend fun lastVisit(toiletId: String): Long =
        try { db.collection("status").document(toiletId).get().await().getLong("visitAt") ?: 0L } catch (_: Exception) { 0L }

    // 상태 투표 (최근 60일)
    data class Votes(val clean: Int, val normal: Int, val bad: Int, val tags: Map<String, Int>, val mine: Int?) {
        val total get() = clean + normal + bad
        val score: Double get() = if (total == 0) 0.0 else (clean * 5.0 + normal * 3.0 + bad * 1.0) / total
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun votes(toiletId: String): Votes {
        val since = System.currentTimeMillis() - 60 * DAY
        val docs = db.collection("votes").whereEqualTo("toiletId", toiletId).get().await().documents
            .filter { (it.getLong("at") ?: 0L) > since }
        var c = 0; var n = 0; var b = 0
        val tags = HashMap<String, Int>()
        var mine: Int? = null
        for (d in docs) {
            when ((d.getLong("v") ?: -1L).toInt()) { 0 -> c++; 1 -> n++; 2 -> b++ }
            ((d.get("tags") as? List<String>) ?: emptyList()).forEach { tags[it] = (tags[it] ?: 0) + 1 }
            if (d.getString("uid") == uid) mine = d.getLong("v")?.toInt()
        }
        return Votes(c, n, b, tags, mine)
    }

    suspend fun vote(toiletId: String, v: Int, tags: List<String>) {
        val u = uid ?: return
        db.collection("votes").document("${toiletId}_$u").set(
            mapOf("toiletId" to toiletId, "uid" to u, "v" to v, "tags" to tags, "at" to System.currentTimeMillis())
        ).await()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun myVoteTags(toiletId: String): List<String> {
        val u = uid ?: return emptyList()
        val d = db.collection("votes").document("${toiletId}_$u").get().await()
        return (d.get("tags") as? List<String>) ?: emptyList()
    }

    // 사용자 메모
    suspend fun memos(toiletId: String): List<MemoItem> =
        db.collection("memos").whereEqualTo("toiletId", toiletId).get().await().documents.map {
            MemoItem(it.id, toiletId, it.getString("uid") ?: "", it.getString("nick") ?: "사용자",
                it.getString("text") ?: "", it.getLong("at") ?: 0L)
        }.sortedByDescending { it.at }

    suspend fun addMemo(toiletId: String, text: String) {
        val u = uid ?: return
        db.collection("memos").add(mapOf("toiletId" to toiletId, "uid" to u, "nick" to myName, "text" to text, "at" to System.currentTimeMillis())).await()
    }

    suspend fun editMemo(id: String, text: String) {
        db.collection("memos").document(id).update(mapOf("text" to text, "at" to System.currentTimeMillis())).await()
    }

    suspend fun deleteMemo(id: String) {
        db.collection("memos").document(id).delete().await()
    }

    suspend fun reportMemo(m: MemoItem) {
        val u = uid ?: return
        db.collection("memoReports").document("${m.id}_$u").set(
            mapOf("memoId" to m.id, "text" to m.text, "author" to m.uid, "uid" to u, "at" to System.currentTimeMillis())
        ).await()
    }

    // ---------- 관리자 ----------

    suspend fun adminUid(): String? = try {
        db.collection("config").document("admin").get().await().getString("uid")
    } catch (e: Exception) { null }

    suspend fun isAdmin(): Boolean = uid != null && adminUid() == uid

    suspend fun claimAdmin() {
        val u = uid ?: return
        db.collection("config").document("admin").set(mapOf("uid" to u, "at" to System.currentTimeMillis())).await()
    }

    suspend fun count(col: String): Long = db.collection(col).count().get(AggregateSource.SERVER).await().count

    suspend fun activeToday(): Long {
        val start = System.currentTimeMillis() - DAY
        return db.collection("users").whereGreaterThan("lastSeen", start).count().get(AggregateSource.SERVER).await().count
    }

    suspend fun publicMeta(): Map<String, Any?> =
        db.collection("config").document("public").get().await().data ?: emptyMap()

    suspend fun setPublicMeta(m: Map<String, Any>) {
        db.collection("config").document("public").set(m, SetOptions.merge()).await()
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun allPublicCells(): Map<String, List<PublicToilet>> =
        db.collection("publicCells").get().await().documents.associate { d ->
            d.id to ((d.get("items") as? List<Map<String, Any?>>)?.map { PublicToilet.fromMap(it) } ?: emptyList())
        }

    suspend fun writeCells(cells: Map<String, List<PublicToilet>>, removed: Set<String>) {
        var batch = db.batch()
        var n = 0
        for ((id, items) in cells) {
            batch.set(db.collection("publicCells").document(id), mapOf("items" to items.map { it.toMap() }))
            if (++n % 400 == 0) { batch.commit().await(); batch = db.batch() }
        }
        for (id in removed) {
            batch.delete(db.collection("publicCells").document(id))
            if (++n % 400 == 0) { batch.commit().await(); batch = db.batch() }
        }
        batch.commit().await()
        cellCache.clear()
    }

    data class Report(val id: String, val memoId: String, val text: String, val at: Long)

    suspend fun reports(): List<Report> =
        db.collection("memoReports").get().await().documents.map {
            Report(it.id, it.getString("memoId") ?: "", it.getString("text") ?: "", it.getLong("at") ?: 0L)
        }.sortedByDescending { it.at }

    suspend fun dismissReport(id: String) {
        db.collection("memoReports").document(id).delete().await()
    }

    // ---------- 계정 삭제 ----------

    suspend fun deleteAccountData() {
        val u = uid ?: return
        val mine = db.collection("users").document(u).collection("toilets").get().await()
        for (d in mine.documents) d.reference.delete().await()
        for (col in listOf("memos", "votes", "unavail")) {
            val q = db.collection(col).whereEqualTo("uid", u).get().await()
            for (d in q.documents) d.reference.delete().await()
        }
        for (g in myGroups()) {
            if (g.owner == u) deleteGroup(g.id) else leaveGroup(g.id)
        }
        db.collection("users").document(u).delete().await()
    }
}
