package kr.mytoilet.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** 눌렀을 때 아래에서 올라오는 창들 */
object Sheets {

    private fun scopeChip(a: MainActivity, t: Toilet): TextView =
        if (t.scope == "group") a.chip("그룹 · ${t.groupName}", C.GREEN_BG, C.GREEN_TEXT)
        else a.chip("나만 보기", C.BLUE_BG, C.BLUE_TEXT)

    private fun distText(a: MainActivity, lat: Double, lon: Double): String {
        val la = a.myLat ?: return ""
        val lo = a.myLon ?: return ""
        return Geo.fmt(Geo.dist(la, lo, lat, lon))
    }

    // ---------------- 내 화장실 상세 ----------------

    fun toiletDetail(a: MainActivity, t: Toilet) {
        val s = Sheet(a, t.title())
        val top = a.hbox(8)
        top.add(scopeChip(a, t))
        val d = distText(a, t.lat, t.lon)
        if (d.isNotEmpty()) top.add(a.text(d, 13f, C.TEXT2))
        s.add(top)
        // 최근에 언제 갔는지(수정했는지) 맨 위에 크게
        s.add(a.text("🕒 최근 수정 ${Geo.agoShort(t.updated)} · ${Geo.date(t.updated)}", 15f, C.BLUE_TEXT, 700).apply {
            background = a.round(C.BLUE_BG, 12f)
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
        })
        s.add(a.text(if (t.place.isBlank()) "위치 설명 없음" else t.place, 15f, C.TEXT2))

        val pwRow = a.hbox(10).apply {
            background = a.round(C.GRAY_BG, 14f)
            setPadding(a.dp(14), a.dp(10), a.dp(8), a.dp(10))
        }
        pwRow.add(a.text("🔑", 18f))
        pwRow.add(a.text(t.pw.ifBlank { "(비밀번호 없음)" }, 22f, C.TEXT, 700).apply {
            letterSpacing = 0.08f
            setTextIsSelectable(true)
        }, 1f)
        if (t.pw.isNotBlank()) pwRow.add(a.btn("복사", 0xFFFFFFFF.toInt()) { a.copy(t.pw) })
        s.add(pwRow)
        if (t.memo.isNotBlank()) s.add(a.text("📝 ${t.memo}", 14f, C.TEXT2))

        val row = a.hbox(8)
        row.add(a.btn("수정") { s.dismiss(); edit(a, t, t.lat, t.lon) }, 1f)
        row.add(a.btn("위치 옮기기") { s.dismiss(); a.startPlacing(t, t.lat, t.lon) }, 1f)
        row.add(a.btn("삭제", C.RED_BG, C.RED_TEXT) {
            s.dismiss()
            a.confirm("삭제할까요?", "${t.title()} 화장실을 지우면 되돌릴 수 없어요.", "삭제", true) {
                a.lifecycleScope.launch { a.deleteToilet(t); a.toast("삭제했어요") }
            }
        }, 1f)
        s.add(row)
        s.show()
    }

    // ---------------- 추가 · 수정 ----------------

    fun edit(a: MainActivity, t: Toilet?, lat: Double, lon: Double) {
        val s = Sheet(a, if (t == null) "화장실 추가" else "화장실 수정")
        val (b1, building) = a.field("건물 이름 (필수)", "OO타워", t?.building ?: "")
        val (b2, floor) = a.field("층", "3층", t?.floor ?: "")
        val (b3, place) = a.field("화장실 위치 설명", "엘리베이터 내려서 오른쪽 끝, 남자화장실", t?.place ?: "")
        val (b4, pw) = a.field("비밀번호", "1234*", t?.pw ?: "")
        val (b5, memo) = a.field("메모", "휴지 없음, 밤 10시 이후 잠김 등", t?.memo ?: "", multi = true)
        listOf(b1, b2, b3, b4, b5).forEach { s.add(it) }

        var scope = t?.scope ?: "mine"
        var groupId = t?.groupId ?: ""
        val canGroup = Cloud.loggedIn && a.groups.isNotEmpty()
        s.add(a.text("공개 범위", 12f, C.TEXT2, 600))
        val groupBox = a.vbox(6)
        fun fillGroups() {
            groupBox.removeAllViews()
            if (scope != "group" || a.groups.isEmpty()) return
            if (groupId.isEmpty() || a.groups.none { it.id == groupId }) groupId = a.groups.first().id
            val idx = a.groups.indexOfFirst { it.id == groupId }.coerceAtLeast(0)
            groupBox.add(a.text("어느 그룹에 공유할까요?", 12f, C.TEXT2, 600))
            groupBox.add(a.segmented(a.groups.map { it.name }, idx) { groupId = a.groups[it].id })
        }
        s.add(a.segmented(listOf("나만 보기", "그룹 공유"), if (scope == "group") 1 else 0, listOf(true, canGroup)) {
            scope = if (it == 1) "group" else "mine"; fillGroups()
        })
        if (!canGroup) s.add(a.text(if (!Cloud.loggedIn) "그룹 공유는 로그인 후 그룹을 만들면 쓸 수 있어요" else "그룹 공유는 메뉴 → 그룹에서 그룹을 먼저 만들어 주세요", 12f, C.TEXT3))
        s.add(groupBox)
        fillGroups()

        val err = a.text("", 13f, C.RED_TEXT)
        s.add(err)
        val row = a.hbox(8)
        row.add(a.btn("취소") { s.dismiss() }, 1f)
        row.add(a.primaryBtn("저장") {
            val bn = building.text.toString().trim()
            if (bn.isEmpty()) { err.text = "건물 이름을 적어 주세요"; return@primaryBtn }
            val oldScope = t?.scope
            val oldGroup = t?.groupId
            val item = t?.copy() ?: Toilet(lat = lat, lon = lon)
            item.building = bn
            item.floor = floor.text.toString().trim()
            item.place = place.text.toString().trim()
            item.pw = pw.text.toString().trim()
            item.memo = memo.text.toString().trim()
            item.scope = scope
            item.groupId = if (scope == "group") groupId else ""
            item.groupName = a.groups.firstOrNull { it.id == item.groupId }?.name ?: ""
            s.dismiss()
            a.lifecycleScope.launch {
                a.saveToilet(item, oldScope, oldGroup)
                a.toast("저장했어요")
            }
        }, 1f)
        s.add(row)
        s.show()
    }

    // ---------------- 목록 ----------------

    fun list(a: MainActivity) {
        val s = Sheet(a, "내 화장실 (가까운 순)")
        val (clat, clon) = a.myLat?.let { it to (a.myLon ?: 0.0) } ?: a.center()
        val all = (a.mine + a.groupToilets).map { it to Geo.dist(clat, clon, it.lat, it.lon) }.sortedBy { it.second }
        if (all.isEmpty()) s.add(a.text("아직 저장한 화장실이 없어요.\n맨 위 '＋ 지금 위치에 화장실 추가'를 눌러 보세요.", 15f, C.TEXT2))
        for ((t, d) in all.take(100)) {
            val row = a.hbox(10).apply {
                background = a.round(0xFFFFFFFF.toInt(), 14f)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            }
            val tx = a.vbox(2)
            tx.add(a.text(t.title(), 15f, C.TEXT, 700))
            tx.add(a.text("${Geo.fmt(d)} · ${t.place.ifBlank { "위치 설명 없음" }}", 12f, C.TEXT2))
            row.add(tx, 1f)
            row.add(scopeChip(a, t))
            row.setOnClickListener { s.dismiss(); toiletDetail(a, t) }
            s.add(row)
        }
        s.show()
    }

    // ---------------- 공중화장실 상세 ----------------

    fun publicDetail(a: MainActivity, p: PublicToilet) {
        val s = Sheet(a, p.name)
        val info = listOf(distText(a, p.lat, p.lon), p.open.let { if (it.isBlank()) "" else "개방 $it" }).filter { it.isNotBlank() }
        s.add(a.text(info.joinToString(" · ").ifBlank { "공중화장실" }, 14f, C.TEXT2))
        if (p.addr.isNotBlank()) s.add(a.text(p.addr, 13f, C.TEXT3))
        if (p.flags.isNotEmpty()) {
            val fr = a.hbox(6)
            p.flags.forEach { fr.add(a.chip(it, C.GRAY_BG, C.TEXT2)) }
            s.add(fr)
        }

        // 사용불가
        val un = a.unavail[p.id]
        if (un != null && un.count > 0) {
            val box = a.vbox(2).apply {
                background = a.round(C.RED, 14f)
                setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            }
            box.add(a.text("⛔ 사용불가 신고 ${un.count}건", 16f, Color.WHITE, 700))
            box.add(a.text("최근 신고 ${Geo.ago(un.last)} · 사유: " + un.reasons.entries.joinToString(", ") { "${it.key} ${it.value}" }, 12f, Color.WHITE))
            s.add(box)
        }
        val ur = a.hbox(8)
        ur.add(a.btn("⛔ 사용불가 알리기", C.RED_BG, C.RED_TEXT, 0xFFF09595.toInt()) {
            Login.need(a, "사용불가를 알리려면") { s.dismiss(); reportUnavailable(a, p) }
        }, 1f)
        ur.add(a.btn("✅ 다시 열렸어요", C.GREEN_BG, C.GREEN_TEXT, 0xFF5DCAA5.toInt()) {
            Login.need(a, "다시 열렸다고 알리려면") {
                a.lifecycleScope.launch {
                    try { Cloud.reopen(p.id); a.refreshUnavailable(); a.toast("알려 줘서 고마워요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
                    s.dismiss()
                }
            }
        }, 1f)
        s.add(ur)

        val ratingBox = a.vbox(8)
        val memoBox = a.vbox(8)
        s.add(ratingBox)
        s.add(memoBox)
        s.show()

        a.lifecycleScope.launch {
            a.busy(true)
            try {
                val v = Cloud.votes(p.id)
                val myTags = if (Cloud.loggedIn) Cloud.myVoteTags(p.id) else emptyList()
                fillRating(a, ratingBox, p, v, myTags)
                fillMemos(a, memoBox, p, Cloud.memos(p.id))
            } catch (e: Exception) {
                ratingBox.add(a.text("평점·메모를 불러오지 못했어요", 13f, C.TEXT3))
            } finally {
                a.busy(false)
            }
        }
    }

    private fun fillRating(a: MainActivity, box: LinearLayout, p: PublicToilet, v: Cloud.Votes, myTags: List<String>) {
        box.removeAllViews()
        val head = a.hbox(8)
        head.add(a.text(if (v.total == 0) "★ -" else "★ %.1f".format(v.score), 26f, C.TEXT, 700))
        head.add(a.text("깨끗함 · ${v.total}명 · 최근 60일", 13f, C.TEXT2))
        box.add(head)
        if (v.total > 0) {
            val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(v.clean to C.GREEN, v.normal to C.AMBER, v.bad to C.RED).forEach { (n, col) ->
                if (n > 0) bar.addView(View(a).apply { setBackgroundColor(col) }, LinearLayout.LayoutParams(0, a.dp(8), n.toFloat()))
            }
            bar.clipToOutline = true
            bar.background = a.round(C.GRAY_BG, 4f)
            box.add(bar)
            box.add(a.text("깨끗해요 ${v.clean} · 보통 ${v.normal} · 별로예요 ${v.bad}", 12f, C.TEXT3))
        }
        val tags = v.tags.entries.filter { it.value > 0 }
        if (tags.isNotEmpty()) {
            val tr = a.hbox(6)
            tags.forEach { tr.add(a.chip("${it.key} ${it.value}", C.AMBER_BG, C.AMBER_TEXT)) }
            box.add(tr)
        }
        var mineV = v.mine ?: -1
        val chosen = myTags.toMutableSet()
        fun send() {
            if (mineV < 0) { a.toast("먼저 깨끗해요/보통/별로예요 중 하나를 눌러 주세요"); return }
            Login.need(a, "평가하려면") {
                a.lifecycleScope.launch {
                    try { Cloud.vote(p.id, mineV, chosen.toList()); a.toast("반영했어요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }
        }
        box.add(a.text("내 평가", 12f, C.TEXT2, 600))
        box.add(a.segmented(listOf("깨끗해요", "보통", "별로예요"), if (mineV < 0) -1 else mineV) { mineV = it; send() })
        for (tag in listOf("휴지 없음", "고장")) {
            box.add(a.toggleRow(tag, chosen.contains(tag)) { on ->
                if (on) chosen.add(tag) else chosen.remove(tag)
                send()
            })
        }
    }

    private fun fillMemos(a: MainActivity, box: LinearLayout, p: PublicToilet, list: List<MemoItem>) {
        box.removeAllViews()
        val blocked = a.prefs.blocked
        val shown = list.filter { it.uid !in blocked }
        box.add(a.text("사용자 메모 ${shown.size}", 15f, C.TEXT, 700))
        for (m in shown) {
            val card = a.vbox(4).apply {
                background = a.round(0xFFFFFFFF.toInt(), 14f)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            }
            val meta = a.hbox(6)
            val isMine = m.uid == Cloud.uid
            if (isMine) meta.add(a.chip("내 메모", C.BLUE_BG, C.BLUE_TEXT))
            meta.add(a.text("${m.nick} · ${Geo.ago(m.at)}", 12f, C.TEXT3), 1f)
            fun link(s: String, col: Int, on: () -> Unit) = a.text(s, 13f, col, 600).apply {
                setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4)); setOnClickListener { on() }
            }
            if (isMine) {
                meta.add(link("수정", C.TEXT2) {
                    a.ask("메모 수정", "메모", "", m.text) { nt ->
                        a.lifecycleScope.launch {
                            try { Cloud.editMemo(m.id, nt); fillMemos(a, box, p, Cloud.memos(p.id)) } catch (e: Exception) { a.toast("실패: ${e.message}") }
                        }
                    }
                })
            }
            if (isMine || a.isAdmin) {
                meta.add(link("삭제", C.RED_TEXT) {
                    a.confirm("메모를 삭제할까요?", m.text, "삭제", true) {
                        a.lifecycleScope.launch {
                            try { Cloud.deleteMemo(m.id); fillMemos(a, box, p, Cloud.memos(p.id)) } catch (e: Exception) { a.toast("실패: ${e.message}") }
                        }
                    }
                })
            }
            if (!isMine) {
                meta.add(link("신고", C.TEXT2) {
                    Login.need(a, "신고하려면") {
                        a.lifecycleScope.launch {
                            try { Cloud.reportMemo(m); a.toast("신고했어요. 관리자가 확인해요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
                        }
                    }
                })
                meta.add(link("차단", C.TEXT2) {
                    a.confirm("이 사용자를 차단할까요?", "차단하면 이 사람의 메모가 내 화면에서 안 보여요.", "차단") {
                        a.prefs.blocked = a.prefs.blocked + m.uid
                        fillMemos(a, box, p, list)
                    }
                })
            }
            card.add(meta)
            card.add(a.text(m.text, 15f, C.TEXT))
            box.add(card)
        }
        val (fb, et) = a.field("메모 남기기", "밤에는 입구 쪽 불이 꺼져 있어요")
        box.add(fb)
        val err = a.text("", 13f, C.RED_TEXT)
        box.add(err)
        box.add(a.primaryBtn("등록") {
            val t = et.text.toString().trim()
            if (t.isEmpty()) { err.text = "메모를 먼저 써 주세요"; return@primaryBtn }
            err.text = ""
            Login.need(a, "메모를 남기려면") {
                a.lifecycleScope.launch {
                    try { Cloud.addMemo(p.id, t); fillMemos(a, box, p, Cloud.memos(p.id)) } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }
        })
    }

    private fun reportUnavailable(a: MainActivity, p: PublicToilet) {
        val s = Sheet(a, "사용불가 알리기")
        s.add(a.text(p.name, 15f, C.TEXT2))
        s.add(a.text("사유를 골라 주세요. 24시간이 지나거나 누군가 '다시 열렸어요'를 누르면 자동으로 꺼져요.", 13f, C.TEXT3))
        val reasons = listOf("공사 중", "문 잠김", "고장", "청소 중", "기타")
        var pick = 0
        s.add(a.segmented(reasons.take(3), 0) { pick = it })
        s.add(a.segmented(reasons.drop(3), -1) { pick = it + 3 })
        s.add(a.btn("⛔ 사용불가로 알리기", C.RED, Color.WHITE) {
            s.dismiss()
            a.lifecycleScope.launch {
                try { Cloud.reportUnavailable(p.id, reasons[pick]); a.refreshUnavailable(); a.toast("알렸어요. 지도에 바로 표시돼요") } catch (e: Exception) { a.toast("실패: ${e.message}") }
            }
        })
        s.show()
    }

    // ---------------- 메뉴 ----------------

    fun menu(a: MainActivity) {
        val s = Sheet(a, "메뉴")
        // 계정
        val acc = a.vbox(8).apply {
            background = a.round(0xFFFFFFFF.toInt(), 16f)
            setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
        }
        if (Cloud.loggedIn) {
            acc.add(a.text("${Cloud.myName}", 16f, C.TEXT, 700))
            acc.add(a.text(if (a.isAdmin) "구글 계정 · 관리자" else "구글 계정으로 로그인됨", 12f, C.TEXT2))
        } else {
            acc.add(a.text("로그인하면 폰을 바꿔도 내 화장실을 불러올 수 있고, 그룹 공유와 메모를 쓸 수 있어요.", 13f, C.TEXT2))
            acc.add(a.primaryBtn(if (Login.ready(a)) "구글로 로그인" else "구글 로그인 (준비 중)") { s.dismiss(); Login.start(a) })
        }
        s.add(acc)

        s.add(a.btn("👥 그룹 관리") { Login.need(a, "그룹을 쓰려면") { s.dismiss(); groups(a) } })

        s.add(a.text("지도", 12f, C.TEXT2, 600))
        s.add(a.toggleRow("원 안 화장실만 보기", a.prefs.circleOnly, "끄면 원 밖 화장실도 보여요") {
            a.prefs.circleOnly = it
            a.setCircleShown(it)
            a.onCameraEnd()
        })
        s.add(a.toggleRow("공중화장실 보기", a.prefs.showPublic) {
            a.prefs.showPublic = it
            a.onCameraEnd()
        })
        s.add(a.text("내 위치 캐릭터", 12f, C.TEXT2, 600))
        s.add(a.segmented(listOf("동글이", "휴지 친구", "물방울이"), a.prefs.character) {
            a.prefs.character = it
            a.refreshCharacter()
        })

        s.add(a.btn("업데이트 확인 · 지금 v${BuildConfig.VERSION_NAME}") { a.checkUpdate(silent = false) })
        if (a.isAdmin || Cloud.loggedIn) s.add(a.btn("🛠 관리자 페이지") { s.dismiss(); admin(a) })
        s.add(a.btn("개인정보처리방침") {
            a.open("https://github.com/${BuildConfig.GITHUB_REPO}/blob/main/docs/privacy.md")
        })
        if (Cloud.loggedIn) {
            val row = a.hbox(8)
            row.add(a.btn("로그아웃") { s.dismiss(); Login.logout(a) }, 1f)
            row.add(a.btn("계정 삭제", C.RED_BG, C.RED_TEXT) {
                s.dismiss()
                a.confirm("계정을 삭제할까요?", "내 화장실, 메모, 평가, 그룹(내가 만든 그룹)이 모두 지워지고 되돌릴 수 없어요.", "삭제", true) {
                    a.lifecycleScope.launch {
                        a.busy(true)
                        try {
                            Cloud.deleteAccountData()
                            try { Cloud.auth.currentUser?.delete()?.await() } catch (_: Exception) {}
                            Login.logout(a)
                            a.toast("계정을 삭제했어요")
                        } catch (e: Exception) {
                            a.toast("삭제하지 못했어요: ${e.message}")
                        } finally { a.busy(false) }
                    }
                }
            }, 1f)
            s.add(row)
        }
        s.show()
    }

    // ---------------- 그룹 ----------------

    fun groups(a: MainActivity) {
        val s = Sheet(a, "그룹")
        val listBox = a.vbox(8)
        s.add(listBox)
        fun reload() {
            a.lifecycleScope.launch {
                a.busy(true)
                try { a.groups = Cloud.myGroups() } catch (e: Exception) { a.toast("불러오지 못했어요: ${e.message}") }
                finally { a.busy(false) }
                listBox.removeAllViews()
                if (a.groups.isEmpty()) listBox.add(a.text("아직 그룹이 없어요. 새 그룹을 만들거나 초대 코드로 참여하세요.", 14f, C.TEXT2))
                for (g in a.groups) {
                    val card = a.vbox(6).apply {
                        background = a.round(0xFFFFFFFF.toInt(), 16f)
                        setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
                    }
                    val owner = g.owner == Cloud.uid
                    val head = a.hbox(6)
                    head.add(a.text(g.name, 16f, C.TEXT, 700), 1f)
                    if (owner) head.add(a.chip("그룹장", C.GREEN_BG, C.GREEN_TEXT))
                    card.add(head)
                    card.add(a.text("${g.members.size}명 · 초대 코드 ${g.code}", 13f, C.TEXT2))
                    val row = a.hbox(6)
                    row.add(a.btn("코드 복사") { a.copy(g.code) }, 1f)
                    if (owner) {
                        row.add(a.btn("이름 바꾸기") {
                            a.ask("그룹 이름 바꾸기", "그룹 이름", "우리 가족", g.name) { n ->
                                a.lifecycleScope.launch { try { Cloud.renameGroup(g.id, n); reload(); a.reloadMine() } catch (e: Exception) { a.toast("실패: ${e.message}") } }
                            }
                        }, 1f)
                        row.add(a.btn("삭제", C.RED_BG, C.RED_TEXT) {
                            a.confirm("그룹을 삭제할까요?", "그룹에 공유한 화장실도 모두 지워져요.", "삭제", true) {
                                a.lifecycleScope.launch { try { Cloud.deleteGroup(g.id); reload(); a.reloadMine() } catch (e: Exception) { a.toast("실패: ${e.message}") } }
                            }
                        }, 1f)
                    } else {
                        row.add(a.btn("나가기", C.RED_BG, C.RED_TEXT) {
                            a.confirm("그룹에서 나갈까요?", "${g.name} 그룹의 화장실이 더 이상 안 보여요.", "나가기", true) {
                                a.lifecycleScope.launch { try { Cloud.leaveGroup(g.id); reload(); a.reloadMine() } catch (e: Exception) { a.toast("실패: ${e.message}") } }
                            }
                        }, 1f)
                    }
                    card.add(row)
                    listBox.add(card)
                }
            }
        }
        val row = a.hbox(8)
        row.add(a.primaryBtn("새 그룹 만들기") {
            a.ask("새 그룹", "그룹 이름", "우리 가족") { n ->
                a.lifecycleScope.launch {
                    try { val g = Cloud.createGroup(n); a.toast("만들었어요. 초대 코드: ${g.code}"); reload(); a.reloadMine() } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }
        }, 1f)
        row.add(a.btn("코드로 참여") {
            a.ask("그룹 참여", "초대 코드 6자리", "ABC123") { c ->
                a.lifecycleScope.launch {
                    try {
                        val g = Cloud.joinGroup(c.trim())
                        if (g == null) a.toast("그런 코드의 그룹이 없어요") else { a.toast("${g.name} 그룹에 참여했어요"); reload(); a.reloadMine() }
                    } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }
        }, 1f)
        s.add(row)
        s.show()
        reload()
    }

    // ---------------- 이용약관 ----------------

    fun terms(a: MainActivity) {
        val s = Sheet(a, "이용약관 동의")
        s.add(a.text(
            "• 다른 사람을 불쾌하게 하는 메모, 거짓 사용불가 신고, 광고는 올리지 않아요.\n" +
                "• 신고가 들어온 메모는 관리자가 지울 수 있어요.\n" +
                "• 공중화장실 메모·평가는 모든 사용자에게 보여요. 내 화장실은 나와 고른 그룹만 봐요.\n" +
                "• 위치는 앱을 쓰는 동안 지도 표시에만 쓰고 서버에 저장하지 않아요.",
            14f, C.TEXT2
        ))
        val row = a.hbox(8)
        row.add(a.btn("동의 안 함") { s.dismiss(); Login.logout(a) }, 1f)
        row.add(a.primaryBtn("동의해요") { a.prefs.termsAgreed = true; s.dismiss() }, 1f)
        s.add(row)
        s.dialog.setCancelable(false)
        s.show()
    }

    // ---------------- 관리자 ----------------

    fun admin(a: MainActivity) {
        val s = Sheet(a, "관리자 페이지")
        val box = a.vbox(10)
        s.add(box)
        s.show()
        a.lifecycleScope.launch {
            a.busy(true)
            try {
                val adminUid = Cloud.adminUid()
                if (adminUid == null) {
                    box.add(a.text("아직 관리자가 없어요. 처음 한 번만 등록하면 이 계정이 관리자가 돼요.", 14f, C.TEXT2))
                    box.add(a.primaryBtn("이 계정을 관리자로 등록") {
                        a.lifecycleScope.launch {
                            try { Cloud.claimAdmin(); a.isAdmin = true; s.dismiss(); admin(a) } catch (e: Exception) { a.toast("실패: ${e.message}") }
                        }
                    })
                    return@launch
                }
                if (adminUid != Cloud.uid) {
                    box.add(a.text("관리자만 볼 수 있어요.", 14f, C.TEXT2))
                    return@launch
                }
                a.isAdmin = true
                fillAdmin(a, box)
            } catch (e: Exception) {
                box.add(a.text("불러오지 못했어요: ${e.message}", 14f, C.RED_TEXT))
            } finally {
                a.busy(false)
            }
        }
    }

    private suspend fun fillAdmin(a: MainActivity, box: LinearLayout) {
        box.add(a.text("사용 현황", 12f, C.TEXT2, 600))
        val grid = a.vbox(6)
        fun stat(label: String, v: String) {
            val r = a.hbox(8).apply {
                background = a.round(0xFFFFFFFF.toInt(), 12f)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            }
            r.add(a.text(label, 14f, C.TEXT2), 1f)
            r.add(a.text(v, 17f, C.TEXT, 700))
            grid.add(r)
        }
        fun safe(block: suspend () -> Long): suspend () -> String = { try { block().toString() } catch (e: Exception) { "-" } }
        stat("전체 사용자", safe { Cloud.count("users") }())
        stat("최근 24시간 접속", safe { Cloud.activeToday() }())
        stat("그룹 수", safe { Cloud.count("groups") }())
        stat("사용자 메모", safe { Cloud.count("memos") }())
        val meta = try { Cloud.publicMeta() } catch (e: Exception) { emptyMap() }
        stat("공중화장실", (meta["count"] as? Number)?.toString() ?: "아직 없음")
        stat("마지막 공중화장실 업데이트", (meta["updatedAt"] as? Number)?.let { Geo.ago(it.toLong()) } ?: "-")
        box.add(grid)
        box.add(a.btn("서버 사용량 보기 (Firebase)") {
            a.open("https://console.firebase.google.com/project/my-toilet-ce481/usage")
        })

        // 공중화장실 업데이트
        box.add(a.text("공중화장실 데이터", 12f, C.TEXT2, 600))
        val (ub, urlField) = a.field("API 주소", PublicApi.DEFAULT_URL, (meta["apiUrl"] as? String) ?: PublicApi.DEFAULT_URL)
        box.add(ub)
        val out = a.text("", 13f, C.TEXT2).apply { setTextIsSelectable(true) }
        val apply = a.vbox(8)
        val row = a.hbox(8)
        row.add(a.btn("응답 미리보기") {
            a.lifecycleScope.launch {
                a.busy(true)
                try {
                    val raw = PublicApi.fetchRaw(urlField.text.toString().trim(), 1, 3)
                    val parsed = try {
                        val j: Any = if (raw.trim().startsWith("[")) org.json.JSONArray(raw) else org.json.JSONObject(raw)
                        PublicApi.findItems(j)?.optJSONObject(0)?.let { PublicApi.parse(it) }
                    } catch (e: Exception) { null }
                    out.text = (if (parsed != null) "✅ 읽기 성공: ${parsed.name} (${parsed.lat}, ${parsed.lon}) ${parsed.flags}\n\n" else "⚠ 항목을 못 읽었어요\n\n") + raw.take(1500)
                } catch (e: Exception) {
                    out.text = "실패: ${e.message}"
                } finally { a.busy(false) }
            }
        }, 1f)
        row.add(a.primaryBtn("새 데이터 확인") {
            apply.removeAllViews()
            a.lifecycleScope.launch {
                a.busy(true)
                val url = urlField.text.toString().trim()
                try {
                    out.text = "받는 중…"
                    val list = PublicApi.fetchAll(url) { n, total -> out.text = "받는 중… $n" + if (total > 0) " / $total" else "" }
                    if (list.isEmpty()) { out.text = "받은 화장실이 없어요. 응답 미리보기로 확인해 주세요"; return@launch }
                    out.text = "서버 데이터와 비교 중…"
                    val old = Cloud.allPublicCells()
                    val oldById = old.values.flatten().associateBy { it.id }
                    val newById = list.associateBy { it.id }
                    val added = newById.keys.count { it !in oldById }
                    val removed = oldById.keys.count { it !in newById }
                    val changed = newById.count { (id, p) -> oldById[id]?.let { it != p } ?: false }
                    val newCells = list.groupBy { Geo.cell(it.lat, it.lon) }
                    val changedCells = newCells.filter { (id, items) -> old[id]?.toSet() != items.toSet() }
                    val removedCells = old.keys - newCells.keys
                    out.text = "전체 ${list.size}곳\n새로 생긴 곳 ${added}곳 · 바뀐 곳 ${changed}곳 · 없어진 곳 ${removed}곳"
                    if (changedCells.isEmpty() && removedCells.isEmpty()) {
                        apply.add(a.text("업데이트할 게 없어요 ✅", 14f, C.GREEN_TEXT, 700))
                        Cloud.setPublicMeta(mapOf("count" to list.size, "updatedAt" to System.currentTimeMillis(), "apiUrl" to url))
                    } else {
                        apply.add(a.primaryBtn("서버에 반영하기 (${changedCells.size + removedCells.size}칸)") {
                            a.lifecycleScope.launch {
                                a.busy(true)
                                try {
                                    Cloud.writeCells(changedCells, removedCells)
                                    Cloud.setPublicMeta(mapOf("count" to list.size, "updatedAt" to System.currentTimeMillis(), "apiUrl" to url))
                                    apply.removeAllViews()
                                    apply.add(a.text("반영했어요 ✅ 사용자 지도에 바로 보여요", 14f, C.GREEN_TEXT, 700))
                                    a.onCameraEnd()
                                } catch (e: Exception) { a.toast("실패: ${e.message}") } finally { a.busy(false) }
                            }
                        })
                    }
                } catch (e: Exception) {
                    out.text = "실패: ${e.message}"
                } finally { a.busy(false) }
            }
        }, 1f)
        box.add(row)
        box.add(out)
        box.add(apply)

        // 신고된 메모
        box.add(a.text("신고된 메모", 12f, C.TEXT2, 600))
        val reports = try { Cloud.reports() } catch (e: Exception) { emptyList() }
        if (reports.isEmpty()) box.add(a.text("신고된 메모가 없어요", 14f, C.TEXT3))
        for (r in reports.take(50)) {
            val card = a.vbox(6).apply {
                background = a.round(0xFFFFFFFF.toInt(), 14f)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            }
            card.add(a.text(r.text, 14f, C.TEXT))
            card.add(a.text("신고 ${Geo.ago(r.at)}", 12f, C.TEXT3))
            val rr = a.hbox(6)
            rr.add(a.btn("메모 삭제", C.RED_BG, C.RED_TEXT) {
                a.lifecycleScope.launch {
                    try { Cloud.deleteMemo(r.memoId); Cloud.dismissReport(r.id); card.visibility = View.GONE } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }, 1f)
            rr.add(a.btn("그대로 두기") {
                a.lifecycleScope.launch {
                    try { Cloud.dismissReport(r.id); card.visibility = View.GONE } catch (e: Exception) { a.toast("실패: ${e.message}") }
                }
            }, 1f)
            card.add(rr)
            box.add(card)
        }
    }
}
