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

    @Suppress("UNCHECKED_CAST")
    private fun toGroup(it: com.google.firebase.firestore.DocumentSnapshot) = Group(
        it.id, it.getString("name") ?: "그룹", it.getString("owner") ?: "", it.getString("code") ?: "",
        (it.get("members") as? List<String>) ?: emptyList(),
        (it.get("subs") as? List<String>) ?: emptyList(),
        (it.get("names") as? Map<String, String>) ?: emptyMap(),
        (it.getLong("maxMembers") ?: 0L).toInt()
    )

    suspend fun myGroups(): List<Group> {
        val u = uid ?: return emptyList()
        val list = db.collection("groups").whereArrayContains("members", u).get().await().documents.map { toGroup(it) }
        // 내 이름이 없거나 바뀌었으면 넣어 둠 (멤버 목록에 이름 보이게)
        for (g in list) if (g.names[u] != myName) try {
            db.collection("groups").document(g.id).update("names.$u", myName).await()
        } catch (_: Exception) {}
        return list
    }

    suspend fun allGroups(): List<Group> =
        db.collection("groups").get().await().documents.map { toGroup(it) }

    suspend fun groupConfig(): GroupConfig = try {
        val d = db.collection("config").document("groups").get().await()
        GroupConfig((d.getLong("defaultMax") ?: 10L).toInt(), (d.getLong("maxGroups") ?: 3L).toInt())
    } catch (_: Exception) { GroupConfig() }

    suspend fun setGroupConfig(c: GroupConfig) {
        db.collection("config").document("groups").set(mapOf("defaultMax" to c.defaultMax, "maxGroups" to c.maxGroups)).await()
    }

    suspend fun setGroupMax(id: String, max: Int) {
        db.collection("groups").document(id).update("maxMembers", max).await()
    }

    private fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    suspend fun createGroup(name: String): Group {
        val u = uid ?: throw IllegalStateException("로그인이 필요해요")
        val code = newCode()
        val ref = db.collection("groups").document()
        ref.set(mapOf("name" to name, "owner" to u, "code" to code, "members" to listOf(u), "subs" to emptyList<String>(),
            "names" to mapOf(u to myName), "created" to System.currentTimeMillis())).await()
        return Group(ref.id, name, u, code, listOf(u), emptyList(), mapOf(u to myName))
    }

    /** 코드로 가입 요청. 결과 문구를 돌려줌 */
    suspend fun requestJoin(code: String): String {
        val u = uid ?: return "로그인이 필요해요"
        val q = db.collection("groups").whereEqualTo("code", code.trim().uppercase()).limit(1).get().await()
        val d = q.documents.firstOrNull() ?: return "그런 코드의 그룹이 없어요"
        val g = toGroup(d)
        if (u in g.members) return "이미 '${g.name}' 그룹에 들어가 있어요"
        val cfg = groupConfig()
        if (g.members.size >= g.limit(cfg.defaultMax)) return "'${g.name}' 그룹이 꽉 찼어요 (${g.members.size}/${g.limit(cfg.defaultMax)}명)"
        val mine = db.collection("groups").whereArrayContains("members", u).get().await().size()
        if (mine >= cfg.maxGroups) return "그룹은 최대 ${cfg.maxGroups}개까지 들어갈 수 있어요"
        d.reference.collection("requests").document(u).set(mapOf("name" to myName, "at" to System.currentTimeMillis())).await()
        return "'${g.name}'에 가입 요청을 보냈어요.\n방장이나 부방장이 수락하면 들어가져요."
    }

    suspend fun joinRequests(gid: String): List<JoinRequest> =
        db.collection("groups").document(gid).collection("requests").get().await().documents.map {
            JoinRequest(it.id, it.getString("name") ?: "사용자", it.getLong("at") ?: 0L)
        }.sortedBy { it.at }

    /** 가입 요청 수락 (방장·부방장). 실패하면 이유 문구 */
    suspend fun acceptJoin(g: Group, r: JoinRequest): String? {
        val cfg = groupConfig()
        val fresh = toGroup(db.collection("groups").document(g.id).get().await())
        if (fresh.members.size >= fresh.limit(cfg.defaultMax)) return "그룹이 꽉 찼어요 (${fresh.members.size}/${fresh.limit(cfg.defaultMax)}명)"
        val theirs = db.collection("groups").whereArrayContains("members", r.uid).get().await().size()
        if (theirs >= cfg.maxGroups) {
            rejectJoin(g.id, r.uid)
            return "${r.name}님은 이미 그룹 ${cfg.maxGroups}개에 들어가 있어서 받을 수 없어요"
        }
        db.collection("groups").document(g.id).update(mapOf("members" to FieldValue.arrayUnion(r.uid), "names.${r.uid}" to r.name)).await()
        rejectJoin(g.id, r.uid)
        return null
    }

    suspend fun rejectJoin(gid: String, who: String) {
        db.collection("groups").document(gid).collection("requests").document(who).delete().await()
    }

    /** 내보내기 (방장·부방장) */
    suspend fun kick(gid: String, who: String) {
        db.collection("groups").document(gid).update(mapOf("members" to FieldValue.arrayRemove(who), "subs" to FieldValue.arrayRemove(who))).await()
    }

    /** 부방장 지정·해제 (방장만) */
    suspend fun setSub(gid: String, who: String, on: Boolean) {
        db.collection("groups").document(gid).update("subs", if (on) FieldValue.arrayUnion(who) else FieldValue.arrayRemove(who)).await()
    }

    /** 초대 코드 새로 만들기 (방장만) */
    suspend fun newGroupCode(gid: String): String {
        val c = newCode()
        db.collection("groups").document(gid).update("code", c).await()
        return c
    }

    suspend fun renameGroup(id: String, name: String) {
        db.collection("groups").document(id).update("name", name).await()
    }

    suspend fun leaveGroup(id: String) {
        val u = uid ?: return
        db.collection("groups").document(id).update(mapOf("members" to FieldValue.arrayRemove(u), "subs" to FieldValue.arrayRemove(u))).await()
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
